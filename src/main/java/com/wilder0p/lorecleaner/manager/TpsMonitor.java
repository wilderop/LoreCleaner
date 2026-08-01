package com.wilder0p.lorecleaner.manager;

import com.wilder0p.lorecleaner.LoreCleanerPlugin;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

public class TpsMonitor {

    private final LoreCleanerPlugin plugin;
    private BukkitTask task;

    private long consecutiveGoodTicks = 0;
    private boolean currentlyStable = false;

    public TpsMonitor(LoreCleanerPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    private void tick() {
        double tps = Bukkit.getTPS()[0];
        // Paper rarely reports exact 20.000; treat >= 19.95 as full TPS
        boolean good = tps >= 19.95;

        if (good) {
            consecutiveGoodTicks++;
        } else {
            consecutiveGoodTicks = 0;
            currentlyStable = false;
        }

        long required = plugin.getConfigManager().getTpsStableMinutes() * 60L;
        currentlyStable = consecutiveGoodTicks >= required;
    }

    public boolean isStable() {
        return currentlyStable;
    }

    public long getConsecutiveGoodSeconds() {
        return consecutiveGoodTicks;
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
        }
    }
}
