package ru.stepanyaa.playerManager.jail;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import ru.stepanyaa.playerManager.util.TimeUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class DeluxeJailsProvider extends CommandJailProvider {

    public DeluxeJailsProvider(Plugin owner) {
        super(owner, "DeluxeJails", "DeluxeJails");
        commands("djail %player% %jail% %time% %reason%", "dunjail %player%");
        permanentCommand("djail %player% %jail% permanent %reason%");
        listCommand("djails");
        capabilities(true, true, true);
    }

    @Override
    public List<JailInfo> getJails() {
        List<JailInfo> jails = readFromFile();
        return jails.isEmpty() ? super.getJails() : jails;
    }

    private List<JailInfo> readFromFile() {
        List<JailInfo> jails = new ArrayList<JailInfo>();
        Plugin plugin = Bukkit.getPluginManager().getPlugin("DeluxeJails");
        if (plugin == null) {
            return jails;
        }
        File file = new File(plugin.getDataFolder(), "jails.yml");
        if (!file.exists()) {
            return jails;
        }
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection section = yaml.getConfigurationSection("jails");
            if (section == null) {
                section = yaml.getConfigurationSection("Jails");
            }
            Iterable<String> keys = (section == null) ? yaml.getKeys(false) : section.getKeys(false);
            for (String key : keys) {
                jails.add(new JailInfo(key, capitalize(key)));
            }
        } catch (Throwable ignored) {
        }
        return jails;
    }

    @Override
    protected String formatDuration(long seconds) {
        return TimeUtil.toShortForm(seconds);
    }
}
