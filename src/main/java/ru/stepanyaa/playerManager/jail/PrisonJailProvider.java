package ru.stepanyaa.playerManager.jail;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import ru.stepanyaa.playerManager.util.TimeUtil;

import java.util.ArrayList;
import java.util.List;

public class PrisonJailProvider extends CommandJailProvider {

    private static final String[] KNOWN_PRISON_PLUGINS = {
            "Prison", "PrisonMines", "MineResetLite", "PrisonCore", "PrisonRanksX"
    };

    private String detectedPlugin;

    public PrisonJailProvider(Plugin owner) {
        super(owner, "Prison", null);
        commands("prison jail %player% %jail% %time% %reason%", "prison unjail %player%");
        permanentCommand("prison jail %player% %jail% permanent %reason%");
        listCommand("prison mines list");
        capabilities(true, true, false);
    }

    @Override
    public String getPluginName() {
        return detectedPlugin;
    }

    @Override
    public boolean isAvailable() {
        for (String candidate : KNOWN_PRISON_PLUGINS) {
            Plugin plugin = Bukkit.getPluginManager().getPlugin(candidate);
            if (plugin != null && plugin.isEnabled()) {
                detectedPlugin = candidate;
                return true;
            }
        }
        return false;
    }

    @Override
    public List<JailInfo> getJails() {
        List<JailInfo> configured = super.getJails();
        if (!configured.isEmpty()) {
            return configured;
        }
        List<JailInfo> fallback = new ArrayList<JailInfo>();
        fallback.add(new JailInfo("prison", "Prison"));
        return fallback;
    }

    @Override
    protected String formatDuration(long seconds) {
        return TimeUtil.toShortForm(seconds);
    }
}
