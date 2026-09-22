package com.wilder0p.lorecleaner.manager;

import com.wilder0p.lorecleaner.LoreCleanerPlugin;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

public class ConfigManager {

    private final LoreCleanerPlugin plugin;

    private int inactiveDays;
    private int gracePeriodDays;
    private int cooldownAfterFullRunHours;
    private int tpsStableMinutes;
    private int playersPerMinute;
    private int recheckDays;
    private String discordWebhookUrl;

    private String cleanedOnLoginMessage;
    private String barrelSignLine1;
    private String barrelSignLine2;
    private String barrelSignLine3;
    private String barrelSignLine4;
    private boolean enabled;
    private String paperPlayerDataDir;
    private String fabricPlayerDataDir;
    private long fabricGoLiveEpochMs;
    private String thisSide;
    private boolean skipProtectedRegions;

    private String redisSentinelMaster;
    private List<String> redisSentinels;
    private String redisFallbackHost;
    private int redisFallbackPort;
    private String redisPasswordFile;
    private boolean redisFailClosed;

    public ConfigManager(LoreCleanerPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();

        // m7: clamp unsafe values to sane minima so a bad edit cannot zero out the
        // schedule or turn every player eligible at once.
        inactiveDays = Math.max(1, cfg.getInt("inactive-days", 180));
        gracePeriodDays = Math.max(0, cfg.getInt("grace-period-days", 30));
        cooldownAfterFullRunHours = Math.max(1, cfg.getInt("cooldown-after-full-run-hours", 72));
        tpsStableMinutes = Math.max(1, cfg.getInt("tps-stable-minutes", 5));
        playersPerMinute = Math.max(1, cfg.getInt("players-per-minute", 4));
        recheckDays = Math.max(1, cfg.getInt("recheck-days", 180));
        discordWebhookUrl = cfg.getString("discord-webhook-url", "");

        cleanedOnLoginMessage = cfg.getString("messages.cleaned-on-login",
                "<yellow>While you were offline for more than 6 months, your lore items were moved into a barrel at your last logout location.");
        barrelSignLine1 = cfg.getString("messages.barrel-sign-line1", "LoreCleaner");
        barrelSignLine2 = cfg.getString("messages.barrel-sign-line2", "%player%");
        barrelSignLine3 = cfg.getString("messages.barrel-sign-line3", "%date%");
        barrelSignLine4 = cfg.getString("messages.barrel-sign-line4", "");
        enabled = cfg.getBoolean("enabled", true);
        paperPlayerDataDir = cfg.getString("paper-playerdata-dir",
                "/mnt/pool/survival/world/players/data");
        fabricPlayerDataDir = cfg.getString("fabric-playerdata-dir",
                "/mnt/pool/fabric/world/players/data");
        String goLive = cfg.getString("fabric-go-live", "2026-09-03T00:00:00Z");
        try {
            fabricGoLiveEpochMs = java.time.Instant.parse(goLive).toEpochMilli();
        } catch (Exception e) {
            fabricGoLiveEpochMs = java.time.Instant.parse("2026-09-03T00:00:00Z").toEpochMilli();
        }
        thisSide = cfg.getString("this-side", "paper");
        skipProtectedRegions = cfg.getBoolean("skip-protected-regions", true);

        // N12: Redis connection settings; defaults preserve the previous hardcodes.
        redisSentinelMaster = cfg.getString("redis.sentinel-master", "azpbmd");
        redisSentinels = cfg.getStringList("redis.sentinels");
        if (redisSentinels == null || redisSentinels.isEmpty()) {
            redisSentinels = List.of("10.0.0.1:26379", "10.0.0.2:26379", "10.0.0.3:26379");
        }
        redisFallbackHost = cfg.getString("redis.fallback-host", "10.0.0.3");
        redisFallbackPort = Math.max(1, cfg.getInt("redis.fallback-port", 6379));
        redisPasswordFile = cfg.getString("redis.password-file", "/mnt/pool/skygate/redis.pass");
        redisFailClosed = cfg.getBoolean("redis.fail-closed", true);
    }

    public int getInactiveDays() { return inactiveDays; }
    public int getGracePeriodDays() { return gracePeriodDays; }
    public int getCooldownAfterFullRunHours() { return cooldownAfterFullRunHours; }
    public int getTpsStableMinutes() { return tpsStableMinutes; }
    public int getPlayersPerMinute() { return playersPerMinute; }
    public int getRecheckDays() { return recheckDays; }
    public String getDiscordWebhookUrl() { return discordWebhookUrl; }
    public boolean isDiscordEnabled() { return discordWebhookUrl != null && !discordWebhookUrl.isBlank(); }

    public String getCleanedOnLoginMessage() { return cleanedOnLoginMessage; }
    public String getBarrelSignLine1() { return barrelSignLine1; }
    public String getBarrelSignLine2() { return barrelSignLine2; }
    public String getBarrelSignLine3() { return barrelSignLine3; }
    public String getBarrelSignLine4() { return barrelSignLine4; }
    public boolean isEnabled() { return enabled; }
    public String getPaperPlayerDataDir() { return paperPlayerDataDir; }
    public String getFabricPlayerDataDir() { return fabricPlayerDataDir; }
    public long getFabricGoLiveEpochMs() { return fabricGoLiveEpochMs; }
    public String getThisSide() { return thisSide; }
    public boolean isPaperSide() { return !"fabric".equalsIgnoreCase(thisSide); }
    public boolean isSkipProtectedRegions() { return skipProtectedRegions; }

    public String getRedisSentinelMaster() { return redisSentinelMaster; }
    public List<String> getRedisSentinels() { return redisSentinels; }
    public String getRedisFallbackHost() { return redisFallbackHost; }
    public int getRedisFallbackPort() { return redisFallbackPort; }
    public String getRedisPasswordFile() { return redisPasswordFile; }
    public boolean isRedisFailClosed() { return redisFailClosed; }
}
