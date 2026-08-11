package com.wilder0p.lorecleaner.manager;

import com.wilder0p.lorecleaner.LoreCleanerPlugin;
import com.wilder0p.lorecleaner.model.OfflinePlayerCandidate;
import com.wilder0p.lorecleaner.util.BarrelPlacer;
import com.wilder0p.lorecleaner.util.DiscordWebhook;
import com.wilder0p.lorecleaner.util.OfflinePlayerData;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;

public class CleanerManager {

    private final LoreCleanerPlugin plugin;
    private final BarrelPlacer barrelPlacer;

    private final File cleanLogFile;
    private final File failedLogFile;

    private final Queue<UUID> processQueue = new LinkedList<>();
    private boolean currentlyProcessing = false;
    private boolean buildingQueue = false;
    private boolean testRunning = false;
    private int processedThisCycle = 0;
    private int processedThisMinute = 0;
    private long minuteWindowStartMs = 0;

    private BukkitTask tickTask;

    public CleanerManager(LoreCleanerPlugin plugin) {
        this.plugin = plugin;
        this.barrelPlacer = new BarrelPlacer(plugin);
        File logDir = new File(plugin.getDataFolder(), "logs");
        if (!logDir.exists()) logDir.mkdirs();
        this.cleanLogFile = new File(logDir, "cleaned.log");
        this.failedLogFile = new File(logDir, "failed.log");
    }

    public void start() {
        minuteWindowStartMs = System.currentTimeMillis();
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        plugin.getDataManager().saveIfDirty();
    }

    public boolean isTestRunning() {
        return testRunning;
    }

    public void setTestRunning(boolean running) {
        this.testRunning = running;
    }

    public void forceRun() {
        if (currentlyProcessing || buildingQueue || testRunning) {
            plugin.getLogger().info("Force run ignored — already processing or building queue / test running.");
            return;
        }
        plugin.getLogger().info("Force run requested — building clean queue asynchronously...");
        startProcessingCycle(true);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        if (now - minuteWindowStartMs >= 60_000L) {
            minuteWindowStartMs = now;
            processedThisMinute = 0;
        }

        if (testRunning || buildingQueue) return;

        if (!currentlyProcessing) {
            if (plugin.getDataManager().isInGracePeriod()) return;
            Instant last = plugin.getDataManager().getLastFullRunCompleted();
            if (last != null) {
                long cooldownMs = plugin.getConfigManager().getCooldownAfterFullRunHours() * 3600_000L;
                if (now - last.toEpochMilli() < cooldownMs) return;
            }
            if (!plugin.getTpsMonitor().isStableAt20()) return;
            startProcessingCycle(false);
            return;
        }

        if (!plugin.getTpsMonitor().isExactly20()) return;

        int maxPerMin = plugin.getConfigManager().getPlayersPerMinute();
        if (processedThisMinute >= maxPerMin) return;

        if (processQueue.isEmpty()) {
            finishCycle();
            return;
        }

        UUID uuid = processQueue.poll();
        if (uuid == null) return;
        processPlayer(uuid);
        processedThisCycle++;
        processedThisMinute++;
    }

    private void startProcessingCycle(boolean force) {
        if (currentlyProcessing || buildingQueue) return;
        buildingQueue = true;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<UUID> built = buildQueue();
            Bukkit.getScheduler().runTask(plugin, () -> {
                processQueue.clear();
                processQueue.addAll(built);
                buildingQueue = false;
                if (processQueue.isEmpty()) {
                    plugin.getLogger().info("Clean queue empty — nothing to process.");
                    if (force) {
                        // still mark nothing
                    }
                    return;
                }
                currentlyProcessing = true;
                processedThisCycle = 0;
                plugin.getLogger().info("Clean cycle started — queue size " + processQueue.size()
                        + (force ? " (force)" : ""));
            });
        });
    }

    private void finishCycle() {
        currentlyProcessing = false;
        plugin.getDataManager().setLastFullRunCompleted(Instant.now());
        plugin.getDataManager().saveIfDirty();
        plugin.getLogger().info("Clean cycle finished — processed " + processedThisCycle + " players this cycle.");
        processedThisCycle = 0;
    }

    private List<UUID> buildQueue() {
        DataManager dataMgr = plugin.getDataManager();
        long inactiveMs = plugin.getConfigManager().getInactiveDays() * 86400_000L;
        long recheckMs = plugin.getConfigManager().getRecheckDays() * 86400_000L;
        long now = System.currentTimeMillis();

        List<OfflinePlayerCandidate> candidates = new ArrayList<>();
        for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
            if (offline.getUniqueId() == null) continue;
            if (offline.isOnline()) continue;
            long lastPlayed;
            try {
                lastPlayed = offline.getLastPlayed();
            } catch (Exception e) {
                continue;
            }
            if (lastPlayed <= 0) continue;
            if (now - lastPlayed < inactiveMs) continue;

            Instant lastCleaned = dataMgr.getLastCleaned(offline.getUniqueId());
            if (lastCleaned != null && now - lastCleaned.toEpochMilli() < recheckMs) continue;

            if (dataMgr.wasScannedAtLastPlayed(offline.getUniqueId(), lastPlayed)) continue;

            candidates.add(new OfflinePlayerCandidate(offline.getUniqueId(), lastPlayed));
        }

        candidates.sort(Comparator.comparingLong(c -> c.lastPlayed));
        List<UUID> result = new ArrayList<>(candidates.size());
        for (OfflinePlayerCandidate c : candidates) {
            result.add(c.uuid);
        }
        return result;
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

        BarrelPlacer.PlacementResult placement = barrelPlacer.placeAndVerify(placeLoc, loreItems, name);

        if (!placement.success()) {
            String reason = placement.rollbackReason != null
                    ? placement.rollbackReason
                    : "barrel verification failed";
            logFailed(uuid, "ABORT — " + reason + " — playerdata left untouched, barrels rolled back");
            return;
        }

        if (Bukkit.getPlayer(uuid) != null) {
            placement.rollback();
            logFailed(uuid, "Player logged in during processing — barrels rolled back, playerdata NOT modified");
            return;
        }

        try {
            data.save();
        } catch (Exception e) {
            logFailed(uuid, "playerdata save failed after verified barrel placement: " + e.getMessage()
                    + " — barrels left in world (possible duplicate if retried)");
            return;
        }

        if (!data.hadConversionFailures()) {
            plugin.getDataManager().markCleanedAndScanned(uuid, lastPlayed);
        } else {
            plugin.getDataManager().markScanned(uuid, lastPlayed);
            plugin.getLogger().warning("Player " + name + " had some unreadable items; not marking fully cleaned.");
        }

        String logLine = String.format("[%s] Cleaned %s (%s) — %d lore items moved into %d barrel(s) [verified]",
                Instant.now(), name, uuid, loreItems.size(), placement.barrelCount());
        plugin.getLogger().info(logLine);
        appendCleanLog(logLine);

        if (plugin.getConfigManager().isDiscordEnabled()) {
            DiscordWebhook.send(plugin.getConfigManager().getDiscordWebhookUrl(),
                    name, loreItems.size(), placement.barrelCount());
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
