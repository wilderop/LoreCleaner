package com.wilder0p.lorecleaner.manager;

import com.wilder0p.lorecleaner.LoreCleanerPlugin;
import org.bukkit.configuration.file.FileConfiguration;

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

    public ConfigManager(LoreCleanerPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();

        inactiveDays = cfg.getInt("inactive-days", 180);
        gracePeriodDays = cfg.getInt("grace-period-days", 30);
        cooldownAfterFullRunHours = cfg.getInt("cooldown-after-full-run-hours", 72);
        tpsStableMinutes = cfg.getInt("tps-stable-minutes", 5);
        playersPerMinute = cfg.getInt("players-per-minute", 4);
        recheckDays = cfg.getInt("recheck-days", 180);
        discordWebhookUrl = cfg.getString("discord-webhook-url", "");

        cleanedOnLoginMessage = cfg.getString("messages.cleaned-on-login",
                "<yellow>While you were offline for more than 6 months, your lore items were moved into a barrel at your last logout location.");
        barrelSignLine1 = cfg.getString("messages.barrel-sign-line1", "LoreCleaner");
        barrelSignLine2 = cfg.getString("messages.barrel-sign-line2", "%player%");
        barrelSignLine3 = cfg.getString("messages.barrel-sign-line3", "%date%");
        barrelSignLine4 = cfg.getString("messages.barrel-sign-line4", "");
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
}
