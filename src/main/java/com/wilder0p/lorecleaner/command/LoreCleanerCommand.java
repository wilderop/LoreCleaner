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
                sender.sendMessage(Component.text(
                        "Force run queued. Processing will begin when TPS conditions are met (or immediately if already stable).",
                        NamedTextColor.GREEN));
            }
            case "status" -> sendStatus(sender);
            case "reload" -> {
                plugin.getConfigManager().reload();
                sender.sendMessage(Component.text("Config reloaded.", NamedTextColor.GREEN));
            }
            case "test" -> handleTest(sender, args);
            default -> sendHelp(sender);
        }
        return true;
    }

    private void handleTest(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /lorecleaner test <months> [limit]", NamedTextColor.RED));
            sender.sendMessage(Component.text("Example: /lorecleaner test 6 10  — 6-month cutoff, only first 10 files", NamedTextColor.GRAY));
            sender.sendMessage(Component.text("Omit limit to scan every eligible offline player.", NamedTextColor.GRAY));
            return;
        }

        int months;
        try {
            months = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Months must be a whole number (e.g. 6).", NamedTextColor.RED));
            return;
        }

        if (months < 1) {
            sender.sendMessage(Component.text("Months must be >= 1.", NamedTextColor.RED));
            return;
        }

        int limit = 0; // 0 = no limit
        if (args.length >= 3) {
            try {
                limit = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                sender.sendMessage(Component.text("Limit must be a whole number (e.g. 10).", NamedTextColor.RED));
                return;
            }
            if (limit < 1) {
                sender.sendMessage(Component.text("Limit must be >= 1 (or omit it for no limit).", NamedTextColor.RED));
                return;
            }
        }

        plugin.getCleanerManager().startTestRun(sender, months, limit);
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("LoreCleaner commands:", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/lorecleaner force              — Force a cleaning cycle", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/lorecleaner test <months> [limit] — Dry-run; optional file limit", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  e.g. /lorecleaner test 6 10    — 6 months, only 10 files", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/lorecleaner status             — Show current state", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/lorecleaner reload             — Reload config.yml", NamedTextColor.YELLOW));
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
        sender.sendMessage(Component.text("Test scan running: " + cleaner.isTestRunning(), NamedTextColor.WHITE));
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
            return Stream.of("force", "test", "status", "reload")
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("test")) {
            return Stream.of("1", "3", "6", "12")
                    .filter(s -> s.startsWith(args[1]))
                    .toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("test")) {
            return Stream.of("5", "10", "25", "50", "100")
                    .filter(s -> s.startsWith(args[2]))
                    .toList();
        }
        return List.of();
    }
}
