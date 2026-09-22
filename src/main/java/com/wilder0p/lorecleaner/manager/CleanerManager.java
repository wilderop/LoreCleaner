package com.wilder0p.lorecleaner.manager;

import com.wilder0p.lorecleaner.LoreCleanerPlugin;
import com.wilder0p.lorecleaner.model.OfflinePlayerCandidate;
import com.wilder0p.lorecleaner.util.BarrelPlacer;
import com.wilder0p.lorecleaner.util.DiscordWebhook;
import com.wilder0p.lorecleaner.util.LogoutOwner;
import com.wilder0p.lorecleaner.util.OfflinePlayerData;
import com.wilder0p.lorecleaner.util.PdsStore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

/**
 * Live cleaning cycle: TPS-gated, longest-offline-first queue.
 * Dry/test scans live in {@link ScanService}.
 */
public class CleanerManager {

    private final LoreCleanerPlugin plugin;
    private final BarrelPlacer barrelPlacer;
    private final ScanService scanService;
    private final PdsStore pdsStore;

    private final Queue<UUID> processQueue = new ConcurrentLinkedQueue<>();
    private BukkitTask scanTask;
    private BukkitTask processTask;
    private boolean forceRun = false;
    private boolean currentlyProcessing = false;
    private boolean testRunning = false;
    private boolean buildingQueue = false;
    private int processedThisCycle = 0;

    private final File logDir;
    private final File cleanLogFile;
    private final File failedLogFile;

    public CleanerManager(LoreCleanerPlugin plugin) {
        this.plugin = plugin;
        this.logDir = new File(plugin.getDataFolder(), "logs");
        if (!logDir.exists()) logDir.mkdirs();
        this.cleanLogFile = new File(logDir, "cleaned.log");
        this.failedLogFile = new File(logDir, "failed-loads.log");
        this.barrelPlacer = new BarrelPlacer(plugin);
        this.scanService = new ScanService(plugin, this, logDir);
        this.pdsStore = new PdsStore(plugin);
        this.pdsStore.start();
    }

    public void start() {
        scanTask = Bukkit.getScheduler().runTaskTimer(plugin, this::decisionTick, 100L, 600L);
    }

    public void shutdown() {
        if (scanTask != null) scanTask.cancel();
        if (processTask != null) processTask.cancel();
        scanService.shutdown();
        testRunning = false;
        buildingQueue = false;
    }

    public boolean isTestRunning() {
        return testRunning;
    }

    void setTestRunning(boolean running) {
        this.testRunning = running;
    }

    public void forceRun() {
        this.forceRun = true;
        plugin.getLogger().info(
                "Force run requested. Will start as soon as TPS conditions allow (or immediately if already stable).");
        decisionTick();
    }

    public void startDryRun(CommandSender sender, int months, int limit) {
        scanService.startDryRun(sender, months, limit);
    }

    public void startTestRun(CommandSender sender, int months, int limit) {
        scanService.startTestRun(sender, months, limit);
    }

    private void decisionTick() {
        if (currentlyProcessing || buildingQueue) return;

        DataManager data = plugin.getDataManager();
        ConfigManager cfg = plugin.getConfigManager();
        if (!cfg.isEnabled()) {
            return;
        }

        if (data.isInGracePeriod() && !forceRun) {
            return;
        }

        Instant lastFull = data.getLastFullRunCompleted();
        if (!forceRun && lastFull != null) {
            Instant nextAllowed = lastFull.plus(cfg.getCooldownAfterFullRunHours(), ChronoUnit.HOURS);
            if (Instant.now().isBefore(nextAllowed)) {
                return;
            }
        }

        if (!forceRun && !plugin.getTpsMonitor().isStable()) {
            return;
        }

        if (processQueue.isEmpty()) {
            buildingQueue = true;
            plugin.getLogger().info("Building clean queue asynchronously (avoids main-thread .dat reads)...");
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                List<UUID> built = buildQueueAsync();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    buildingQueue = false;
                    processQueue.clear();
                    processQueue.addAll(built);
                    if (processQueue.isEmpty()) {
                        if (forceRun) {
                            forceRun = false;
                            plugin.getLogger().info("Force run finished — no eligible players found.");
                        }
                        return;
                    }
                    plugin.getLogger().info(
                            "Built processing queue with " + processQueue.size()
                                    + " eligible offline players (oldest first).");
                    startProcessing();
                });
            });
            return;
        }

        startProcessing();
    }

    private void startProcessing() {
        if (currentlyProcessing) return;
        currentlyProcessing = true;
        processedThisCycle = 0;
        ConfigManager cfg = plugin.getConfigManager();
        DataManager data = plugin.getDataManager();
        int delayTicks = Math.max(1, 1200 / Math.max(1, cfg.getPlayersPerMinute()));

        processTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!forceRun && !plugin.getTpsMonitor().isStable()) {
                return;
            }

            UUID next = processQueue.poll();
            if (next == null) {
                processTask.cancel();
                currentlyProcessing = false;
                forceRun = false;
                data.setLastFullRunCompleted(Instant.now());
                data.saveIfDirty();
                plugin.getLogger().info("Full cleaning cycle completed. Next automatic run in "
                        + cfg.getCooldownAfterFullRunHours() + " hours. Scan snapshots: "
                        + data.getScannedSnapshotCount());
                return;
            }

            Player online = Bukkit.getPlayer(next);
            if (online != null && online.isOnline()) {
                return;
            }

            try {
                processPlayer(next);
                processedThisCycle++;
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to process player " + next, e);
                logFailed(next, e.getMessage());
            }
        }, 1L, delayTicks);
    }

    private List<UUID> buildQueueAsync() {
        ConfigManager cfg = plugin.getConfigManager();
        DataManager data = plugin.getDataManager();

        long inactiveMs = cfg.getInactiveDays() * 86400L * 1000L;
        long recheckMs = cfg.getRecheckDays() * 86400L * 1000L;
        long now = System.currentTimeMillis();

        List<OfflinePlayerCandidate> candidates = new ArrayList<>();

        OfflinePlayer[] offlinePlayers = Bukkit.getOfflinePlayers();
        for (OfflinePlayer offline : offlinePlayers) {
            if (offline.getUniqueId() == null) continue;
            if (offline.isOnline()) continue;

            long lastPlayed;
            try {
                lastPlayed = offline.getLastPlayed();
            } catch (Exception e) {
                continue;
            }
            if (lastPlayed <= 0) continue;
            long lastSeen = LogoutOwner.lastSeenMs(cfg, offline.getUniqueId(), lastPlayed);
            if (now - lastSeen < inactiveMs) continue;
            if (!LogoutOwner.isOurs(cfg, offline.getUniqueId())) {
                continue;
            }

            if (data.wasScannedAtLastPlayed(offline.getUniqueId(), lastPlayed)) {
                continue;
            }

            Instant lastCleaned = data.getLastCleaned(offline.getUniqueId());
            if (lastCleaned != null) {
                long sinceCleaned = now - lastCleaned.toEpochMilli();
                if (sinceCleaned < recheckMs) continue;
            }

            candidates.add(new OfflinePlayerCandidate(offline.getUniqueId(), lastPlayed));
        }

        candidates.sort(Comparator.comparingLong(c -> c.lastPlayed));
        List<UUID> result = new ArrayList<>(candidates.size());
        for (OfflinePlayerCandidate c : candidates) {
            result.add(c.uuid);
        }
        return result;
    }

    /**
     * C1: .dat disk/NBT work runs on an async thread; only world mutation runs on
     * the main thread. C3: inactivity is revalidated immediately before mutation.
     */
    private void processPlayer(UUID uuid) {
        if (Bukkit.getPlayer(uuid) != null) {
            return;
        }
        if (plugin.getDataManager().redis().isNetworkOnline(uuid)) {
            return; // fail-closed: unknown Redis state counts as online
        }

        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        String name = offline.getName() != null ? offline.getName() : uuid.toString();
        long lastPlayed = offline.getLastPlayed();
        ConfigManager cfg = plugin.getConfigManager();
        if (!LogoutOwner.isOurs(cfg, uuid)) {
            return;
        }
        if (LogoutOwner.recentlyTouched(LogoutOwner.fabricDat(cfg, uuid), 10 * 60 * 1000L)) {
            return;
        }

        // --- async stage: disk + NBT only, no world access ---
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            OfflinePlayerData.LoadResult loaded = OfflinePlayerData.loadDetailed(plugin, uuid);
            if (loaded.data == null) {
                if (loaded.status == OfflinePlayerData.LoadStatus.FILE_MISSING) {
                    // m5: no .dat exists — nothing to clean. Record the scan so this
                    // player stops spamming failed-loads.log every cycle. A future
                    // login changes lastPlayed and re-enables them automatically.
                    Bukkit.getScheduler().runTask(plugin, () ->
                            plugin.getDataManager().markScanned(uuid, lastPlayed));
                } else {
                    String reason = loaded.status + ": " + loaded.detail;
                    Bukkit.getScheduler().runTask(plugin, () -> logFailed(uuid, reason));
                }
                return;
            }
            OfflinePlayerData data = loaded.data;

            List<ItemStack> scanned = data.scanLoreItems();
            if (scanned.isEmpty()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!data.hadConversionFailures()) {
                        plugin.getDataManager().markScanned(uuid, lastPlayed);
                    } else {
                        logFailed(uuid, "Had unreadable items; not marking scanned so they can be retried later");
                    }
                });
                return;
            }

            // N9: capture the pre-extraction inventory state so the PDS strip can
            // verify the DB still holds exactly these items before modifying it.
            Map<String, List<String>> preCleanSigs = data.canonicalSlotSignatures();

            List<ItemStack> loreItems = data.extractAndRemoveLoreItems();
            if (loreItems.isEmpty()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!data.hadConversionFailures()) {
                        plugin.getDataManager().markScanned(uuid, lastPlayed);
                    }
                });
                return;
            }

            final List<ItemStack> items = loreItems;
            Bukkit.getScheduler().runTask(plugin, () ->
                    processPlayerSync(uuid, name, lastPlayed, data, items, preCleanSigs));
        });
    }

    /** Main-thread stage: revalidate everything, then place barrels and save. */
    private void processPlayerSync(UUID uuid, String name, long lastPlayed,
                                   OfflinePlayerData data, List<ItemStack> loreItems,
                                   Map<String, List<String>> preCleanSigs) {
        // C3: revalidate inactivity immediately before mutation.
        if (Bukkit.getPlayer(uuid) != null
                || plugin.getDataManager().redis().isNetworkOnline(uuid)
                || Bukkit.getOfflinePlayer(uuid).getLastPlayed() != lastPlayed
                || LogoutOwner.recentlyTouched(
                        LogoutOwner.fabricDat(plugin.getConfigManager(), uuid), 10 * 60 * 1000L)) {
            logFailed(uuid, "Player state changed during processing — items left untouched");
            return;
        }

        Location logoutLoc = data.getLogoutLocation();
        if (logoutLoc == null || logoutLoc.getWorld() == null) {
            logFailed(uuid, "No valid logout location in playerdata — items left untouched");
            return;
        }

        // M6: preload the logout chunk asynchronously; skip when it cannot be
        // loaded from disk without generating terrain.
        logoutLoc.getWorld().getChunkAtAsync(logoutLoc, false).thenAccept(chunk ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (chunk == null || !chunk.isLoaded()) {
                        logFailed(uuid, "Logout chunk not loadable without terrain generation — skipping");
                        return;
                    }
                    placeAndSave(uuid, name, lastPlayed, data, loreItems, preCleanSigs, logoutLoc);
                }));
    }

    private void placeAndSave(UUID uuid, String name, long lastPlayed,
                              OfflinePlayerData data, List<ItemStack> loreItems,
                              Map<String, List<String>> preCleanSigs, Location logoutLoc) {
        if (Bukkit.getPlayer(uuid) != null) {
            logFailed(uuid, "Player logged in before placement — items left untouched");
            return;
        }

        Location placeLoc = barrelPlacer.findSafeBarrelLocation(logoutLoc);
        if (placeLoc == null) {
            logFailed(uuid, "Could not find a valid air block inside world border — items left untouched");
            return;
        }

        BarrelPlacer.PlacementResult placed;
        try {
            placed = barrelPlacer.placeBarrelsWithItems(placeLoc, loreItems, name);
        } catch (Exception e) {
            // placeBarrelsWithItems already rolled back any partial placement.
            logFailed(uuid, "Barrel placement failed and was rolled back — items left untouched: " + e.getMessage());
            return;
        }

        if (!placed.complete) {
            // N1: ran out of air — roll back BEFORE the .dat save so nothing is lost.
            barrelPlacer.rollbackPlacement(placed);
            logFailed(uuid, "Placement incomplete (" + placed.itemsPlaced + "/" + loreItems.size()
                    + " items) — rolled back, items left untouched");
            return;
        }

        if (Bukkit.getPlayer(uuid) != null) {
            // N8: player logged in during processing — roll back, never save.
            barrelPlacer.rollbackPlacement(placed);
            logFailed(uuid, "Player logged in during processing — barrels rolled back, playerdata NOT modified");
            return;
        }

        try {
            data.save();
        } catch (Exception e) {
            // N7: .dat save failed — roll back the barrels so items are neither
            // duplicated nor lost.
            barrelPlacer.rollbackPlacement(placed);
            logFailed(uuid, "playerdata save failed after barrel placement — barrels rolled back: " + e.getMessage());
            return;
        }

        // N9: strip PDS lore only when the DB payload still matches the pre-clean
        // local state; mismatch skips loudly inside stripLore.
        pdsStore.stripLore(uuid, preCleanSigs);

        if (!data.hadConversionFailures()) {
            plugin.getDataManager().markCleanedAndScanned(uuid, lastPlayed);
        } else {
            plugin.getDataManager().markScanned(uuid, lastPlayed);
            plugin.getLogger().warning("Player " + name + " had some unreadable items; not marking fully cleaned.");
        }

        String logLine = String.format("[%s] Cleaned %s (%s) — %d lore items moved into %d barrel(s)",
                Instant.now(), name, uuid, loreItems.size(), placed.barrelsPlaced);
        plugin.getLogger().info(logLine);
        appendCleanLog(logLine);

        if (plugin.getConfigManager().isDiscordEnabled()) {
            DiscordWebhook.send(plugin.getConfigManager().getDiscordWebhookUrl(),
                    name, loreItems.size(), placed.barrelsPlaced);
        }
    }

    private void appendCleanLog(String line) {
        try (PrintWriter out = new PrintWriter(new FileWriter(cleanLogFile, true))) {
            out.println(line);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not write to cleaned.log");
        }
    }

    private void logFailed(UUID uuid, String reason) {
        String line = String.format("[%s] FAILED %s — %s", Instant.now(), uuid, reason);
        plugin.getLogger().warning(line);
        try (PrintWriter out = new PrintWriter(new FileWriter(failedLogFile, true))) {
            out.println(line);
        } catch (IOException ignored) {}
    }

    public int getQueueSize() {
        return processQueue.size();
    }

    public boolean isCurrentlyProcessing() {
        return currentlyProcessing;
    }

    public int getProcessedThisCycle() {
        return processedThisCycle;
    }
}
