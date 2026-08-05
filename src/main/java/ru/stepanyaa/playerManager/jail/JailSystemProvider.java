package ru.stepanyaa.playerManager.jail;

import org.bukkit.plugin.Plugin;
import ru.stepanyaa.playerManager.util.TimeUtil;

import java.util.List;

public class JailSystemProvider extends JailsProvider {

    public JailSystemProvider(Plugin owner) {
        super(owner);
        commands("jailsystem jail %player% %jail% %time%", "jailsystem unjail %player%");
        permanentCommand("jailsystem jail %player% %jail% -1");
        listCommand("jailsystem list");
        capabilities(true, true, false);
    }

    @Override
    public String getName() {
        return "JailSystem";
    }

    @Override
    public String getPluginName() {
        return "JailSystem";
    }

    @Override
    public List<JailInfo> getJails() {
        List<JailInfo> jails = readFromFile("JailSystem");
        return jails.isEmpty() ? rawStaticJails() : jails;
    }

    @Override
    protected String formatDuration(long seconds) {
        return TimeUtil.toMinutes(seconds);
    }
}
