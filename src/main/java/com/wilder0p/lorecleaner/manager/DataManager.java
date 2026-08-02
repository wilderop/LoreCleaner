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

    /**
     * UUID → OfflinePlayer.getLastPlayed() value at the time we last successfully
     * scanned their .dat. If current lastPlayed still equals this, the file has not
     * changed (player has not logged in), so we can skip re-reading it.
     */
    private final Map<UUID, Long> scannedLastPlayed = new HashMap<>();

    private boolean dirty = false;
    private int unsavedMarks = 0;

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

        if (data.isConfigurationSection("scanned-last-played")) {
            for (String key : data.getConfigurationSection("scanned-last-played").getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    long lp = data.getLong("scanned-last-played." + key);
                    scannedLastPlayed.put(uuid, lp);
                } catch (Exception e) {
                    plugin.getLogger().warning("Invalid scanned-last-played entry: " + key);
                }
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

        for (Map.Entry<UUID, Long> entry : scannedLastPlayed.entrySet()) {
            data.set("scanned-last-played." + entry.getKey().toString(), entry.getValue());
        }

        try {
            data.save(dataFile);
            dirty = false;
            unsavedMarks = 0;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save data.yml", e);
        }
    }

    /** Persist if there are pending in-memory changes. */
    public void saveIfDirty() {
        if (dirty) save();
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
        dirty = true;
        save();
    }

    public Instant getLastCleaned(UUID uuid) {
        return lastCleaned.get(uuid);
    }

    /**
     * True if we already scanned this player while their lastPlayed was this value.
     * Means they have not logged in since, so the .dat is unchanged — safe to skip.
     */
    public boolean wasScannedAtLastPlayed(UUID uuid, long currentLastPlayed) {
        Long stored = scannedLastPlayed.get(uuid);
        return stored != null && stored == currentLastPlayed;
    }

    /**
     * Record that we successfully read this player's .dat while lastPlayed was this value.
     * Batches disk writes (flushes every 50 marks).
     */
    public void markScanned(UUID uuid, long lastPlayed) {
        scannedLastPlayed.put(uuid, lastPlayed);
        dirty = true;
        unsavedMarks++;
        if (unsavedMarks >= 50) {
            save();
        }
    }

    public void markCleaned(UUID uuid) {
        lastCleaned.put(uuid, Instant.now());
        pendingLoginMessage.put(uuid, true);
        dirty = true;
        unsavedMarks++;
        if (unsavedMarks >= 50) {
            save();
        }
    }

    /**
     * After a successful clean (items moved), record both cleaned time and the
     * lastPlayed we scanned under so we do not immediately re-open the same file.
     */
    public void markCleanedAndScanned(UUID uuid, long lastPlayed) {
        lastCleaned.put(uuid, Instant.now());
        pendingLoginMessage.put(uuid, true);
        scannedLastPlayed.put(uuid, lastPlayed);
        dirty = true;
        unsavedMarks++;
        if (unsavedMarks >= 50) {
            save();
        }
    }

    public boolean hasPendingLoginMessage(UUID uuid) {
        return Boolean.TRUE.equals(pendingLoginMessage.get(uuid));
    }

    public void clearPendingLoginMessage(UUID uuid) {
        pendingLoginMessage.remove(uuid);
        dirty = true;
        save();
    }

    public int getScannedSnapshotCount() {
        return scannedLastPlayed.size();
    }

    public String format(Instant instant) {
        return FORMATTER.format(instant);
    }
}
