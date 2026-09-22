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
     * Strip lore from DB inventories. Returns extracted stacks (may be empty if DB had none extra).
     * Does not place barrels — caller already has items from .dat.
     */
    public void stripLore(UUID uuid) {
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
                for (String field : List.of("inventoryContents", "enderChestContents")) {
                    if (!root.has(field) || root.get(field).isJsonNull()) {
                        continue;
                    }
                    String payload = root.get(field).getAsString();
                    String next = stripPayload(payload);
                    if (next != null && !next.equals(payload)) {
                        root.addProperty(field, next);
                        changed = true;
                    }
                }
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
