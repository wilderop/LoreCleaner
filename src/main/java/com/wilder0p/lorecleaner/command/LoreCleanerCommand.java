package com.wilder0p.lorecleaner.command;

import com.wilder0p.lorecleaner.LoreCleanerPlugin;
import com.wilder0p.lorecleaner.manager.CleanerManager;
import com.wilder0p.lorecleaner.manager.DataManager;
import com.wilder0p.lorecleaner.manager.TpsMonitor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Stream;

public class LoreCleanerCommand implements CommandExecutor, TabCompleter {

    private final LoreCleanerPlugin plugin;

    public LoreCleanerCommand(LoreCleanerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("lorecleaner.admin")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "force" -> {
                plugin.getCleanerManager().forceRun();
                sender.sendMessage(Component.text("Force run queued. Processing will begin when TPS conditions are met (or immediately if already stable).", NamedTextColor.GREEN));
            }
            case "status" -> sendStatus(sender);
            case "reload" -> {
                plugin.getConfigManager().reload();
                sender.sendMessage(Component.text("Config reloaded.", NamedTextColor.GREEN));
            }
            default -> sendHelp(sender);
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("LoreCleaner commands:", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/lorecleaner force  — Force a cleaning cycle", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/lorecleaner status — Show current state", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/lorecleaner reload — Reload config.yml", NamedTextColor.YELLOW));
    }

    private void sendStatus(CommandSender sender) {
        DataManager data = plugin.getDataManager();
        TpsMonitor tps = plugin.getTpsMonitor();
        CleanerManager cleaner = plugin.getCleanerManager();
        var cfg = plugin.getConfigManager();

        sender.sendMessage(Component.text("=== LoreCleaner Status ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Grace period active: " + data.isInGracePeriod(), NamedTextColor.WHITE));
        sender.sendMessage(Component.text("Grace ends: " + data.format(data.getGraceEndTime()), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("TPS currently stable (20.0 for " + cfg.getTpsStableMinutes() + " min): " + tps.isStable(), NamedTextColor.WHITE));
        sender.sendMessage(Component.text("Consecutive good seconds: " + tps.getConsecutiveGoodSeconds(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Currently processing: " + cleaner.isCurrentlyProcessing(), NamedTextColor.WHITE));
        sender.sendMessage(Component.text("Queue size: " + cleaner.getQueueSize(), NamedTextColor.WHITE));
        sender.sendMessage(Component.text("Processed this cycle: " + cleaner.getProcessedThisCycle(), NamedTextColor.GRAY));

        Instant lastFull = data.getLastFullRunCompleted();
        if (lastFull != null) {
            Instant next = lastFull.plus(cfg.getCooldownAfterFullRunHours(), ChronoUnit.HOURS);
            sender.sendMessage(Component.text("Last full run: " + data.format(lastFull), NamedTextColor.GRAY));
            sender.sendMessage(Component.text("Next automatic run allowed after: " + data.format(next), NamedTextColor.GRAY));
        } else {
            sender.sendMessage(Component.text("No full run completed yet.", NamedTextColor.GRAY));
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return Stream.of("force", "status", "reload")
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}
