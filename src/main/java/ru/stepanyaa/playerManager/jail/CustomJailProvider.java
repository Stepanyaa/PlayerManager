package ru.stepanyaa.playerManager.jail;

import org.bukkit.plugin.Plugin;
import ru.stepanyaa.playerManager.util.TimeUtil;

import java.util.ArrayList;
import java.util.List;

public class CustomJailProvider extends CommandJailProvider {

    private final String requiredPlugin;
    private final String timeFormat;

    public CustomJailProvider(Plugin owner, String name, String requiredPlugin, String jailCommand,
                              String permanentCmd, String unjailCommand, List<String> jailNames,
                              String timeFormat, boolean permanent, boolean timed, boolean offline) {
        super(owner, name, null);
        this.requiredPlugin = requiredPlugin;
        this.timeFormat = (timeFormat == null || timeFormat.isEmpty()) ? "short" : timeFormat.toLowerCase();

        commands(jailCommand, unjailCommand);
        permanentCommand(permanentCmd);
        capabilities(permanent, timed, offline);

        List<String> names = (jailNames == null) ? new ArrayList<String>() : jailNames;
        setStaticJails(namesToJails(names));
    }

    @Override
    public String getPluginName() {
        return requiredPlugin;
    }

    @Override
    public boolean isAvailable() {
        if (getJailCommand() == null || getJailCommand().isEmpty()) {
            return false;
        }
        if (requiredPlugin == null || requiredPlugin.isEmpty()) {
            return true;
        }
        org.bukkit.plugin.Plugin plugin = org.bukkit.Bukkit.getPluginManager().getPlugin(requiredPlugin);
        return plugin != null && plugin.isEnabled();
    }

    @Override
    public List<JailInfo> getJails() {
        List<JailInfo> jails = super.getJails();
        if (!jails.isEmpty()) {
            return jails;
        }
        List<JailInfo> fallback = new ArrayList<JailInfo>();
        fallback.add(new JailInfo("default", "Default"));
        return fallback;
    }

    @Override
    protected String formatDuration(long seconds) {
        if ("minutes".equals(timeFormat)) {
            return TimeUtil.toMinutes(seconds);
        }
        if ("seconds".equals(timeFormat)) {
            return String.valueOf(Math.max(1L, seconds));
        }
        if ("ticks".equals(timeFormat)) {
            return TimeUtil.toTicks(seconds);
        }
        return TimeUtil.toShortForm(seconds);
    }
}
