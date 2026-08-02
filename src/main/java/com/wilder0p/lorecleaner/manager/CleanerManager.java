package com.wilder0p.lorecleaner.manager;

import com.wilder0p.lorecleaner.LoreCleanerPlugin;
import com.wilder0p.lorecleaner.util.OfflinePlayerData;
import com.wilder0p.lorecleaner.util.DiscordWebhook;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

public class CleanerManager {

    private final LoreCleanerPlugin plugin;
    private final Queue<UUID> processQueue = new ConcurrentLinkedQueue<>();
    private BukkitTask scanTask;
    private BukkitTask processTask;
    private BukkitTask testTask;
    private boolean forceRun = false;
    private boolean currentlyProcessing = false;
    private boolean testRunning = false;
    private int processedThisCycle = 0;

    private final File logDir;
    private final File cleanLogFile;
    private final File failedLogFile;

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
            .withZone(ZoneId.systemDefault());
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    public CleanerManager(LoreCleanerPlugin plugin) {
        this.plugin = plugin;
        this.logDir = new File(plugin.getDataFolder(), "logs");
        if (!logDir.exists()) logDir.mkdirs();
        this.cleanLogFile = new File(logDir, "cleaned.log");
        this.failedLogFile = new File(logDir, "failed-loads.log");
    }

    public void start() {
        scanTask = Bukkit.getScheduler().runTaskTimer(plugin, this::decisionTick, 100L, 600L);
    }

    public void shutdown() {
        if (scanTask != null) scanTask.cancel();
        if (processTask != null) processTask.cancel();
        if (testTask != null) testTask.cancel();
        testRunning = false;
    }

    public boolean isTestRunning() {
        return testRunning;
    }

    public void forceRun() {
        this.forceRun = true;
        plugin.getLogger().info("Force run requested. Will start as soon as TPS conditions allow (or immediately if already stable).");
        decisionTick();
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
            plugin.getLogger().info("Built processing queue with " + processQueue.size() + " eligible offline players (oldest first).");
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
                plugin.getLogger().info("Full cleaning cycle completed. Next automatic run in "
                        + cfg.getCooldownAfterFullRunHours() + " hours.");
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

        for (org.bukkit.OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
            if (offline.getUniqueId() == null) continue;
            if (offline.isOnline()) continue;

            long lastPlayed = offline.getLastPlayed();
            if (lastPlayed <= 0) continue;

            if (now - lastPlayed < inactiveMs) continue;

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

        org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        String name = offline.getName() != null ? offline.getName() : uuid.toString();

        OfflinePlayerData data = OfflinePlayerData.load(plugin, uuid);
        if (data == null) {
            logFailed(uuid, "Could not load playerdata .dat (corrupted or missing)");
            return;
        }

        List<ItemStack> scanned = data.scanLoreItems();

        if (scanned.isEmpty()) {
            if (!data.hadConversionFailures()) {
                plugin.getDataManager().markCleaned(uuid);
            } else {
                logFailed(uuid, "Had unreadable items; not marking cleaned so they can be retried later");
            }
            return;
        }

        Location logoutLoc = data.getLogoutLocation();
        if (logoutLoc == null || logoutLoc.getWorld() == null) {
            logFailed(uuid, "No valid logout location in playerdata — items left untouched");
            return;
        }

        Location placeLoc = findSafeBarrelLocation(logoutLoc);
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

        int barrelsPlaced = placeBarrelsWithItems(placeLoc, loreItems, name);

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
            plugin.getDataManager().markCleaned(uuid);
        } else {
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

    private Location findSafeBarrelLocation(Location origin) {
        World world = origin.getWorld();
        if (world == null) return null;

        if (isValidPlacement(origin.getBlock())) {
            return origin.getBlock().getLocation();
        }

        int maxRadius = 8;
        for (int r = 1; r <= maxRadius; r++) {
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    if (Math.abs(x) != r && Math.abs(z) != r) continue;
                    for (int y = -2; y <= 2; y++) {
                        Block b = world.getBlockAt(origin.getBlockX() + x, origin.getBlockY() + y, origin.getBlockZ() + z);
                        if (isValidPlacement(b)) {
                            return b.getLocation();
                        }
                    }
                }
            }
        }
        return null;
    }

    private boolean isValidPlacement(Block block) {
        if (block.getType() != Material.AIR) return false;
        return block.getWorld().getWorldBorder().isInside(block.getLocation());
    }

    private int placeBarrelsWithItems(Location start, List<ItemStack> items, String playerName) {
        int barrels = 0;
        int index = 0;
        Location current = start.clone();

        int[][] offsets = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}};

        while (index < items.size()) {
            Block block = current.getBlock();
            if (!isValidPlacement(block)) {
                Location found = null;
                outer:
                for (int r = 1; r <= 4; r++) {
                    for (int[] off : offsets) {
                        Location tryLoc = current.clone().add(off[0] * r, off[1] * r, off[2] * r);
                        if (isValidPlacement(tryLoc.getBlock())) {
                            found = tryLoc;
                            break outer;
                        }
                    }
                }
                if (found == null) {
                    plugin.getLogger().warning("Ran out of free air blocks while placing barrels for remaining items");
                    break;
                }
                current = found;
                block = current.getBlock();
            }

            block.setType(Material.BARREL);

            org.bukkit.block.Barrel barrel = (org.bukkit.block.Barrel) block.getState();
            org.bukkit.inventory.Inventory inv = barrel.getInventory();

            int slotsFilled = 0;
            while (index < items.size() && slotsFilled < 27) {
                inv.setItem(slotsFilled, items.get(index));
                index++;
                slotsFilled++;
            }
            barrel.update(true, false);

            placeSignOnBarrel(block, playerName);

            barrels++;
            current = block.getLocation().add(1, 0, 0);
        }
        return barrels;
    }

    private void placeSignOnBarrel(Block barrelBlock, String playerName) {
        ConfigManager cfg = plugin.getConfigManager();
        String date = plugin.getDataManager().format(Instant.now());

        BlockFace[] faces = {BlockFace.SOUTH, BlockFace.NORTH, BlockFace.EAST, BlockFace.WEST};
        for (BlockFace face : faces) {
            Block signBlock = barrelBlock.getRelative(face);
            if (signBlock.getType() != Material.AIR && !signBlock.isPassable()) continue;

            signBlock.setType(Material.OAK_WALL_SIGN);
            BlockData data = signBlock.getBlockData();
            if (data instanceof WallSign wallSign) {
                wallSign.setFacing(face);
                signBlock.setBlockData(wallSign);
            }

            Sign sign = (Sign) signBlock.getState();
            sign.getSide(org.bukkit.block.sign.Side.FRONT).setLine(0,
                    cfg.getBarrelSignLine1().replace("%player%", playerName).replace("%date%", date));
            sign.getSide(org.bukkit.block.sign.Side.FRONT).setLine(1,
                    cfg.getBarrelSignLine2().replace("%player%", playerName).replace("%date%", date));
            sign.getSide(org.bukkit.block.sign.Side.FRONT).setLine(2,
                    cfg.getBarrelSignLine3().replace("%player%", playerName).replace("%date%", date));
            sign.getSide(org.bukkit.block.sign.Side.FRONT).setLine(3,
                    cfg.getBarrelSignLine4().replace("%player%", playerName).replace("%date%", date));
            sign.update(true, false);
            return;
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

    public void startTestRun(CommandSender sender, int months) {
        if (testRunning) {
            sender.sendMessage(Component.text("A test scan is already running. Wait for it to finish.", NamedTextColor.RED));
            return;
        }
        if (months < 1) {
            sender.sendMessage(Component.text("Months must be a whole number >= 1.", NamedTextColor.RED));
            return;
        }

        long inactiveMs = months * 30L * 86400L * 1000L;
        long now = System.currentTimeMillis();

        List<OfflinePlayerCandidate> candidates = new ArrayList<>();
        for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
            if (offline.getUniqueId() == null) continue;
            if (offline.isOnline()) continue;
            long lastPlayed = offline.getLastPlayed();
            if (lastPlayed <= 0) continue;
            if (now - lastPlayed < inactiveMs) continue;
            candidates.add(new OfflinePlayerCandidate(offline.getUniqueId(), lastPlayed));
        }
        candidates.sort(Comparator.comparingLong(c -> c.lastPlayed));

        if (candidates.isEmpty()) {
            sender.sendMessage(Component.text("No offline players found inactive for " + months + "+ months.", NamedTextColor.YELLOW));
            return;
        }

        String stamp = FILE_TS.format(Instant.now());
        File testLog = new File(logDir, "test-" + stamp + ".log");

        testRunning = true;
        sender.sendMessage(Component.text("Starting dry-run test for " + months + " months inactivity cutoff.", NamedTextColor.GREEN));
        sender.sendMessage(Component.text("Eligible offline players: " + candidates.size() + " — writing to logs/" + testLog.getName(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Progress updates every minute. This does NOT move any items.", NamedTextColor.GRAY));

        final int total = candidates.size();
        final int[] index = {0};
        final int[] playersWithItems = {0};
        final int[] totalLoreItems = {0};
        final int[] failedLoads = {0};
        final long startMs = System.currentTimeMillis();
        final long[] lastProgressMs = {startMs};

        try (PrintWriter header = new PrintWriter(new FileWriter(testLog, false))) {
            header.println("LoreCleaner dry-run test");
            header.println("Started: " + plugin.getDataManager().format(Instant.now()));
            header.println("Inactivity cutoff: " + months + " month(s) (~" + (months * 30) + " days)");
            header.println("Eligible offline players to scan: " + total);
            header.println("NOTE: This is a dry run. No items were moved. No playerdata was modified.");
            header.println("========================================================================");
            header.println();
        } catch (IOException e) {
            sender.sendMessage(Component.text("Could not create test log file: " + e.getMessage(), NamedTextColor.RED));
            testRunning = false;
            return;
        }

        testTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (index[0] >= total) {
                testTask.cancel();
                testTask = null;
                testRunning = false;

                long elapsedSec = Math.max(1, (System.currentTimeMillis() - startMs) / 1000);
                try (PrintWriter out = new PrintWriter(new FileWriter(testLog, true))) {
                    out.println();
                    out.println("========================================================================");
                    out.println("SUMMARY");
                    out.println("  Players scanned:           " + total);
                    out.println("  Players with lore items:   " + playersWithItems[0]);
                    out.println("  Total lore items found:    " + totalLoreItems[0]);
                    out.println("  Failed/unreadable loads:   " + failedLoads[0]);
                    out.println("  Elapsed:                   " + elapsedSec + "s");
                    out.println("Finished: " + plugin.getDataManager().format(Instant.now()));
                } catch (IOException ignored) {}

                sender.sendMessage(Component.text("Test complete.", NamedTextColor.GREEN));
                sender.sendMessage(Component.text(
                        "Scanned " + total + " | with lore: " + playersWithItems[0]
                                + " | items: " + totalLoreItems[0]
                                + " | failed loads: " + failedLoads[0],
                        NamedTextColor.WHITE));
                sender.sendMessage(Component.text("Report: plugins/LoreCleaner/logs/" + testLog.getName(), NamedTextColor.GRAY));
                plugin.getLogger().info("Test scan finished → " + testLog.getName());
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

            OfflinePlayerCandidate c = candidates.get(index[0]++);
            OfflinePlayer offline = Bukkit.getOfflinePlayer(c.uuid);
            if (offline.isOnline()) {
                return;
            }

            String name = offline.getName() != null ? offline.getName() : c.uuid.toString();
            long daysAgo = Math.max(0, (now - c.lastPlayed) / 86400_000L);
            String lastPlayedStr = plugin.getDataManager().format(Instant.ofEpochMilli(c.lastPlayed));

            OfflinePlayerData data = OfflinePlayerData.load(plugin, c.uuid);
            if (data == null) {
                failedLoads[0]++;
                appendTestLine(testLog, String.format(
                        "[%s] %s (%s)%n  Last played: %s (%d days ago)%n  ERROR: could not load playerdata%n",
                        plugin.getDataManager().format(Instant.now()), name, c.uuid, lastPlayedStr, daysAgo));
                return;
            }

            List<ItemStack> loreItems = data.scanLoreItems();
            if (data.hadConversionFailures() && loreItems.isEmpty()) {
                failedLoads[0]++;
            }

            if (loreItems.isEmpty()) {
                appendTestLine(testLog, String.format(
                        "[%s] %s (%s)%n  Last played: %s (%d days ago)%n  Lore items: 0%s%n",
                        plugin.getDataManager().format(Instant.now()), name, c.uuid, lastPlayedStr, daysAgo,
                        data.hadConversionFailures() ? " (had unreadable items)" : ""));
                return;
            }

            playersWithItems[0]++;
            totalLoreItems[0] += loreItems.size();

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("[%s] %s (%s)%n", plugin.getDataManager().format(Instant.now()), name, c.uuid));
            sb.append(String.format("  Last played: %s (%d days ago)%n", lastPlayedStr, daysAgo));
            sb.append(String.format("  Lore items: %d%n", loreItems.size()));
            for (ItemStack stack : loreItems) {
                sb.append("    - ").append(describeItem(stack)).append('\n');
            }
            if (data.hadConversionFailures()) {
                sb.append("  NOTE: some items in this file could not be converted and were skipped\n");
            }
            appendTestLine(testLog, sb.toString());
        }, 1L, 5L);
    }

    private void appendTestLine(File testLog, String block) {
        try (PrintWriter out = new PrintWriter(new FileWriter(testLog, true))) {
            out.print(block);
            if (!block.endsWith("\n")) out.println();
            out.println();
        } catch (IOException e) {
            plugin.getLogger().warning("Could not write to test log: " + e.getMessage());
        }
    }

    private String describeItem(ItemStack stack) {
        String type = stack.getType().name();
        int amount = stack.getAmount();
        String display = null;
        String lorePreview = null;

        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (meta.hasDisplayName()) {
                try {
                    Component nameComp = meta.displayName();
                    if (nameComp != null) {
                        display = PLAIN.serialize(nameComp);
                    }
                } catch (Exception ignored) {}
                if (display == null || display.isBlank()) {
                    try {
                        display = meta.getDisplayName();
                    } catch (Exception ignored) {}
                }
            }
            if (meta.hasLore()) {
                try {
                    List<Component> loreComps = meta.lore();
                    if (loreComps != null && !loreComps.isEmpty()) {
                        lorePreview = PLAIN.serialize(loreComps.get(0));
                    }
                } catch (Exception ignored) {}
                if (lorePreview == null) {
                    try {
                        List<String> legacy = meta.getLore();
                        if (legacy != null && !legacy.isEmpty()) {
                            lorePreview = legacy.get(0);
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        if (lorePreview != null && lorePreview.length() > 60) {
            lorePreview = lorePreview.substring(0, 57) + "...";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(type).append(" x").append(amount);
        if (display != null && !display.isBlank()) {
            sb.append(" \"").append(display).append("\"");
        }
        if (lorePreview != null && !lorePreview.isBlank()) {
            sb.append(" — lore: \"").append(lorePreview).append("\"");
        }
        return sb.toString();
    }

    private static class OfflinePlayerCandidate {
        final UUID uuid;
        final long lastPlayed;
        OfflinePlayerCandidate(UUID uuid, long lastPlayed) {
            this.uuid = uuid;
            this.lastPlayed = lastPlayed;
        }
    }
}
