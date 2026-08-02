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
import java.util.List;

/**
 * Finds a safe air block near logout and places barrel(s) + wall signs.
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
     * @return number of barrels placed
     */
    public int placeBarrelsWithItems(Location start, List<ItemStack> items, String playerName) {
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
                    plugin.getLogger().warning(
                            "Ran out of free air blocks while placing barrels for remaining items");
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
}
