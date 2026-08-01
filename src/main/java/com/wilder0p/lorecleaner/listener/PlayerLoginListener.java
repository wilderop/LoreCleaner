package com.wilder0p.lorecleaner.listener;

import com.wilder0p.lorecleaner.LoreCleanerPlugin;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class PlayerLoginListener implements Listener {

    private final LoreCleanerPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public PlayerLoginListener(LoreCleanerPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        var data = plugin.getDataManager();

        if (data.hasPendingLoginMessage(player.getUniqueId())) {
            String msg = plugin.getConfigManager().getCleanedOnLoginMessage();
            player.sendMessage(miniMessage.deserialize(msg));
            data.clearPendingLoginMessage(player.getUniqueId());
        }
    }
}
