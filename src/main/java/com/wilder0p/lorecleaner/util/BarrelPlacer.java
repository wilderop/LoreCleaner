package com.wilder0p.lorecleaner.util;

import com.wilder0p.lorecleaner.LoreCleanerPlugin;
import com.wilder0p.lorecleaner.manager.ConfigManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds a safe air block near logout and places barrel(s) + wall signs.
 */
public class BarrelPlacer {

    private final LoreCleanerPlugin plugin;

    // Lazily-detected protection plugins (M7).
    private boolean wgChecked = false;
    private boolean wgPresent = false;
    private boolean gpChecked = false;
    private boolean gpPresent = false;

    public BarrelPlacer(LoreCleanerPlugin plugin) {
        this.plugin = plugin;
    }

    public Location findSafeBarrelLocation(Location origin) {
        World world = origin.getWorld();
        if (world == null) return null;

        // M6: the origin chunk was pre-loaded async by the caller; never load it here.
        if (world.isChunkLoaded(origin.getBlockX() >> 4, origin.getBlockZ() >> 4)
                && isAllowedPlacement(origin.getBlock())) {
            return origin.getBlock().getLocation();
        }

        int maxRadius = 8;
        for (int r = 1; r <= maxRadius; r++) {
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    if (Math.abs(x) != r && Math.abs(z) != r) continue;
                    for (int y = -2; y <= 2; y++) {
                        int bx = origin.getBlockX() + x;
                        int bz = origin.getBlockZ() + z;
                        // M6: never synchronously load or generate chunks on the main thread.
                        if (!world.isChunkLoaded(bx >> 4, bz >> 4)) continue;
                        Block b = world.getBlockAt(bx, origin.getBlockY() + y, bz);
                        if (isAllowedPlacement(b)) {
                            return b.getLocation();
                        }
                    }
                }
            }
        }
        return null;
    }

    public boolean isValidPlacement(Block block) {
        if (block.getType() != Material.AIR) return false;
        return block.getWorld().getWorldBorder().isInside(block.getLocation());
    }

    /** Air + inside border + (M7) not inside a protection region we cannot verify. */
    private boolean isAllowedPlacement(Block block) {
        return isValidPlacement(block) && !isProtected(block);
    }

    /**
     * M7: detect WorldGuard regions / GriefPrevention claims via reflection so we
     * never modify another player's protected land. Fail closed when the check
     * itself errors while a protection plugin is present.
     */
    private boolean isProtected(Block block) {
        if (!plugin.getConfigManager().isSkipProtectedRegions()) return false;
        try {
            if (!wgChecked) {
                wgPresent = classExists("com.sk89q.worldguard.WorldGuard");
                wgChecked = true;
            }
            if (wgPresent && inWorldGuardRegion(block)) {
                plugin.getLogger().fine("Skipping WorldGuard-protected block at " + block.getLocation());
                return true;
            }
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "WorldGuard region check failed; treating location as protected: " + e.getMessage());
            return true;
        }
        try {
            if (!gpChecked) {
                gpPresent = classExists("me.ryanhamshire.GriefPrevention.GriefPrevention");
                gpChecked = true;
            }
            if (gpPresent && inGriefPreventionClaim(block)) {
                plugin.getLogger().fine("Skipping GriefPrevention-protected block at " + block.getLocation());
                return true;
            }
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "GriefPrevention claim check failed; treating location as protected: " + e.getMessage());
            return true;
        }
        return false;
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private boolean inWorldGuardRegion(Block block) throws Exception {
        Class<?> wg = Class.forName("com.sk89q.worldguard.WorldGuard");
        Object inst = wg.getMethod("getInstance").invoke(null);
        Object platform = wg.getMethod("getPlatform").invoke(inst);
        Object container = platform.getClass().getMethod("getRegionContainer").invoke(platform);
        Class<?> adapter = Class.forName("com.sk89q.worldguard.bukkit.BukkitAdapter");
        Object weWorld = adapter.getMethod("adapt", World.class).invoke(null, block.getWorld());
        Object manager = container.getClass()
                .getMethod("get", Class.forName("com.sk89q.worldedit.world.World"))
                .invoke(container, weWorld);
        if (manager == null) return false;
        Class<?> bv3 = Class.forName("com.sk89q.worldedit.math.BlockVector3");
        Object vec = bv3.getMethod("at", int.class, int.class, int.class)
                .invoke(null, block.getX(), block.getY(), block.getZ());
        Object regions = manager.getClass().getMethod("getApplicableRegions", bv3).invoke(manager, vec);
        return ((Number) regions.getClass().getMethod("size").invoke(regions)).intValue() > 0;
    }

    private boolean inGriefPreventionClaim(Block block) throws Exception {
        Class<?> gp = Class.forName("me.ryanhamshire.GriefPrevention.GriefPrevention");
        Object inst = gp.getField("instance").get(null);
        if (inst == null) return false;
        Object store = gp.getField("dataStore").get(inst);
        Class<?> claimClass = Class.forName("me.ryanhamshire.GriefPrevention.Claim");
        Object claim = store.getClass()
                .getMethod("getClaimAt", Location.class, boolean.class, claimClass)
                .invoke(store, block.getLocation(), false, null);
        return claim != null;
    }

    /** Result of a barrel placement run. {@link #complete} is false when air ran out. */
    public static final class PlacementResult {
        public final int barrelsPlaced;
        public final int itemsPlaced;
        public final boolean complete;
        final List<Block> placedBlocks;

        PlacementResult(int barrelsPlaced, int itemsPlaced, boolean complete, List<Block> placedBlocks) {
            this.barrelsPlaced = barrelsPlaced;
            this.itemsPlaced = itemsPlaced;
            this.complete = complete;
            this.placedBlocks = placedBlocks;
        }
    }

    /**
     * Remove every barrel/sign this placement run created. Only reverts blocks that
     * still hold what we placed (never touches blocks another process changed).
     */
    public void rollbackPlacement(PlacementResult result) {
        if (result == null || result.placedBlocks == null) return;
        for (Block b : result.placedBlocks) {
            try {
                Material t = b.getType();
                if (t == Material.BARREL || t == Material.OAK_WALL_SIGN) {
                    b.setType(Material.AIR);
                }
            } catch (Exception ignored) {}
        }
    }

    /**
     * @return placement result; check {@link PlacementResult#complete} — when false,
     * some items were NOT placed and the caller must abort before saving playerdata.
     * @throws RuntimeException if placement itself throws — any partial placement is
     * rolled back before the exception propagates.
     */
    public PlacementResult placeBarrelsWithItems(Location start, List<ItemStack> items, String playerName) {
        List<Block> placed = new ArrayList<>();
        try {
            return placeBarrelsWithItemsInner(start, items, playerName, placed);
        } catch (Exception e) {
            // Roll back whatever was placed before the failure, then propagate.
            rollbackPlacement(new PlacementResult(0, 0, false, placed));
            throw new RuntimeException("barrel placement failed", e);
        }
    }

    private PlacementResult placeBarrelsWithItemsInner(Location start, List<ItemStack> items,
                                                      String playerName, List<Block> placed) {
        int barrels = 0;
        int index = 0;
        boolean complete = true;
        Location current = start.clone();

        int[][] offsets = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}};

        while (index < items.size()) {
            Block block = current.getBlock();
            if (!isAllowedPlacement(block)) {
                Location found = null;
                outer:
                for (int r = 1; r <= 4; r++) {
                    for (int[] off : offsets) {
                        Location tryLoc = current.clone().add(off[0] * r, off[1] * r, off[2] * r);
                        if (isAllowedPlacement(tryLoc.getBlock())) {
                            found = tryLoc;
                            break outer;
                        }
                    }
                }
                if (found == null) {
                    // N1: do NOT silently drop the remaining items — report incomplete
                    // so the caller aborts before the .dat save.
                    plugin.getLogger().severe(
                            "Ran out of free air blocks while placing barrels for " + playerName
                                    + " — " + (items.size() - index) + " item(s) NOT placed");
                    complete = false;
                    break;
                }
                current = found;
                block = current.getBlock();
            }

            block.setType(Material.BARREL);
            placed.add(block);

            org.bukkit.block.Barrel barrel = (org.bukkit.block.Barrel) block.getState();
            org.bukkit.inventory.Inventory inv = barrel.getInventory();

            int slotsFilled = 0;
            while (index < items.size() && slotsFilled < 27) {
                inv.setItem(slotsFilled, items.get(index));
                index++;
                slotsFilled++;
            }
            barrel.update(true, false);

            Block signBlock = placeSignOnBarrel(block, playerName);
            if (signBlock != null) placed.add(signBlock);

            barrels++;
            current = block.getLocation().add(1, 0, 0);
        }
        return new PlacementResult(barrels, index, complete, placed);
    }

    /** @return the sign block placed, or null if no face was available. */
    private Block placeSignOnBarrel(Block barrelBlock, String playerName) {
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
            return signBlock;
        }
        return null;
    }
}
