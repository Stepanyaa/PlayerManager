package ru.stepanyaa.playerManager.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLocaleChangeEvent;
import ru.stepanyaa.playerManager.PlayerManager;

public class LocaleListener implements Listener {

    private final PlayerManager plugin;

    public LocaleListener(PlayerManager plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLocaleChange(PlayerLocaleChangeEvent event) {
        final Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                plugin.getMessageManager().cacheLocale(player);
            }
        });
    }
}
