package com.wilder0p.lorecleaner.manager;

import com.wilder0p.lorecleaner.LoreCleanerPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class DataManager {

    private final LoreCleanerPlugin plugin;
    private final File dataFile;
    private FileConfiguration data;

    private Instant firstEnabled;
    private Instant lastFullRunCompleted;
    private final Map<UUID, Instant> lastCleaned = new HashMap<>();
    private final Map<UUID, Boolean> pendingLoginMessage = new HashMap<>();

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    public DataManager(LoreCleanerPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
        load();
    }

    public void load() {
        if (!dataFile.exists()) {
            firstEnabled = Instant.now();
            lastFullRunCompleted = null;
            save();
            return;
        }

        data = YamlConfiguration.loadConfiguration(dataFile);

        String first = data.getString("first-enabled");
        if (first != null) {
            firstEnabled = Instant.parse(first);
        } else {
            firstEnabled = Instant.now();
        }

        String lastRun = data.getString("last-full-run-completed");
        if (lastRun != null && !lastRun.isBlank()) {
            lastFullRunCompleted = Instant.parse(lastRun);
        }

        if (data.isConfigurationSection("cleaned")) {
            for (String key : data.getConfigurationSection("cleaned").getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    Instant when = Instant.parse(data.getString("cleaned." + key));
                    lastCleaned.put(uuid, when);
                } catch (Exception e) {
                    plugin.getLogger().warning("Invalid cleaned entry: " + key);
                }
            }
        }

        if (data.isConfigurationSection("pending-login-message")) {
            for (String key : data.getConfigurationSection("pending-login-message").getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    if (data.getBoolean("pending-login-message." + key)) {
                        pendingLoginMessage.put(uuid, true);
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    public void save() {
        data = new YamlConfiguration();
        data.set("first-enabled", firstEnabled.toString());
        data.set("last-full-run-completed", lastFullRunCompleted != null ? lastFullRunCompleted.toString() : null);

        for (Map.Entry<UUID, Instant> entry : lastCleaned.entrySet()) {
            data.set("cleaned." + entry.getKey().toString(), entry.getValue().toString());
        }

        for (Map.Entry<UUID, Boolean> entry : pendingLoginMessage.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue())) {
                data.set("pending-login-message." + entry.getKey().toString(), true);
            }
        }

        try {
            data.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save data.yml", e);
        }
    }

    public Instant getFirstEnabled() {
        return firstEnabled;
    }

    public Instant getGraceEndTime() {
        return firstEnabled.plusSeconds(plugin.getConfigManager().getGracePeriodDays() * 86400L);
    }

    public boolean isInGracePeriod() {
        return Instant.now().isBefore(getGraceEndTime());
    }

    public Instant getLastFullRunCompleted() {
        return lastFullRunCompleted;
    }

    public void setLastFullRunCompleted(Instant time) {
        this.lastFullRunCompleted = time;
        save();
    }

    public Instant getLastCleaned(UUID uuid) {
        return lastCleaned.get(uuid);
    }

    public void markCleaned(UUID uuid) {
        lastCleaned.put(uuid, Instant.now());
        pendingLoginMessage.put(uuid, true);
        save();
    }

    public boolean hasPendingLoginMessage(UUID uuid) {
        return Boolean.TRUE.equals(pendingLoginMessage.get(uuid));
    }

    public void clearPendingLoginMessage(UUID uuid) {
        pendingLoginMessage.remove(uuid);
        save();
    }

    public String format(Instant instant) {
        return FORMATTER.format(instant);
    }
}
