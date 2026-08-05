package ru.stepanyaa.playerManager.jail;

import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import ru.stepanyaa.playerManager.util.NameUtil;
import ru.stepanyaa.playerManager.util.TimeUtil;

public class LiteBansJailProvider extends CommandJailProvider {

    public LiteBansJailProvider(Plugin owner) {
        super(owner, "LiteBans", "LiteBans");
        commands("litebans:mute %player% %time% [JAIL:%jail%] %reason%", "litebans:unmute %player%");
        permanentCommand("litebans:mute %player% [JAIL:%jail%] %reason%");
        capabilities(true, true, true);
    }

    @Override
    public boolean jailPlayer(CommandSender executor, OfflinePlayer target, String jail, long durationSeconds, String reason) {
        return super.jailPlayer(executor, target, jail, durationSeconds, reason);
    }

    @Override
    protected String apply(String template, OfflinePlayer target, String jail, long durationSeconds,
                           String reason, CommandSender executor) {
        String result = super.apply(template, target, jail, durationSeconds, reason, executor);
        String plainName = NameUtil.sanitize(target.getName());
        if (!NameUtil.isPlainName(plainName)) {
            result = result.replace(plainName, target.getUniqueId().toString());
        }
        return result;
    }

    @Override
    protected String formatDuration(long seconds) {
        return TimeUtil.toShortForm(seconds);
    }

    @Override
    protected String permanentKeyword() {
        return "";
    }
}
