package ru.stepanyaa.playerManager.jail;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import ru.stepanyaa.playerManager.util.TimeUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class JailsProvider extends CommandJailProvider {

    public JailsProvider(Plugin owner) {
        super(owner, "Jails", "Jails");
        commands("jails jail %player% %jail% %time% %reason%", "jails unjail %player%");
        permanentCommand("jails jail %player% %jail% -1 %reason%");
        listCommand("jails list");
        capabilities(true, true, true);
    }

    @Override
    public List<JailInfo> getJails() {
        List<JailInfo> jails = readFromFile("Jails");
        return jails.isEmpty() ? super.getJails() : jails;
    }

    protected List<JailInfo> readFromFile(String pluginName) {
        List<JailInfo> jails = new ArrayList<JailInfo>();
        Plugin plugin = Bukkit.getPluginManager().getPlugin(pluginName);
        if (plugin == null) {
            return jails;
        }
        File folder = plugin.getDataFolder();
        String[] candidates = new String[]{"jails.yml", "Jails.yml", "data.yml", "config.yml"};
        for (String candidate : candidates) {
            File file = new File(folder, candidate);
            if (!file.exists()) {
                continue;
            }
            try {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
                ConfigurationSection section = yaml.getConfigurationSection("jails");
                if (section == null) {
                    section = yaml.getConfigurationSection("Jails");
                }
                if (section == null) {
                    continue;
                }
                for (String key : section.getKeys(false)) {
                    jails.add(new JailInfo(key, capitalize(key)));
                }
                if (!jails.isEmpty()) {
                    return jails;
                }
            } catch (Throwable ignored) {
            }
        }
        return jails;
    }

    @Override
    protected String formatDuration(long seconds) {
        return TimeUtil.toMinutes(seconds);
    }

    @Override
    protected String permanentKeyword() {
        return "-1";
    }
}
