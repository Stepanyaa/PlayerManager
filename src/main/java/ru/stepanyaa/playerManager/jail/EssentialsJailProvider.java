package ru.stepanyaa.playerManager.jail;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import ru.stepanyaa.playerManager.util.TimeUtil;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class EssentialsJailProvider extends CommandJailProvider {

    public EssentialsJailProvider(Plugin owner) {
        super(owner, "EssentialsX", "Essentials");
        commands("essentials:jail %player% %jail% %time%", "essentials:unjail %player%");
        permanentCommand("essentials:jail %player% %jail%");
        listCommand("essentials:jails");
        capabilities(true, true, true);
    }

    @Override
    public boolean isAvailable() {
        return plugin() != null;
    }

    private Plugin plugin() {
        Plugin essentials = Bukkit.getPluginManager().getPlugin("Essentials");
        if (essentials == null) {
            essentials = Bukkit.getPluginManager().getPlugin("EssentialsX");
        }
        return (essentials != null && essentials.isEnabled()) ? essentials : null;
    }

    @Override
    public List<JailInfo> getJails() {
        List<JailInfo> discovered = readFromApi();
        if (!discovered.isEmpty()) {
            return discovered;
        }
        return super.getJails();
    }

    @SuppressWarnings("unchecked")
    private List<JailInfo> readFromApi() {
        List<JailInfo> jails = new ArrayList<JailInfo>();
        Plugin essentials = plugin();
        if (essentials == null) {
            return jails;
        }
        try {
            Method getJails = essentials.getClass().getMethod("getJails");
            Object jailApi = getJails.invoke(essentials);
            if (jailApi == null) {
                return jails;
            }
            Method getList = null;
            for (String candidate : new String[]{"getList", "getJails", "getJailNames"}) {
                try {
                    getList = jailApi.getClass().getMethod(candidate);
                    break;
                } catch (NoSuchMethodException ignored) {
                }
            }
            if (getList == null) {
                return jails;
            }
            Object raw = getList.invoke(jailApi);
            if (raw instanceof Collection) {
                for (Object entry : (Collection<Object>) raw) {
                    if (entry != null) {
                        jails.add(new JailInfo(String.valueOf(entry), capitalize(String.valueOf(entry))));
                    }
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
