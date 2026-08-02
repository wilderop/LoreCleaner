package com.wilder0p.lorecleaner.manager;

import com.wilder0p.lorecleaner.LoreCleanerPlugin;
import com.wilder0p.lorecleaner.model.OfflinePlayerCandidate;
import com.wilder0p.lorecleaner.util.BarrelPlacer;
import com.wilder0p.lorecleaner.util.DiscordWebhook;
import com.wilder0p.lorecleaner.util.OfflinePlayerData;
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

    private final Queue<UUID> processQueue = new ConcurrentLinkedQueue<>();
    private BukkitTask scanTask;
    private BukkitTask processTask;
    private boolean forceRun = false;
    private boolean currentlyProcessing = false;
    private boolean testRunning = false;
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
    }

    public void start() {
        scanTask = Bukkit.getScheduler().runTaskTimer(plugin, this::decisionTick, 100L, 600L);
    }

    public void shutdown() {
        if (scanTask != null) scanTask.cancel();
        if (processTask != null) processTask.cancel();
        scanService.shutdown();
        testRunning = false;
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
        if (currentlyProcessing) return;

        DataManager data = plugin.getDataManager();
        ConfigManager cfg = plugin.getConfigManager();

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
            buildQueue();
            if (processQueue.isEmpty()) {
                if (forceRun) {
                    forceRun = false;
                    plugin.getLogger().info("Force run finished — no eligible players found.");
                }
                return;
            }
            plugin.getLogger().info(
                    "Built processing queue with " + processQueue.size() + " eligible offline players (oldest first).");
        }

        currentlyProcessing = true;
        processedThisCycle = 0;
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

    private void buildQueue() {
        processQueue.clear();
        ConfigManager cfg = plugin.getConfigManager();
        DataManager data = plugin.getDataManager();

        long inactiveMs = cfg.getInactiveDays() * 86400L * 1000L;
        long recheckMs = cfg.getRecheckDays() * 86400L * 1000L;
        long now = System.currentTimeMillis();

        List<OfflinePlayerCandidate> candidates = new ArrayList<>();

        for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
            if (offline.getUniqueId() == null) continue;
            if (offline.isOnline()) continue;

            long lastPlayed = offline.getLastPlayed();
            if (lastPlayed <= 0) continue;
            if (now - lastPlayed < inactiveMs) continue;

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
        for (OfflinePlayerCandidate c : candidates) {
            processQueue.add(c.uuid);
        }
    }

    private void processPlayer(UUID uuid) {
        if (Bukkit.getPlayer(uuid) != null) {
            return;
        }

        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        String name = offline.getName() != null ? offline.getName() : uuid.toString();
        long lastPlayed = offline.getLastPlayed();

        OfflinePlayerData.LoadResult loaded = OfflinePlayerData.loadDetailed(plugin, uuid);
        if (loaded.data == null) {
            logFailed(uuid, loaded.status + ": " + loaded.detail);
            return;
        }
        OfflinePlayerData data = loaded.data;

        List<ItemStack> scanned = data.scanLoreItems();

        if (scanned.isEmpty()) {
            if (!data.hadConversionFailures()) {
                plugin.getDataManager().markScanned(uuid, lastPlayed);
            } else {
                logFailed(uuid, "Had unreadable items; not marking scanned so they can be retried later");
            }
            return;
        }

        Location logoutLoc = data.getLogoutLocation();
        if (logoutLoc == null || logoutLoc.getWorld() == null) {
            logFailed(uuid, "No valid logout location in playerdata — items left untouched");
            return;
        }

        Location placeLoc = barrelPlacer.findSafeBarrelLocation(logoutLoc);
        if (placeLoc == null) {
            logFailed(uuid, "Could not find a valid air block inside world border — items left untouched");
            return;
        }

        if (Bukkit.getPlayer(uuid) != null) {
            return;
        }

        List<ItemStack> loreItems = data.extractAndRemoveLoreItems();
        if (loreItems.isEmpty()) {
            return;
        }

        int barrelsPlaced = barrelPlacer.placeBarrelsWithItems(placeLoc, loreItems, name);

        if (Bukkit.getPlayer(uuid) != null) {
            logFailed(uuid, "Player logged in during processing — barrels placed but playerdata NOT modified");
            return;
        }

        try {
            data.save();
        } catch (Exception e) {
            logFailed(uuid, "playerdata save failed after barrel placement: " + e.getMessage());
            return;
        }

        if (!data.hadConversionFailures()) {
            plugin.getDataManager().markCleanedAndScanned(uuid, lastPlayed);
        } else {
            plugin.getDataManager().markScanned(uuid, lastPlayed);
            plugin.getLogger().warning("Player " + name + " had some unreadable items; not marking fully cleaned.");
        }

        String logLine = String.format("[%s] Cleaned %s (%s) — %d lore items moved into %d barrel(s)",
                Instant.now(), name, uuid, loreItems.size(), barrelsPlaced);
        plugin.getLogger().info(logLine);
        appendCleanLog(logLine);

        if (plugin.getConfigManager().isDiscordEnabled()) {
            DiscordWebhook.send(plugin.getConfigManager().getDiscordWebhookUrl(),
                    name, loreItems.size(), barrelsPlaced);
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
