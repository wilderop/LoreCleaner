package com.wilder0p.lorecleaner.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Level;

/** Strip lore from PlayerDataSync MariaDB inventory + ender chest (v2 slot payload). */
public final class PdsStore {
    private final JavaPlugin plugin;
    private String jdbcUrl;
    private String user;
    private String password;
    private boolean ready;

    public PdsStore(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        File cfgFile = new File(plugin.getDataFolder().getParentFile(), "PlayerDataSyncReloaded/config.yml");
        if (!cfgFile.isFile()) {
            plugin.getLogger().warning("PDS config not found at " + cfgFile + " — will not strip MariaDB");
            return;
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(cfgFile);
        String host = cfg.getString("storage.host", "127.0.0.1");
        int port = cfg.getInt("storage.port", 3306);
        String db = cfg.getString("storage.database", "playerdatasync");
        user = cfg.getString("storage.username", "");
        password = cfg.getString("storage.password", "");
        jdbcUrl = "jdbc:mariadb://" + host + ":" + port + "/" + db + "?useUnicode=true&characterEncoding=utf8";
        try {
            Class.forName("org.mariadb.jdbc.Driver");
            try (Connection c = connect()) {
                c.createStatement().execute("SELECT 1");
            }
            ready = true;
            plugin.getLogger().info("LoreCleaner PDS MariaDB ready");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "LoreCleaner could not open PDS MariaDB: " + e.getMessage());
            ready = false;
        }
    }

    public boolean ready() {
        return ready;
    }

    /**
     * Strip lore from DB inventories.
     *
     * N13: this method is VOID — it does not return extracted stacks. The extracted
     * items come from the offline .dat pass (see {@link OfflinePlayerData}); this
     * store only strips DB-side lore.
     *
     * N9: each v2 section is verified against {@code expectedSignatures} (the
     * pre-clean local snapshot from {@link OfflinePlayerData#canonicalSlotSignatures})
     * before stripping. A mismatch means the DB row belongs to a different session —
     * the section is skipped and logged LOUDLY, never stripped.
     */
    public void stripLore(UUID uuid, Map<String, List<String>> expectedSignatures) {
        if (!ready || uuid == null) {
            return;
        }
        try (Connection c = connect();
             PreparedStatement sel = c.prepareStatement("SELECT data FROM player_data WHERE uuid = ?")) {
            sel.setString(1, uuid.toString());
            try (ResultSet rs = sel.executeQuery()) {
                if (!rs.next()) {
                    return;
                }
                String raw = rs.getString("data");
                if (raw == null || raw.isBlank()) {
                    return;
                }
                JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
                boolean changed = false;
                changed |= stripSectionIfVerified(uuid, root, "inventoryContents", "inv", expectedSignatures);
                changed |= stripSectionIfVerified(uuid, root, "enderChestContents", "ec", expectedSignatures);
                if (!changed) {
                    return;
                }
                try (PreparedStatement upd = c.prepareStatement(
                        "UPDATE player_data SET data = ?, last_updated = CURRENT_TIMESTAMP WHERE uuid = ?")) {
                    upd.setString(1, root.toString());
                    upd.setString(2, uuid.toString());
                    upd.executeUpdate();
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "PDS lore strip failed for " + uuid + ": " + e.getMessage());
        }
    }

    /**
     * N9: verify one DB section against the pre-clean local snapshot, then strip it.
     * Returns true when the section was modified. Any verification problem skips the
     * section loudly and never strips it.
     */
    private boolean stripSectionIfVerified(UUID uuid, JsonObject root, String field, String section,
                                           Map<String, List<String>> expectedSignatures) {
        if (!root.has(field) || root.get(field).isJsonNull()) {
            return false;
        }
        String payload = root.get(field).getAsString();
        if (SlotDataFormat.kind(payload) != SlotDataFormat.Kind.V2) {
            plugin.getLogger().warning("PDS inventory not v2 (" + SlotDataFormat.kind(payload)
                    + "); leaving DB " + field + " unchanged for " + uuid);
            return false;
        }
        List<String> expected = expectedSignatures != null
                ? expectedSignatures.getOrDefault(section, List.of()) : List.of();
        List<String> actual = new ArrayList<>();
        for (Map.Entry<Integer, byte[]> e : SlotDataFormat.decodeV2(payload).entrySet()) {
            try {
                actual.add(Base64.getEncoder().encodeToString(
                        ItemStack.deserializeBytes(e.getValue()).serializeAsBytes()));
            } catch (Exception ex) {
                plugin.getLogger().severe("PDS STRIP SKIPPED for " + uuid + " — DB " + field
                        + " slot " + e.getKey() + " is not deserializable; NOT stripping (fail closed)");
                return false;
            }
        }
        if (!multisetEquals(expected, actual)) {
            plugin.getLogger().severe("PDS STRIP SKIPPED for " + uuid + " — DB " + field
                    + " does not match cleaned local state (db items=" + actual.size()
                    + ", local items=" + expected.size()
                    + "). The DB may belong to a different session; NOT stripping.");
            return false;
        }
        String next = stripPayload(payload);
        if (next != null && !next.equals(payload)) {
            root.addProperty(field, next);
            return true;
        }
        return false;
    }

    private static boolean multisetEquals(List<String> a, List<String> b) {
        if (a.size() != b.size()) return false;
        Map<String, Integer> counts = new HashMap<>();
        for (String s : a) counts.merge(s, 1, Integer::sum);
        for (String s : b) {
            Integer n = counts.get(s);
            if (n == null || n == 0) return false;
            counts.put(s, n - 1);
        }
        return true;
    }

    private String stripPayload(String payload) {
        SlotDataFormat.Kind kind = SlotDataFormat.kind(payload);
        if (kind != SlotDataFormat.Kind.V2) {
            plugin.getLogger().warning("PDS inventory not v2 (" + kind + "); leaving DB row unchanged");
            return payload;
        }
        Map<Integer, byte[]> slots = SlotDataFormat.decodeV2(payload);
        Map<Integer, byte[]> next = new LinkedHashMap<>();
        boolean changed = false;
        for (Map.Entry<Integer, byte[]> e : slots.entrySet()) {
            ItemStack stack;
            try {
                stack = ItemStack.deserializeBytes(e.getValue());
            } catch (Exception ex) {
                next.put(e.getKey(), e.getValue());
                continue;
            }
            LoreExtractor.Result r = LoreExtractor.extract(stack);
            if (r.extracted.isEmpty()) {
                next.put(e.getKey(), e.getValue());
                continue;
            }
            changed = true;
            if (r.remaining != null && !r.remaining.getType().isAir()) {
                next.put(e.getKey(), r.remaining.serializeAsBytes());
            }
        }
        return changed ? SlotDataFormat.encode(next) : payload;
    }

    private Connection connect() throws Exception {
        Properties props = new Properties();
        props.setProperty("user", user == null ? "" : user);
        props.setProperty("password", password == null ? "" : password);
        return DriverManager.getConnection(jdbcUrl, props);
    }
}
