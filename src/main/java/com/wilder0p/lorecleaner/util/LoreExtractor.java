package com.wilder0p.lorecleaner.util;

import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects lore items. A shulker/bundle that itself has lore is moved whole.
 * Otherwise lore is pulled out of its contents (nested).
 */
public final class LoreExtractor {
    private LoreExtractor() {}

    public static final class Result {
        public final List<ItemStack> extracted = new ArrayList<>();
        /** Remaining stack to leave in the inventory, or null to delete the slot. */
        public ItemStack remaining;
    }

    public static boolean hasLore(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta != null && meta.hasLore();
    }

    public static List<ItemStack> scan(ItemStack stack) {
        Result r = extract(stack.clone());
        return r.extracted;
    }

    public static Result extract(ItemStack stack) {
        Result r = new Result();
        if (stack == null || stack.getType().isAir()) {
            r.remaining = stack;
            return r;
        }
        if (hasLore(stack)) {
            r.extracted.add(stack.clone());
            r.remaining = null;
            return r;
        }
        if (unpackShulker(stack, r) || unpackBundle(stack, r)) {
            return r;
        }
        r.remaining = stack;
        return r;
    }

    private static boolean unpackShulker(ItemStack stack, Result r) {
        ItemMeta meta = stack.getItemMeta();
        if (!(meta instanceof BlockStateMeta bsm) || !(bsm.getBlockState() instanceof ShulkerBox box)) {
            return false;
        }
        boolean changed = false;
        org.bukkit.inventory.Inventory inv = box.getInventory();
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            Result inner = extract(contents[i]);
            if (!inner.extracted.isEmpty()) {
                r.extracted.addAll(inner.extracted);
                contents[i] = inner.remaining;
                changed = true;
            }
        }
        if (changed) {
            inv.setContents(contents);
            bsm.setBlockState(box);
            stack.setItemMeta(bsm);
        }
        r.remaining = stack;
        return true;
    }

    private static boolean unpackBundle(ItemStack stack, Result r) {
        ItemMeta meta = stack.getItemMeta();
        if (!(meta instanceof BundleMeta bundle)) {
            return false;
        }
        List<ItemStack> keep = new ArrayList<>();
        boolean changed = false;
        for (ItemStack inner : bundle.getItems()) {
            Result nested = extract(inner);
            if (!nested.extracted.isEmpty()) {
                r.extracted.addAll(nested.extracted);
                changed = true;
                if (nested.remaining != null && !nested.remaining.getType().isAir()) {
                    keep.add(nested.remaining);
                }
            } else if (inner != null && !inner.getType().isAir()) {
                keep.add(inner);
            }
        }
        if (changed) {
            bundle.setItems(keep);
            stack.setItemMeta(bundle);
        }
        r.remaining = stack;
        return true;
    }
}
