package ru.stepanyaa.playerManager.quit;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class QuitTracker implements Listener {

    private final Plugin plugin;
    private final FileConfiguration data;
    private final Runnable saver;

    private final Map<UUID, Long> lastCombat = new ConcurrentHashMap<UUID, Long>();
    private final Map<UUID, String> pendingKickReason = new ConcurrentHashMap<UUID, String>();

    public QuitTracker(Plugin plugin, FileConfiguration data, Runnable saver) {
        this.plugin = plugin;
        this.data = data;
        this.saver = saver;
    }

    private long combatWindowMillis() {
        return Math.max(1L, plugin.getConfig().getLong("quit-tracking.combat-seconds", 15L)) * 1000L;
    }

    private String serverName() {
        String configured = plugin.getConfig().getString("quit-tracking.server-name", "");
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        String motd = Bukkit.getServer().getMotd();
        return (motd == null || motd.trim().isEmpty()) ? "server" : motd.trim();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Player victim = event.getEntity() instanceof Player ? (Player) event.getEntity() : null;
        Player attacker = resolveAttacker(event.getDamager());
        long now = System.currentTimeMillis();
        if (victim != null) {
            lastCombat.put(victim.getUniqueId(), now);
        }
        if (attacker != null) {
            lastCombat.put(attacker.getUniqueId(), now);
        }
    }

    private Player resolveAttacker(Entity damager) {
        if (damager instanceof Player) {
            return (Player) damager;
        }
        if (damager instanceof Projectile) {
            Object shooter = ((Projectile) damager).getShooter();
            if (shooter instanceof Player) {
                return (Player) shooter;
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKick(PlayerKickEvent event) {
        String reason = event.getReason();
        pendingKickReason.put(event.getPlayer().getUniqueId(),
                (reason == null || reason.isEmpty()) ? "" : reason);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (!plugin.getConfig().getBoolean("quit-tracking.enabled", true)) {
            return;
        }
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();
        final Location location = player.getLocation();

        Long combatAt = lastCombat.remove(uuid);
        final boolean inCombat = combatAt != null && (System.currentTimeMillis() - combatAt) <= combatWindowMillis();

        String kickReason = pendingKickReason.remove(uuid);
        String reasonKey;
        String reason;
        if (player.isBanned()) {
            reasonKey = QuitInfo.REASON_BANNED;
            reason = plugin.getConfig().getString("quit-tracking.banned-reason", "");
        } else if (kickReason != null) {
            reasonKey = QuitInfo.REASON_KICKED;
            reason = kickReason;
        } else {
            reasonKey = QuitInfo.REASON_DISCONNECTED;
            reason = plugin.getConfig().getString("quit-tracking.default-reason", "");
        }

        final String path = "players." + uuid + ".last_quit";
        final String finalReason = reason == null ? "" : reason;
        final String finalReasonKey = reasonKey;
        final String world = location.getWorld() == null ? "?" : location.getWorld().getName();
        final double x = location.getX();
        final double y = location.getY();
        final double z = location.getZ();
        final String server = serverName();

        data.set(path + ".time", System.currentTimeMillis());
        data.set(path + ".server", server);
        data.set(path + ".world", world);
        data.set(path + ".x", x);
        data.set(path + ".y", y);
        data.set(path + ".z", z);
        data.set(path + ".reason", finalReason);
        data.set(path + ".reason_key", finalReasonKey);
        data.set(path + ".in_combat", inCombat);
        save();
    }

    public QuitInfo getQuitInfo(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        ConfigurationSection section = data.getConfigurationSection("players." + uuid + ".last_quit");
        if (section == null) {
            return null;
        }
        long time = section.getLong("time", 0L);
        if (time <= 0L) {
            return null;
        }
        return new QuitInfo(
                time,
                section.getString("server", "?"),
                section.getString("world", "?"),
                section.getDouble("x", 0.0D),
                section.getDouble("y", 0.0D),
                section.getDouble("z", 0.0D),
                section.getString("reason", ""),
                section.getString("reason_key", ""),
                section.getBoolean("in_combat", false));
    }

    public boolean leftRecently(UUID uuid) {
        QuitInfo info = getQuitInfo(uuid);
        return info != null && info.category() != null;
    }

    public boolean isInCombat(Player player) {
        Long combatAt = lastCombat.get(player.getUniqueId());
        return combatAt != null && (System.currentTimeMillis() - combatAt) <= combatWindowMillis();
    }

    private void save() {
        if (saver != null) {
            saver.run();
        }
    }
}
