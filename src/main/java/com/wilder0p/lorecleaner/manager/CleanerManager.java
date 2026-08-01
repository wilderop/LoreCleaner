package com.wilder0p.lorecleaner.manager;

import com.wilder0p.lorecleaner.LoreCleanerPlugin;
import com.wilder0p.lorecleaner.util.OfflinePlayerData;
import com.wilder0p.lorecleaner.util.DiscordWebhook;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

public class CleanerManager {

    private final LoreCleanerPlugin plugin;
    private final Queue<UUID> processQueue = new ConcurrentLinkedQueue<>();
    private BukkitTask scanTask;
    private BukkitTask processTask;
    private boolean forceRun = false;
    private boolean currentlyProcessing = false;
    private int processedThisCycle = 0;

    private final File cleanLogFile;
    private final File failedLogFile;

    public CleanerManager(LoreCleanerPlugin plugin) {
        this.plugin = plugin;
        File logDir = new File(plugin.getDataFolder(), "logs");
        if (!logDir.exists()) logDir.mkdirs();
        this.cleanLogFile = new File(logDir, "cleaned.log");
        this.failedLogFile = new File(logDir, "failed-loads.log");
    }

    public void start() {
        // Main decision loop every 30 seconds
        scanTask = Bukkit.getScheduler().runTaskTimer(plugin, this::decisionTick, 100L, 600L);
    }

    public void shutdown() {
        if (scanTask != null) scanTask.cancel();
        if (processTask != null) processTask.cancel();
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

        // Build or rebuild queue if empty
        if (processQueue.isEmpty()) {
            buildQueue();
            if (processQueue.isEmpty()) {
                // Nothing to do
                if (forceRun) {
                    forceRun = false;
                    plugin.getLogger().info("Force run finished — no eligible players found.");
                }
                return;
            }
            plugin.getLogger().info("Built processing queue with " + processQueue.size() + " eligible offline players (oldest first).");
        }

        // Start processing loop
        currentlyProcessing = true;
        processedThisCycle = 0;
        int delayTicks = Math.max(1, 1200 / Math.max(1, cfg.getPlayersPerMinute())); // ticks between players

        processTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!forceRun && !plugin.getTpsMonitor().isStable()) {
                // TPS dropped — pause
                return;
            }

            UUID next = processQueue.poll();
            if (next == null) {
                // Queue empty — full run complete
                processTask.cancel();
                currentlyProcessing = false;
                forceRun = false;
                data.setLastFullRunCompleted(Instant.now());
                plugin.getLogger().info("Full cleaning cycle completed. Next automatic run in "
                        + cfg.getCooldownAfterFullRunHours() + " hours.");
                return;
            }

            // Skip if player is now online
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
            if (lastPlayed <= 0) continue; // never played or invalid

            if (now - lastPlayed < inactiveMs) continue;

            Instant lastCleaned = data.getLastCleaned(offline.getUniqueId());
            if (lastCleaned != null) {
                long sinceCleaned = now - lastCleaned.toEpochMilli();
                if (sinceCleaned < recheckMs) continue;
            }

            candidates.add(new OfflinePlayerCandidate(offline.getUniqueId(), lastPlayed));
        }

        // Oldest first
        candidates.sort(Comparator.comparingLong(c -> c.lastPlayed));

        for (OfflinePlayerCandidate c : candidates) {
            processQueue.add(c.uuid);
        }
    }

    private void processPlayer(UUID uuid) {
        // Abort immediately if the player came online between queue poll and now
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

        // ---- Phase 1: scan only (no mutation of NBT) ----
        List<ItemStack> scanned = data.scanLoreItems();

        if (scanned.isEmpty()) {
            // Only mark cleaned if conversion fully succeeded (no unreadable items left behind)
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

        // ---- Phase 2: player still offline? ----
        if (Bukkit.getPlayer(uuid) != null) {
            return; // they logged in; leave .dat alone
        }

        // ---- Phase 3: mutate NBT + place barrels + atomic save ----
        List<ItemStack> loreItems = data.extractAndRemoveLoreItems();
        if (loreItems.isEmpty()) {
            // Race or conversion flake — nothing to place
            return;
        }

        int barrelsPlaced = placeBarrelsWithItems(placeLoc, loreItems, name);

        // Final online check before writing the .dat
        if (Bukkit.getPlayer(uuid) != null) {
            // Player logged in mid-process. Barrels are already in the world (duplication risk
            // is better than deleting items from a live session). Do NOT save the mutated NBT.
            logFailed(uuid, "Player logged in during processing — barrels placed but playerdata NOT modified");
            return;
        }

        try {
            data.save(); // atomic write + .bak
        } catch (Exception e) {
            // Barrels exist in world; playerdata still has the items → duplication, not loss
            logFailed(uuid, "playerdata save failed after barrel placement: " + e.getMessage());
            return;
        }

        // Only mark cleaned when we successfully committed
        if (!data.hadConversionFailures()) {
            plugin.getDataManager().markCleaned(uuid);
        } else {
            // Some items could not be converted and were left in the .dat.
            // Do not mark cleaned so a future run (after a plugin fix) can retry.
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

        // Check origin first
        if (isValidPlacement(origin.getBlock())) {
            return origin.getBlock().getLocation();
        }

        // Spiral search for air
        int maxRadius = 8;
        for (int r = 1; r <= maxRadius; r++) {
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    if (Math.abs(x) != r && Math.abs(z) != r) continue; // only perimeter
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
        // Prefer true air; never replace solid blocks
        if (block.getType() != Material.AIR) return false;
        return block.getWorld().getWorldBorder().isInside(block.getLocation());
    }

    private int placeBarrelsWithItems(Location start, List<ItemStack> items, String playerName) {
        int barrels = 0;
        int index = 0;
        Location current = start.clone();

        // Offsets to try when the next spot is blocked (east, west, south, north, up)
        int[][] offsets = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}};

        while (index < items.size()) {
            Block block = current.getBlock();
            if (!isValidPlacement(block)) {
                // Search nearby for another free air block
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
            // Prefer next spot one block east of this barrel
            current = block.getLocation().add(1, 0, 0);
        }
        return barrels;
    }

    private void placeSignOnBarrel(Block barrelBlock, String playerName) {
        ConfigManager cfg = plugin.getConfigManager();
        String date = plugin.getDataManager().format(Instant.now());

        // Prefer south face, then others
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

    private static class OfflinePlayerCandidate {
        final UUID uuid;
        final long lastPlayed;
        OfflinePlayerCandidate(UUID uuid, long lastPlayed) {
            this.uuid = uuid;
            this.lastPlayed = lastPlayed;
        }
    }
}
