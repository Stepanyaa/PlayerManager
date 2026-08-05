package ru.stepanyaa.playerManager.punish;

import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import ru.stepanyaa.playerManager.util.NameUtil;
import ru.stepanyaa.playerManager.util.TimeUtil;

import java.util.Date;
import java.util.Locale;

public class PunishmentManager {

    public enum Backend {
        LITEBANS("LiteBans"),
        ADVANCEDBAN("AdvancedBan"),
        LIBERTYBANS("LibertyBans"),
        BANMANAGER("BanManager"),
        CMI("CMI"),
        ESSENTIALS("Essentials"),
        VANILLA("Vanilla");

        private final String pluginName;

        Backend(String pluginName) {
            this.pluginName = pluginName;
        }

        public String getPluginName() {
            return pluginName;
        }
    }

    private final Plugin plugin;
    private Backend backend = Backend.VANILLA;

    public PunishmentManager(Plugin plugin) {
        this.plugin = plugin;
        detect();
    }

    public final void detect() {
        String forced = plugin.getConfig().getString("punishments.force-backend", "auto");
        if (forced != null && !forced.trim().isEmpty() && !forced.equalsIgnoreCase("auto")) {
            for (Backend candidate : Backend.values()) {
                if (candidate.name().equalsIgnoreCase(forced.trim())) {
                    backend = candidate;
                    plugin.getLogger().info("Punishment backend forced to " + backend.name());
                    return;
                }
            }
        }

        for (Backend candidate : Backend.values()) {
            if (candidate == Backend.VANILLA) {
                continue;
            }
            if (isEnabled(candidate.getPluginName())
                    || (candidate == Backend.ESSENTIALS && isEnabled("EssentialsX"))) {
                backend = candidate;
                plugin.getLogger().info("Punishment backend detected: " + candidate.getPluginName());
                return;
            }
        }

        backend = Backend.VANILLA;
        plugin.getLogger().info("No punishment plugin found, using the built-in Bukkit ban list.");
    }

    private boolean isEnabled(String pluginName) {
        Plugin target = Bukkit.getPluginManager().getPlugin(pluginName);
        return target != null && target.isEnabled();
    }

    public Backend getBackend() {
        return backend;
    }

    public String getBackendName() {
        return backend == Backend.VANILLA ? "Vanilla" : backend.getPluginName();
    }

    public boolean supportsTemporaryBans() {
        return true;
    }

    public boolean ban(CommandSender executor, OfflinePlayer target, String reason) {
        return applyBan(executor, target, TimeUtil.PERMANENT, reason);
    }

    public boolean tempBan(CommandSender executor, OfflinePlayer target, long durationSeconds, String reason) {
        return applyBan(executor, target, durationSeconds, reason);
    }

    public boolean unban(CommandSender executor, OfflinePlayer target) {
        String identifier = NameUtil.commandIdentifier(target, supportsUuid());
        String name = NameUtil.sanitize(target.getName());

        boolean dispatched;
        switch (backend) {
            case LITEBANS:
                dispatched = dispatch("litebans:unban " + identifier);
                break;
            case ADVANCEDBAN:
                dispatched = dispatch("unban " + name);
                break;
            case LIBERTYBANS:
                dispatched = dispatch("libertybans:unban " + identifier);
                break;
            case BANMANAGER:
                dispatched = dispatch("bmunban " + name);
                break;
            case CMI:
                dispatched = dispatch("cmi unban " + name);
                break;
            case ESSENTIALS:
                dispatched = dispatch("essentials:unban " + name);
                break;
            case VANILLA:
            default:
                dispatched = false;
                break;
        }

        if (!name.isEmpty()) {
            Bukkit.getBanList(BanList.Type.NAME).pardon(name);
        }
        Bukkit.getBanList(BanList.Type.NAME).pardon(target.getUniqueId().toString());
        return dispatched || true;
    }

    public boolean kick(CommandSender executor, Player target, String reason) {
        final String message = ChatColor.translateAlternateColorCodes('&',
                format("punishments.kick-screen",
                        "&c&lYou were kicked\n&7Reason: &f%reason%\n&7By: &f%admin%",
                        target, reason, TimeUtil.PERMANENT, executor));
        if (Bukkit.isPrimaryThread()) {
            target.kickPlayer(message);
        } else {
            final Player player = target;
            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override
                public void run() {
                    player.kickPlayer(message);
                }
            });
        }
        return true;
    }

    private boolean applyBan(CommandSender executor, OfflinePlayer target, long durationSeconds, String reason) {
        if (target == null) {
            return false;
        }
        String safeReason = normalizeReason(reason);
        boolean permanent = durationSeconds <= 0L;

        boolean success;
        switch (backend) {
            case LITEBANS:
                success = liteBans(target, durationSeconds, safeReason, permanent);
                break;
            case ADVANCEDBAN:
                success = advancedBan(target, durationSeconds, safeReason, permanent);
                break;
            case LIBERTYBANS:
                success = libertyBans(target, durationSeconds, safeReason, permanent);
                break;
            case BANMANAGER:
                success = banManager(target, durationSeconds, safeReason, permanent);
                break;
            case CMI:
                success = cmi(target, durationSeconds, safeReason, permanent);
                break;
            case ESSENTIALS:
                success = essentials(target, durationSeconds, safeReason, permanent);
                break;
            case VANILLA:
            default:
                success = vanilla(executor, target, durationSeconds, safeReason);
                break;
        }

        if (success && backend != Backend.VANILLA) {
            kickIfOnline(executor, target, durationSeconds, safeReason);
        }
        return success;
    }

    private boolean liteBans(OfflinePlayer target, long seconds, String reason, boolean permanent) {
        String id = NameUtil.commandIdentifier(target, true);
        if (permanent) {
            return dispatch("litebans:ban " + id + " " + reason);
        }
        return dispatch("litebans:tempban " + id + " " + TimeUtil.toShortForm(seconds) + " " + reason);
    }

    private boolean advancedBan(OfflinePlayer target, long seconds, String reason, boolean permanent) {
        String name = NameUtil.sanitize(target.getName());
        if (name.isEmpty()) {
            name = target.getUniqueId().toString();
        }
        if (permanent) {
            return dispatch("ban " + name + " " + reason);
        }
        return dispatch("tempban " + name + " " + TimeUtil.toShortForm(seconds) + " " + reason);
    }

    private boolean libertyBans(OfflinePlayer target, long seconds, String reason, boolean permanent) {
        String id = NameUtil.commandIdentifier(target, true);
        if (permanent) {
            return dispatch("libertybans:ban " + id + " " + reason);
        }
        return dispatch("libertybans:ban " + id + " " + TimeUtil.toShortForm(seconds) + " " + reason);
    }

    private boolean banManager(OfflinePlayer target, long seconds, String reason, boolean permanent) {
        String name = NameUtil.sanitize(target.getName());
        if (permanent) {
            return dispatch("bmban " + name + " " + reason);
        }
        return dispatch("bmtempban " + name + " " + TimeUtil.toShortForm(seconds) + " " + reason);
    }

    private boolean cmi(OfflinePlayer target, long seconds, String reason, boolean permanent) {
        String name = NameUtil.sanitize(target.getName());
        if (name.isEmpty()) {
            name = target.getUniqueId().toString();
        }
        if (permanent) {
            return dispatch("cmi ban " + name + " -s " + reason);
        }
        return dispatch("cmi tempban " + name + " " + TimeUtil.toShortForm(seconds) + " -s " + reason);
    }

    private boolean essentials(OfflinePlayer target, long seconds, String reason, boolean permanent) {
        String name = NameUtil.sanitize(target.getName());
        if (name.isEmpty() || !NameUtil.isPlainName(name)) {
            return vanilla(null, target, seconds, reason);
        }
        if (permanent) {
            return dispatch("essentials:ban " + name + " " + reason);
        }
        return dispatch("essentials:tempban " + name + " " + TimeUtil.toShortForm(seconds) + " " + reason);
    }

    private boolean vanilla(CommandSender executor, OfflinePlayer target, long seconds, String reason) {
        String source = executor == null ? "PlayerManager" : executor.getName();
        Date expires = seconds > 0L ? new Date(TimeUtil.expiryFromNow(seconds)) : null;

        String name = NameUtil.sanitize(target.getName());
        if (!name.isEmpty()) {
            Bukkit.getBanList(BanList.Type.NAME).addBan(name, reason, expires, source);
        }
        Bukkit.getBanList(BanList.Type.NAME).addBan(target.getUniqueId().toString(), reason, expires, source);

        kickIfOnline(executor, target, seconds, reason);
        return true;
    }

    private void kickIfOnline(CommandSender executor, OfflinePlayer target, long seconds, String reason) {
        final Player online = target.getPlayer();
        if (online == null || !online.isOnline()) {
            return;
        }
        String key = seconds > 0L ? "punishments.tempban-screen" : "punishments.ban-screen";
        String fallback = seconds > 0L
                ? "&c&lYou are temporarily banned\n&7Reason: &f%reason%\n&7Duration: &f%duration%\n&7By: &f%admin%"
                : "&4&lYou are banned\n&7Reason: &f%reason%\n&7By: &f%admin%";
        final String message = ChatColor.translateAlternateColorCodes('&',
                format(key, fallback, target, reason, seconds, executor));

        if (Bukkit.isPrimaryThread()) {
            online.kickPlayer(message);
        } else {
            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override
                public void run() {
                    if (online.isOnline()) {
                        online.kickPlayer(message);
                    }
                }
            });
        }
    }

    private String format(String configKey, String fallback, OfflinePlayer target, String reason,
                          long seconds, CommandSender executor) {
        String template = plugin.getConfig().getString(configKey, fallback);
        if (template == null) {
            template = fallback;
        }
        template = template.replace("\\n", "\n");
        return template
                .replace("%player%", NameUtil.displayName(target))
                .replace("%uuid%", target.getUniqueId().toString())
                .replace("%reason%", reason == null ? "" : reason)
                .replace("%duration%", seconds > 0L ? humanDuration(seconds) : permanentLabel())
                .replace("%admin%", executor == null ? "CONSOLE" : executor.getName());
    }

    public String humanDuration(long seconds) {
        if (seconds <= 0L) {
            return permanentLabel();
        }
        return TimeUtil.humanize(seconds,
                plugin.getConfig().getString("time-labels.day", "d"),
                plugin.getConfig().getString("time-labels.hour", "h"),
                plugin.getConfig().getString("time-labels.minute", "m"),
                plugin.getConfig().getString("time-labels.second", "s"));
    }

    public String permanentLabel() {
        return plugin.getConfig().getString("time-labels.permanent", "permanent");
    }

    private String normalizeReason(String reason) {
        String value = (reason == null) ? "" : ChatColor.stripColor(reason).trim();
        value = value.replace("\n", " ").replace("\r", " ");
        while (value.contains("  ")) {
            value = value.replace("  ", " ");
        }
        if (value.isEmpty()) {
            value = plugin.getConfig().getString("punishments.default-reason", "Rule violation");
        }
        int maxLength = plugin.getConfig().getInt("punishments.max-reason-length", 96);
        if (maxLength > 0 && value.length() > maxLength) {
            value = value.substring(0, maxLength);
        }
        return value;
    }

    private boolean supportsUuid() {
        return backend == Backend.LITEBANS || backend == Backend.LIBERTYBANS;
    }

    private boolean dispatch(String command) {
        final String clean = command.startsWith("/") ? command.substring(1) : command;
        if (Bukkit.isPrimaryThread()) {
            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), clean);
        }
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), clean);
            }
        });
        return true;
    }

    public boolean isBanned(OfflinePlayer target) {
        if (target.isBanned()) {
            return true;
        }
        return Bukkit.getBanList(BanList.Type.NAME).isBanned(target.getUniqueId().toString());
    }

    public long parseDuration(String input) {
        if (input == null) {
            return Long.MIN_VALUE;
        }
        String value = input.trim().toLowerCase(Locale.ROOT);
        if (value.equals("permanent") || value.equals("perm")) {
            return TimeUtil.PERMANENT;
        }
        return TimeUtil.parseSeconds(value);
    }
}
