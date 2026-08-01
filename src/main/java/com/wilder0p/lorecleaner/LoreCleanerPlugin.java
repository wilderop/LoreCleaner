package com.wilder0p.lorecleaner;

import com.wilder0p.lorecleaner.command.LoreCleanerCommand;
import com.wilder0p.lorecleaner.listener.PlayerLoginListener;
import com.wilder0p.lorecleaner.manager.CleanerManager;
import com.wilder0p.lorecleaner.manager.ConfigManager;
import com.wilder0p.lorecleaner.manager.DataManager;
import com.wilder0p.lorecleaner.manager.TpsMonitor;
import org.bukkit.plugin.java.JavaPlugin;

public final class LoreCleanerPlugin extends JavaPlugin {

    private static LoreCleanerPlugin instance;

    private ConfigManager configManager;
    private DataManager dataManager;
    private TpsMonitor tpsMonitor;
    private CleanerManager cleanerManager;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();

        this.configManager = new ConfigManager(this);
        this.dataManager = new DataManager(this);
        this.tpsMonitor = new TpsMonitor(this);
        this.cleanerManager = new CleanerManager(this);

        var cmd = getCommand("lorecleaner");
        if (cmd != null) {
            LoreCleanerCommand executor = new LoreCleanerCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        getServer().getPluginManager().registerEvents(new PlayerLoginListener(this), this);

        tpsMonitor.start();
        cleanerManager.start();

        getLogger().info("LoreCleaner enabled. Grace period ends at: " + dataManager.getGraceEndTime());
    }

    @Override
    public void onDisable() {
        if (cleanerManager != null) {
            cleanerManager.shutdown();
        }
        if (tpsMonitor != null) {
            tpsMonitor.shutdown();
        }
        if (dataManager != null) {
            dataManager.save();
        }
        getLogger().info("LoreCleaner disabled.");
    }

    public static LoreCleanerPlugin getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public DataManager getDataManager() {
        return dataManager;
    }

    public TpsMonitor getTpsMonitor() {
        return tpsMonitor;
    }

    public CleanerManager getCleanerManager() {
        return cleanerManager;
    }
}
