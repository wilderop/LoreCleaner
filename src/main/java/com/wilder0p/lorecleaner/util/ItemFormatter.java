package com.wilder0p.lorecleaner.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/** Human-readable one-line description of an ItemStack for logs. */
public final class ItemFormatter {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private ItemFormatter() {}

    public static String describe(ItemStack stack) {
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
}
