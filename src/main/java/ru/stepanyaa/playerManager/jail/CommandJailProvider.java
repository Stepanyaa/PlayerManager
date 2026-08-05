package ru.stepanyaa.playerManager.jail;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import ru.stepanyaa.playerManager.util.TimeUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class CommandJailProvider implements JailProvider {

    protected final Plugin owner;
    private final String name;
    private final String pluginName;

    private String jailCommand;
    private String permanentJailCommand;
    private String unjailCommand;
    private String listCommand;

    private boolean supportsPermanent = true;
    private boolean supportsTimed = true;
    private boolean supportsOffline = false;

    private final List<JailInfo> staticJails = new ArrayList<JailInfo>();

    public CommandJailProvider(Plugin owner, String name, String pluginName) {
        this.owner = owner;
        this.name = name;
        this.pluginName = pluginName;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getPluginName() {
        return pluginName;
    }

    @Override
    public boolean isAvailable() {
        if (pluginName == null || pluginName.isEmpty()) {
            return jailCommand != null && !jailCommand.isEmpty();
        }
        Plugin plugin = Bukkit.getPluginManager().getPlugin(pluginName);
        return plugin != null && plugin.isEnabled();
    }

    @Override
    public List<JailInfo> getJails() {
        return Collections.unmodifiableList(new ArrayList<JailInfo>(staticJails));
    }

    @Override
    public boolean jailPlayer(CommandSender executor, OfflinePlayer target, String jail, long durationSeconds, String reason) {
        boolean permanent = durationSeconds <= 0L;
        String template = permanent && permanentJailCommand != null && !permanentJailCommand.isEmpty()
                ? permanentJailCommand
                : jailCommand;
        if (template == null || template.isEmpty()) {
            return false;
        }
        return dispatch(executor, apply(template, target, jail, durationSeconds, reason, executor));
    }

    @Override
    public boolean unjailPlayer(CommandSender executor, OfflinePlayer target) {
        if (unjailCommand == null || unjailCommand.isEmpty()) {
            return false;
        }
        return dispatch(executor, apply(unjailCommand, target, null, 0L, "", executor));
    }

    @Override
    public boolean supportsPermanent() {
        return supportsPermanent;
    }

    @Override
    public boolean supportsTimed() {
        return supportsTimed;
    }

    @Override
    public boolean supportsOffline() {
        return supportsOffline;
    }

    protected String apply(String template, OfflinePlayer target, String jail, long durationSeconds,
                           String reason, CommandSender executor) {
        String playerName = target.getName();
        if (playerName == null || playerName.isEmpty()) {
            playerName = target.getUniqueId().toString();
        }
        String safeReason = (reason == null) ? "" : reason.trim();
        String adminName = executor == null ? "CONSOLE" : executor.getName();

        String out = template;
        out = out.replace("%player%", playerName);
        out = out.replace("%uuid%", target.getUniqueId().toString());
        out = out.replace("%jail%", jail == null ? "" : jail);
        out = out.replace("%time%", durationSeconds <= 0L ? permanentKeyword() : formatDuration(durationSeconds));
        out = out.replace("%seconds%", String.valueOf(Math.max(0L, durationSeconds)));
        out = out.replace("%minutes%", String.valueOf(Math.max(0L, durationSeconds / 60L)));
        out = out.replace("%reason%", safeReason);
        out = out.replace("%admin%", adminName);

        while (out.contains("  ")) {
            out = out.replace("  ", " ");
        }
        return out.trim();
    }

    protected String formatDuration(long seconds) {
        return TimeUtil.toShortForm(seconds);
    }

    protected String permanentKeyword() {
        return "permanent";
    }

    protected boolean dispatch(CommandSender executor, String command) {
        if (command == null || command.isEmpty()) {
            return false;
        }
        final String clean = command.startsWith("/") ? command.substring(1) : command;
        if (Bukkit.isPrimaryThread()) {
            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), clean);
        }
        Bukkit.getScheduler().runTask(owner, new Runnable() {
            @Override
            public void run() {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), clean);
            }
        });
        return true;
    }

    protected List<JailInfo> namesToJails(Iterable<String> names) {
        List<JailInfo> jails = new ArrayList<JailInfo>();
        if (names == null) {
            return jails;
        }
        for (String raw : names) {
            if (raw == null) {
                continue;
            }
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            jails.add(new JailInfo(trimmed, capitalize(trimmed)));
        }
        return jails;
    }

    protected static String capitalize(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        return input.substring(0, 1).toUpperCase(Locale.ROOT) + input.substring(1);
    }

    public CommandJailProvider commands(String jailCommand, String unjailCommand) {
        this.jailCommand = jailCommand;
        this.unjailCommand = unjailCommand;
        return this;
    }

    public CommandJailProvider permanentCommand(String permanentJailCommand) {
        this.permanentJailCommand = permanentJailCommand;
        return this;
    }

    public CommandJailProvider listCommand(String listCommand) {
        this.listCommand = listCommand;
        return this;
    }

    public CommandJailProvider capabilities(boolean permanent, boolean timed, boolean offline) {
        this.supportsPermanent = permanent;
        this.supportsTimed = timed;
        this.supportsOffline = offline;
        return this;
    }

    public String getListCommand() {
        return listCommand;
    }

    public String getJailCommand() {
        return jailCommand;
    }

    public String getUnjailCommand() {
        return unjailCommand;
    }

    protected void setStaticJails(List<JailInfo> jails) {
        this.staticJails.clear();
        if (jails != null) {
            this.staticJails.addAll(jails);
        }
    }

    protected List<JailInfo> rawStaticJails() {
        return staticJails;
    }
}
