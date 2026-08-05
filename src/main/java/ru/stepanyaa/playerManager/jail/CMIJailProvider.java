package ru.stepanyaa.playerManager.jail;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import ru.stepanyaa.playerManager.util.TimeUtil;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

public class CMIJailProvider extends CommandJailProvider {

    public CMIJailProvider(Plugin owner) {
        super(owner, "CMI", "CMI");
        commands("cmi jail %player% %jail% %time% -s", "cmi unjail %player% -s");
        permanentCommand("cmi jail %player% %jail% -s");
        listCommand("cmi jaillist");
        capabilities(true, true, true);
    }

    @Override
    public List<JailInfo> getJails() {
        List<JailInfo> jails = readFromApi();
        if (jails.isEmpty()) {
            jails = readFromFile();
        }
        if (jails.isEmpty()) {
            return super.getJails();
        }
        return jails;
    }

    @SuppressWarnings("unchecked")
    private List<JailInfo> readFromApi() {
        List<JailInfo> jails = new ArrayList<JailInfo>();
        try {
            Class<?> cmiClass = Class.forName("com.Zrips.CMI.CMI");
            Method getInstance = cmiClass.getMethod("getInstance");
            Object cmi = getInstance.invoke(null);
            if (cmi == null) {
                return jails;
            }
            Method getJailManager = cmiClass.getMethod("getJailManager");
            Object manager = getJailManager.invoke(cmi);
            if (manager == null) {
                return jails;
            }
            Object raw = null;
            for (String candidate : new String[]{"getJails", "getJailsMap", "getJailList"}) {
                try {
                    raw = manager.getClass().getMethod(candidate).invoke(manager);
                    break;
                } catch (NoSuchMethodException ignored) {
                }
            }
            if (raw instanceof Map) {
                for (Object key : ((Map<Object, Object>) raw).keySet()) {
                    jails.add(new JailInfo(String.valueOf(key), capitalize(String.valueOf(key))));
                }
            } else if (raw instanceof Collection) {
                for (Object entry : (Collection<Object>) raw) {
                    String name = nameOf(entry);
                    if (name != null) {
                        jails.add(new JailInfo(name, capitalize(name)));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return jails;
    }

    private String nameOf(Object entry) {
        if (entry == null) {
            return null;
        }
        try {
            Method getName = entry.getClass().getMethod("getName");
            Object value = getName.invoke(entry);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable ignored) {
            return String.valueOf(entry);
        }
    }

    private List<JailInfo> readFromFile() {
        List<JailInfo> jails = new ArrayList<JailInfo>();
        Plugin cmi = Bukkit.getPluginManager().getPlugin("CMI");
        if (cmi == null) {
            return jails;
        }
        File file = new File(cmi.getDataFolder(), "jails.yml");
        if (!file.exists()) {
            return jails;
        }
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection section = yaml.getConfigurationSection("Jails");
            if (section == null) {
                section = yaml.getConfigurationSection("jails");
            }
            if (section == null) {
                for (String key : yaml.getKeys(false)) {
                    jails.add(new JailInfo(key, capitalize(key)));
                }
            } else {
                for (String key : section.getKeys(false)) {
                    jails.add(new JailInfo(key, capitalize(key)));
                }
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
