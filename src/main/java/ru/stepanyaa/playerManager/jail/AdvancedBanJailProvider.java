package ru.stepanyaa.playerManager.jail;

import org.bukkit.plugin.Plugin;
import ru.stepanyaa.playerManager.util.TimeUtil;

public class AdvancedBanJailProvider extends CommandJailProvider {

    public AdvancedBanJailProvider(Plugin owner) {
        super(owner, "AdvancedBan", "AdvancedBan");
        commands("tempmute %player% %time% [JAIL:%jail%] %reason%", "unmute %player%");
        permanentCommand("mute %player% [JAIL:%jail%] %reason%");
        capabilities(true, true, true);
    }

    @Override
    protected String formatDuration(long seconds) {
        return TimeUtil.toShortForm(seconds);
    }
}
