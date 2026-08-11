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
 * Placement can be verified and fully rolled back (barrels + signs → AIR)
 * if the items in the barrels do not exactly match what was intended.
 */
public class BarrelPlacer {

    private final LoreCleanerPlugin plugin;

    public BarrelPlacer(LoreCleanerPlugin plugin) {
        this.plugin = plugin;
    }

    public Location findSafeBarrelLocation(Location origin) {
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
                        Block b = world.getBlockAt(
                                origin.getBlockX() + x,
                                origin.getBlockY() + y,
                                origin.getBlockZ() + z);
                        if (isValidPlacement(b)) {
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

    /**
     * Places barrels + signs, then reads contents back and verifies an exact match
     * against {@code expected} (same count, same ItemStacks via equals).
     * On mismatch or incomplete placement, all placed blocks are destroyed.
     */
    public PlacementResult placeAndVerify(Location start, List<ItemStack> expected, String playerName) {
        PlacementResult result = placeBarrelsInternal(start, expected, playerName);

        if (!result.placedAllExpected) {
            result.rollbackReason = "Could not place all items (ran out of air blocks); expected "
                    + expected.size() + " items, only placed " + result.itemsPlacedBeforeVerify;
            result.rollback();
            return result;
        }

        List<ItemStack> actual = result.readContentsInOrder();
        if (!exactMatch(expected, actual)) {
            result.rollbackReason = "Barrel contents did not exactly match extracted lore items "
                    + "(expected " + expected.size() + " items, read back " + actual.size() + ")";
            result.rollback();
            return result;
        }

        result.verified = true;
        return result;
    }

    private PlacementResult placeBarrelsInternal(Location start, List<ItemStack> items, String playerName) {
        PlacementResult result = new PlacementResult();
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
                    plugin.getLogger().warning(
                            "Ran out of free air blocks while placing barrels for remaining items");
                    result.placedAllExpected = false;
                    result.itemsPlacedBeforeVerify = index;
                    return result;
                }
                current = found;
                block = current.getBlock();
            }

            block.setType(Material.BARREL);
            result.barrelBlocks.add(block.getLocation().clone());

            org.bukkit.block.Barrel barrel = (org.bukkit.block.Barrel) block.getState();
            org.bukkit.inventory.Inventory inv = barrel.getInventory();

            int slotsFilled = 0;
            while (index < items.size() && slotsFilled < 27) {
                ItemStack clone = items.get(index).clone();
                inv.setItem(slotsFilled, clone);
                index++;
                slotsFilled++;
            }
            barrel.update(true, false);

            Location signLoc = placeSignOnBarrel(block, playerName);
            if (signLoc != null) {
                result.signBlocks.add(signLoc);
            }

            current = block.getLocation().add(1, 0, 0);
        }

        result.placedAllExpected = true;
        result.itemsPlacedBeforeVerify = index;
        return result;
    }

    private Location placeSignOnBarrel(Block barrelBlock, String playerName) {
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
            return signBlock.getLocation().clone();
        }
        return null;
    }

    static boolean exactMatch(List<ItemStack> expected, List<ItemStack> actual) {
        if (expected.size() != actual.size()) return false;
        for (int i = 0; i < expected.size(); i++) {
            ItemStack e = expected.get(i);
            ItemStack a = actual.get(i);
            if (e == null && a == null) continue;
            if (e == null || a == null) return false;
            if (!e.equals(a)) return false;
        }
        return true;
    }

    public static final class PlacementResult {
        public final List<Location> barrelBlocks = new ArrayList<>();
        public final List<Location> signBlocks = new ArrayList<>();
        public boolean placedAllExpected = false;
        public boolean verified = false;
        public int itemsPlacedBeforeVerify = 0;
        public String rollbackReason;

        public int barrelCount() {
            return barrelBlocks.size();
        }

        public List<ItemStack> readContentsInOrder() {
            List<ItemStack> out = new ArrayList<>();
            for (Location loc : barrelBlocks) {
                Block b = loc.getBlock();
                if (b.getType() != Material.BARREL) continue;
                org.bukkit.block.Barrel barrel = (org.bukkit.block.Barrel) b.getState();
                org.bukkit.inventory.Inventory inv = barrel.getInventory();
                for (int slot = 0; slot < inv.getSize(); slot++) {
                    ItemStack stack = inv.getItem(slot);
                    if (stack != null && stack.getType() != Material.AIR) {
                        out.add(stack.clone());
                    }
                }
            }
            return out;
        }

        public void rollback() {
            for (Location loc : signBlocks) {
                Block b = loc.getBlock();
                if (b.getType().name().contains("SIGN") || b.getType().name().contains("WALL_SIGN")) {
                    b.setType(Material.AIR);
                }
            }
            for (Location loc : barrelBlocks) {
                Block b = loc.getBlock();
                if (b.getType() == Material.BARREL) {
                    org.bukkit.block.Barrel barrel = (org.bukkit.block.Barrel) b.getState();
                    barrel.getInventory().clear();
                    barrel.update(true, false);
                    b.setType(Material.AIR);
                }
            }
            verified = false;
        }

        public boolean success() {
            return verified && placedAllExpected;
        }
    }
}
