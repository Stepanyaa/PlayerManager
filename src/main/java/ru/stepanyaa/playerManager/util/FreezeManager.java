package ru.stepanyaa.playerManager.util;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class FreezeManager implements Listener {

    private final Plugin plugin;
    private final Set<UUID> frozen = new HashSet<UUID>();

    public FreezeManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public boolean isFrozen(Player player) {
        return player != null && frozen.contains(player.getUniqueId());
    }

    public boolean isFrozen(UUID uuid) {
        return uuid != null && frozen.contains(uuid);
    }

    public boolean toggle(Player target) {
        if (target == null) {
            return false;
        }
        if (isFrozen(target)) {
            unfreeze(target);
            return false;
        }
        freeze(target);
        return true;
    }

    public void freeze(Player target) {
        if (target == null) {
            return;
        }
        frozen.add(target.getUniqueId());
        if (plugin.getConfig().getBoolean("freeze.blindness", true)) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, Integer.MAX_VALUE, 0, false, false));
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, Integer.MAX_VALUE, 10, false, false));
        }
        String message = plugin.getConfig().getString("freeze.message-frozen",
                "&c&lYou have been frozen by an administrator. Do not log out!");
        target.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }

    public void unfreeze(Player target) {
        if (target == null) {
            return;
        }
        frozen.remove(target.getUniqueId());
        target.removePotionEffect(PotionEffectType.BLINDNESS);
        target.removePotionEffect(PotionEffectType.SLOW);
        String message = plugin.getConfig().getString("freeze.message-unfrozen",
                "&a&lYou have been unfrozen.");
        target.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }

    public void unfreezeAll() {
        for (UUID uuid : new HashSet<UUID>(frozen)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                unfreeze(player);
            }
        }
        frozen.clear();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!isFrozen(event.getPlayer())) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        if (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ()
                && from.getY() >= to.getY()) {
            return;
        }
        Location blocked = from.clone();
        blocked.setPitch(to.getPitch());
        blocked.setYaw(to.getYaw());
        event.setTo(blocked);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (isFrozen(event.getPlayer()) && plugin.getConfig().getBoolean("freeze.notify-on-quit", true)) {
            String name = event.getPlayer().getName();
            plugin.getLogger().warning("Frozen player " + name + " left the server!");
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.hasPermission("playermanager.admin")) {
                    online.sendMessage(ChatColor.RED + "[PlayerManager] " + ChatColor.YELLOW
                            + "Frozen player " + name + " left the server!");
                }
            }
        }
    }
}
