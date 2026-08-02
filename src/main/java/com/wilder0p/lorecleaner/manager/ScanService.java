package com.wilder0p.lorecleaner.manager;

import com.wilder0p.lorecleaner.LoreCleanerPlugin;
import com.wilder0p.lorecleaner.model.OfflinePlayerCandidate;
import com.wilder0p.lorecleaner.util.ItemFormatter;
import com.wilder0p.lorecleaner.util.OfflinePlayerData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Dry-run and full diagnostic (test) scans. Never modifies playerdata or places blocks.
 */
public class ScanService {

    private final LoreCleanerPlugin plugin;
    private final CleanerManager cleaner;
    private final File logDir;

    private BukkitTask scanTask;

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
            .withZone(ZoneId.systemDefault());

    public ScanService(LoreCleanerPlugin plugin, CleanerManager cleaner, File logDir) {
        this.plugin = plugin;
        this.cleaner = cleaner;
        this.logDir = logDir;
    }

    public void shutdown() {
        if (scanTask != null) {
            scanTask.cancel();
            scanTask = null;
        }
    }

    /**
     * Dry-run: skips players already scanned at current lastPlayed.
     * Zero-lore → markScanned. Lore hits → report only (live clean still processes them).
     */
    public void startDryRun(CommandSender sender, int months, int limit) {
        if (cleaner.isTestRunning()) {
            sender.sendMessage(Component.text("A test/dry scan is already running. Wait for it to finish.", NamedTextColor.RED));
            return;
        }
        if (cleaner.isCurrentlyProcessing()) {
            sender.sendMessage(Component.text("A live cleaning cycle is running. Wait for it to finish.", NamedTextColor.RED));
            return;
        }
        if (months < 1) {
            sender.sendMessage(Component.text("Months must be a whole number >= 1.", NamedTextColor.RED));
            return;
        }

        cleaner.setTestRunning(true);
        sender.sendMessage(Component.text(
                "Building dry-run list async (" + months + " months) — avoids main-thread .dat reads...",
                NamedTextColor.GRAY));

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            DataManager dataMgr = plugin.getDataManager();
            long inactiveMs = months * 30L * 86400L * 1000L;
            long now = System.currentTimeMillis();

            List<OfflinePlayerCandidate> candidates = new ArrayList<>();
            int skippedUnchanged = 0;
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
                if (dataMgr.wasScannedAtLastPlayed(offline.getUniqueId(), lastPlayed)) {
                    skippedUnchanged++;
                    continue;
                }
                candidates.add(new OfflinePlayerCandidate(offline.getUniqueId(), lastPlayed));
            }
            candidates.sort(Comparator.comparingLong(c -> c.lastPlayed));

            final int skippedSnapshot = skippedUnchanged;
            final List<OfflinePlayerCandidate> built = candidates;

            Bukkit.getScheduler().runTask(plugin, () ->
                    continueDryRun(sender, months, limit, built, skippedSnapshot, now));
        });
    }

    private void continueDryRun(CommandSender sender, int months, int limit,
                                List<OfflinePlayerCandidate> candidates, int skippedUnchanged, long now) {
        if (candidates.isEmpty()) {
            cleaner.setTestRunning(false);
            sender.sendMessage(Component.text(
                    "Nothing to dry-scan for " + months + "+ months. All eligible were already scanned at current lastPlayed"
                            + " (skipped unchanged: " + skippedUnchanged + ").",
                    NamedTextColor.YELLOW));
            return;
        }

        DataManager dataMgr = plugin.getDataManager();
        final int eligibleNew = candidates.size();
        final List<OfflinePlayerCandidate> toScan = applyLimit(candidates, limit);

        String stamp = FILE_TS.format(Instant.now());
        File dryLog = new File(logDir, "dry-" + stamp + ".log");

        sender.sendMessage(Component.text(
                "Starting dry-run for " + months + " months inactivity cutoff. No files or blocks will be modified.",
                NamedTextColor.GREEN));
        sender.sendMessage(Component.text(
                "Need scan: " + eligibleNew
                        + " | skipped unchanged: " + skippedUnchanged
                        + (limit > 0 ? " | scanning first " + toScan.size() : " | scanning all " + toScan.size())
                        + " — logs/" + dryLog.getName(),
                NamedTextColor.GRAY));
        sender.sendMessage(Component.text(
                "Progress every minute. Zero-lore results are remembered; lore hits are only reported.",
                NamedTextColor.GRAY));

        final int total = toScan.size();
        final int skippedSnapshot = skippedUnchanged;
        final int[] index = {0};
        final int[] playersWithItems = {0};
        final int[] playersWithZeroLore = {0};
        final int[] totalLoreItems = {0};
        final int[] failedLoads = {0};
        final int[] missingFiles = {0};
        final long startMs = System.currentTimeMillis();
        final long[] lastProgressMs = {startMs};

        try (PrintWriter header = new PrintWriter(new FileWriter(dryLog, false))) {
            header.println("LoreCleaner dry-run (skip unchanged snapshots)");
            header.println("Started: " + dataMgr.format(Instant.now()));
            header.println("Inactivity cutoff: " + months + " month(s) (~" + (months * 30) + " days)");
            header.println("Skipped unchanged (already scanned at current lastPlayed): " + skippedSnapshot);
            header.println("Candidates needing scan: " + eligibleNew);
            header.println("Actually scanning: " + total);
            header.println("NOTE: Dry run — no playerdata writes, no barrels.");
            header.println("NOTE: Zero-lore players are markScanned so future runs skip them.");
            header.println("NOTE: Players WITH lore are reported only (not markScanned) so live clean can still process them.");
            header.println("========================================================================");
            header.println();
        } catch (IOException e) {
            sender.sendMessage(Component.text("Could not create dry log file: " + e.getMessage(), NamedTextColor.RED));
            cleaner.setTestRunning(false);
            return;
        }

        scanTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (index[0] >= total) {
                finishDry(sender, dryLog, dataMgr, total, skippedSnapshot,
                        playersWithItems[0], playersWithZeroLore[0], missingFiles[0],
                        failedLoads[0], totalLoreItems[0], startMs);
                return;
            }

            long nowMs = System.currentTimeMillis();
            if (nowMs - lastProgressMs[0] >= 60_000L) {
                lastProgressMs[0] = nowMs;
                int pct = (index[0] * 100) / Math.max(1, total);
                sender.sendMessage(Component.text(
                        "Dry progress: " + index[0] + "/" + total + " (" + pct + "%) — "
                                + playersWithItems[0] + " with lore so far",
                        NamedTextColor.YELLOW));
            }

            OfflinePlayerCandidate c = toScan.get(index[0]++);
            OfflinePlayer offline = Bukkit.getOfflinePlayer(c.uuid);
            if (offline.isOnline()) return;

            String name = offline.getName() != null ? offline.getName() : c.uuid.toString();
            long daysAgo = Math.max(0, (now - c.lastPlayed) / 86400_000L);
            String lastPlayedStr = dataMgr.format(Instant.ofEpochMilli(c.lastPlayed));

            OfflinePlayerData.LoadResult loaded = OfflinePlayerData.loadDetailed(plugin, c.uuid);
            if (loaded.data == null) {
                if (loaded.status == OfflinePlayerData.LoadStatus.FILE_MISSING) {
                    missingFiles[0]++;
                } else {
                    failedLoads[0]++;
                    if (failedLoads[0] <= 50) {
                        appendLine(dryLog, String.format(
                                "[%s] %s (%s)%n  Last played: %s (%d days ago)%n  READ ERROR: %s%n",
                                dataMgr.format(Instant.now()), name, c.uuid,
                                lastPlayedStr, daysAgo, loaded.detail));
                    }
                }
                return;
            }

            OfflinePlayerData pdata = loaded.data;
            List<ItemStack> loreItems = pdata.scanLoreItems();

            if (loreItems.isEmpty()) {
                if (pdata.hadConversionFailures()) {
                    failedLoads[0]++;
                    appendLine(dryLog, String.format(
                            "[%s] %s (%s)%n  Last played: %s (%d days ago)%n  Lore items: 0 (had unreadable items)%n",
                            dataMgr.format(Instant.now()), name, c.uuid, lastPlayedStr, daysAgo));
                } else {
                    playersWithZeroLore[0]++;
                    dataMgr.markScanned(c.uuid, c.lastPlayed);
                }
                return;
            }

            playersWithItems[0]++;
            totalLoreItems[0] += loreItems.size();

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("[%s] %s (%s)%n", dataMgr.format(Instant.now()), name, c.uuid));
            sb.append(String.format("  Last played: %s (%d days ago)%n", lastPlayedStr, daysAgo));
            sb.append(String.format("  Lore items: %d%n", loreItems.size()));
            for (ItemStack stack : loreItems) {
                sb.append("    - ").append(ItemFormatter.describe(stack)).append('\n');
            }
            if (pdata.hadConversionFailures()) {
                sb.append("  NOTE: some items could not be converted and were skipped\n");
            }
            appendLine(dryLog, sb.toString());
        }, 1L, 5L);
    }

    private void finishDry(CommandSender sender, File dryLog, DataManager dataMgr,
                           int total, int skippedSnapshot, int withLore, int zeroLore,
                           int missing, int errors, int items, long startMs) {
        if (scanTask != null) {
            scanTask.cancel();
            scanTask = null;
        }
        cleaner.setTestRunning(false);
        dataMgr.saveIfDirty();

        long elapsedSec = Math.max(1, (System.currentTimeMillis() - startMs) / 1000);
        try (PrintWriter out = new PrintWriter(new FileWriter(dryLog, true))) {
            out.println();
            out.println("========================================================================");
            out.println("SUMMARY");
            out.println("  Skipped unchanged (before scan): " + skippedSnapshot);
            out.println("  Players scanned this dry-run:    " + total);
            out.println("  Players with lore items:         " + withLore);
            out.println("  Players with zero lore (marked): " + zeroLore);
            out.println("  Missing .dat:                    " + missing);
            out.println("  Read errors:                     " + errors);
            out.println("  Total lore items found:          " + items);
            out.println("  Elapsed:                         " + elapsedSec + "s");
            out.println("Finished: " + dataMgr.format(Instant.now()));
        } catch (IOException ignored) {}

        sender.sendMessage(Component.text("Dry-run complete.", NamedTextColor.GREEN));
        sender.sendMessage(Component.text(
                "Scanned " + total
                        + " | with lore: " + withLore
                        + " | zero lore marked: " + zeroLore
                        + " | skipped unchanged: " + skippedSnapshot
                        + " | missing: " + missing
                        + " | errors: " + errors
                        + " | items: " + items,
                NamedTextColor.WHITE));
        sender.sendMessage(Component.text("Report: plugins/LoreCleaner/logs/" + dryLog.getName(), NamedTextColor.GRAY));
        plugin.getLogger().info("Dry-run finished → " + dryLog.getName());
    }

    /**
     * Full diagnostic: ignores prior scan snapshots. Does not markScanned.
     */
    public void startTestRun(CommandSender sender, int months, int limit) {
        if (cleaner.isTestRunning()) {
            sender.sendMessage(Component.text("A test/dry scan is already running. Wait for it to finish.", NamedTextColor.RED));
            return;
        }
        if (months < 1) {
            sender.sendMessage(Component.text("Months must be a whole number >= 1.", NamedTextColor.RED));
            return;
        }

        cleaner.setTestRunning(true);
        sender.sendMessage(Component.text(
                "Building test candidate list async (" + months + " months) — avoids main-thread .dat reads...",
                NamedTextColor.GRAY));

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            long inactiveMs = months * 30L * 86400L * 1000L;
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
                candidates.add(new OfflinePlayerCandidate(offline.getUniqueId(), lastPlayed));
            }
            candidates.sort(Comparator.comparingLong(c -> c.lastPlayed));

            final List<OfflinePlayerCandidate> built = candidates;
            Bukkit.getScheduler().runTask(plugin, () ->
                    continueTestRun(sender, months, limit, built, now));
        });
    }

    private void continueTestRun(CommandSender sender, int months, int limit,
                                 List<OfflinePlayerCandidate> candidates, long now) {
        if (candidates.isEmpty()) {
            cleaner.setTestRunning(false);
            sender.sendMessage(Component.text(
                    "No offline players found inactive for " + months + "+ months.", NamedTextColor.YELLOW));
            return;
        }

        final int eligibleTotal = candidates.size();
        final List<OfflinePlayerCandidate> toScan = applyLimit(candidates, limit);

        String stamp = FILE_TS.format(Instant.now());
        File testLog = new File(logDir, "test-" + stamp + ".log");

        int datOnDisk = censusDatFiles();
        if (!toScan.isEmpty()) {
            File probe = OfflinePlayerData.findPlayerDat(toScan.get(0).uuid);
            plugin.getLogger().info("Probe path for first candidate "
                    + toScan.get(0).uuid + ": "
                    + (probe == null ? "NOT FOUND" : probe.getAbsolutePath()));
        }

        sender.sendMessage(Component.text(
                "Starting dry-run test for " + months + " months inactivity cutoff.", NamedTextColor.GREEN));
        if (limit > 0) {
            sender.sendMessage(Component.text(
                    "Eligible offline: " + eligibleTotal
                            + " | scanning first " + toScan.size() + " (oldest offline first)"
                            + " | .dat on disk: " + datOnDisk
                            + " — logs/" + testLog.getName(),
                    NamedTextColor.GRAY));
        } else {
            sender.sendMessage(Component.text(
                    "Eligible offline (usercache): " + eligibleTotal
                            + " | .dat files on disk (all worlds): " + datOnDisk
                            + " — writing to logs/" + testLog.getName(),
                    NamedTextColor.GRAY));
        }
        sender.sendMessage(Component.text(
                "Progress updates every minute. This does NOT move any items.", NamedTextColor.GRAY));
        if (datOnDisk == 0) {
            sender.sendMessage(Component.text(
                    "WARNING: census found 0 .dat files — see console path report. Loads may still work via fallback.",
                    NamedTextColor.YELLOW));
        }

        final int total = toScan.size();
        final int[] index = {0};
        final int[] playersWithItems = {0};
        final int[] playersWithZeroLore = {0};
        final int[] totalLoreItems = {0};
        final int[] failedLoads = {0};
        final int[] missingFiles = {0};
        final long startMs = System.currentTimeMillis();
        final long[] lastProgressMs = {startMs};

        try (PrintWriter header = new PrintWriter(new FileWriter(testLog, false))) {
            header.println("LoreCleaner dry-run test");
            header.println("Started: " + plugin.getDataManager().format(Instant.now()));
            header.println("Inactivity cutoff: " + months + " month(s) (~" + (months * 30) + " days)");
            header.println("Eligible offline (usercache matching cutoff): " + eligibleTotal);
            if (limit > 0) {
                header.println("Scan limit: " + limit + " (oldest offline first) — actually scanning: " + total);
            } else {
                header.println("Scan limit: none — scanning all " + total);
            }
            header.println("NOTE: This is a dry run. No items were moved. No playerdata was modified.");
            header.println("NOTE: Players with 0 lore items are counted in the summary only (not listed).");
            header.println("========================================================================");
            header.println();
        } catch (IOException e) {
            sender.sendMessage(Component.text("Could not create test log file: " + e.getMessage(), NamedTextColor.RED));
            cleaner.setTestRunning(false);
            return;
        }

        scanTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (index[0] >= total) {
                finishTest(sender, testLog, total, playersWithItems[0], playersWithZeroLore[0],
                        missingFiles[0], failedLoads[0], totalLoreItems[0], startMs);
                return;
            }

            long nowMs = System.currentTimeMillis();
            if (nowMs - lastProgressMs[0] >= 60_000L) {
                lastProgressMs[0] = nowMs;
                int pct = (index[0] * 100) / Math.max(1, total);
                sender.sendMessage(Component.text(
                        "Test progress: " + index[0] + "/" + total + " (" + pct + "%) — "
                                + playersWithItems[0] + " players with lore so far",
                        NamedTextColor.YELLOW));
            }

            OfflinePlayerCandidate c = toScan.get(index[0]++);
            OfflinePlayer offline = Bukkit.getOfflinePlayer(c.uuid);
            if (offline.isOnline()) return;

            String name = offline.getName() != null ? offline.getName() : c.uuid.toString();
            long daysAgo = Math.max(0, (now - c.lastPlayed) / 86400_000L);
            String lastPlayedStr = plugin.getDataManager().format(Instant.ofEpochMilli(c.lastPlayed));

            OfflinePlayerData.LoadResult loaded = OfflinePlayerData.loadDetailed(plugin, c.uuid);
            if (loaded.data == null) {
                if (loaded.status == OfflinePlayerData.LoadStatus.FILE_MISSING) {
                    missingFiles[0]++;
                } else {
                    failedLoads[0]++;
                    if (failedLoads[0] <= 50) {
                        appendLine(testLog, String.format(
                                "[%s] %s (%s)%n  Last played: %s (%d days ago)%n  READ ERROR: %s%n",
                                plugin.getDataManager().format(Instant.now()), name, c.uuid,
                                lastPlayedStr, daysAgo, loaded.detail));
                    }
                }
                return;
            }

            OfflinePlayerData data = loaded.data;
            List<ItemStack> loreItems = data.scanLoreItems();

            if (loreItems.isEmpty()) {
                if (data.hadConversionFailures()) {
                    failedLoads[0]++;
                    appendLine(testLog, String.format(
                            "[%s] %s (%s)%n  Last played: %s (%d days ago)%n  Lore items: 0 (had unreadable items)%n",
                            plugin.getDataManager().format(Instant.now()), name, c.uuid, lastPlayedStr, daysAgo));
                } else {
                    playersWithZeroLore[0]++;
                }
                return;
            }

            playersWithItems[0]++;
            totalLoreItems[0] += loreItems.size();

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("[%s] %s (%s)%n", plugin.getDataManager().format(Instant.now()), name, c.uuid));
            sb.append(String.format("  Last played: %s (%d days ago)%n", lastPlayedStr, daysAgo));
            sb.append(String.format("  Lore items: %d%n", loreItems.size()));
            for (ItemStack stack : loreItems) {
                sb.append("    - ").append(ItemFormatter.describe(stack)).append('\n');
            }
            if (data.hadConversionFailures()) {
                sb.append("  NOTE: some items in this file could not be converted and were skipped\n");
            }
            appendLine(testLog, sb.toString());
        }, 1L, 5L);
    }

    private void finishTest(CommandSender sender, File testLog, int total, int withLore, int zeroLore,
                            int missing, int errors, int items, long startMs) {
        if (scanTask != null) {
            scanTask.cancel();
            scanTask = null;
        }
        cleaner.setTestRunning(false);

        long elapsedSec = Math.max(1, (System.currentTimeMillis() - startMs) / 1000);
        try (PrintWriter out = new PrintWriter(new FileWriter(testLog, true))) {
            out.println();
            out.println("========================================================================");
            out.println("SUMMARY");
            out.println("  Players scanned:              " + total);
            out.println("  Players with lore items:      " + withLore + "  (detailed above)");
            out.println("  Players with zero lore items: " + zeroLore + "  (not listed individually)");
            out.println("  Missing .dat on disk:         " + missing + "  (usercache entry, no file)");
            out.println("  Total lore items found:       " + items);
            out.println("  Failed/unreadable loads:      " + errors);
            out.println("  Elapsed:                      " + elapsedSec + "s");
            out.println("Finished: " + plugin.getDataManager().format(Instant.now()));
        } catch (IOException ignored) {}

        sender.sendMessage(Component.text("Test complete.", NamedTextColor.GREEN));
        sender.sendMessage(Component.text(
                "Scanned " + total
                        + " | with lore: " + withLore
                        + " | zero lore: " + zeroLore
                        + " | missing .dat: " + missing
                        + " | read errors: " + errors
                        + " | items: " + items,
                NamedTextColor.WHITE));
        sender.sendMessage(Component.text("Report: plugins/LoreCleaner/logs/" + testLog.getName(), NamedTextColor.GRAY));
        plugin.getLogger().info("Test scan finished → " + testLog.getName());
    }

    private int censusDatFiles() {
        int datOnDisk = 0;
        String[] subdirs = {"players/data", "playerdata"};
        java.util.LinkedHashSet<File> countedDirs = new java.util.LinkedHashSet<>();
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            for (String sub : subdirs) {
                countedDirs.add(new File(w.getWorldFolder(), sub));
            }
        }
        File container = Bukkit.getWorldContainer();
        File[] containerChildren = container.listFiles();
        if (containerChildren != null) {
            for (File worldFolder : containerChildren) {
                if (!worldFolder.isDirectory()) continue;
                for (String sub : subdirs) {
                    countedDirs.add(new File(worldFolder, sub));
                }
            }
        }
        StringBuilder pathReport = new StringBuilder();
        for (File dir : countedDirs) {
            File[] files = dir.isDirectory()
                    ? dir.listFiles((d, n) -> n.endsWith(".dat") && !n.endsWith(".dat_old"))
                    : null;
            int count = files == null ? 0 : files.length;
            datOnDisk += count;
            if (dir.isDirectory() || count > 0) {
                pathReport.append("  ").append(dir.getAbsolutePath())
                        .append(" exists=").append(dir.isDirectory())
                        .append(" count=").append(count).append('\n');
            }
        }
        if (pathReport.length() > 0) {
            plugin.getLogger().info("playerdata census:\n" + pathReport);
        }
        return datOnDisk;
    }

    private static List<OfflinePlayerCandidate> applyLimit(List<OfflinePlayerCandidate> candidates, int limit) {
        if (limit > 0 && candidates.size() > limit) {
            return new ArrayList<>(candidates.subList(0, limit));
        }
        return candidates;
    }

    private void appendLine(File log, String block) {
        try (PrintWriter out = new PrintWriter(new FileWriter(log, true))) {
            out.print(block);
            if (!block.endsWith("\n")) out.println();
            out.println();
        } catch (IOException e) {
            plugin.getLogger().warning("Could not write to scan log: " + e.getMessage());
        }
    }
}
