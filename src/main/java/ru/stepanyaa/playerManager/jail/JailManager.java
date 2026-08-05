package ru.stepanyaa.playerManager.jail;

import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

public class JailManager {

    private final Plugin plugin;
    private final List<JailProvider> providers = new ArrayList<JailProvider>();
    private JailProvider activeProvider;

    public JailManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        providers.clear();
        activeProvider = null;

        FileConfiguration config = plugin.getConfig();
        registerCustomProviders(config);
        registerBuiltInProviders();

        String forced = config.getString("jail.force-provider", "");
        if (forced != null && !forced.trim().isEmpty() && !forced.equalsIgnoreCase("auto")) {
            for (JailProvider provider : providers) {
                if (provider.getName().equalsIgnoreCase(forced.trim()) && provider.isAvailable()) {
                    activate(provider);
                    return;
                }
            }
            plugin.getLogger().warning("jail.force-provider = '" + forced
                    + "' is not installed, falling back to automatic detection.");
        }

        for (String preferred : priorityList(config)) {
            for (JailProvider provider : providers) {
                if (provider.getName().equalsIgnoreCase(preferred) && provider.isAvailable()) {
                    activate(provider);
                    return;
                }
            }
        }

        for (JailProvider provider : providers) {
            if (provider.isAvailable()) {
                activate(provider);
                return;
            }
        }

        plugin.getLogger().warning("No supported jail plugin found. (Не найдено ни одного поддерживаемого Jail-плагина.)");
    }

    private void activate(JailProvider provider) {
        this.activeProvider = provider;
        plugin.getLogger().info("Found " + provider.getName() + " (Нашёл " + provider.getName() + ")");
        plugin.getLogger().info("JailProvider loaded (Загружен JailProvider): "
                + provider.getClass().getSimpleName());

        int jailCount;
        try {
            jailCount = provider.getJails().size();
        } catch (Throwable throwable) {
            jailCount = 0;
            plugin.getLogger().log(Level.WARNING, "Could not read the jail list from " + provider.getName(), throwable);
        }
        plugin.getLogger().info("Jails detected: " + jailCount
                + " | timed=" + provider.supportsTimed()
                + " permanent=" + provider.supportsPermanent()
                + " offline=" + provider.supportsOffline());
    }

    private List<String> priorityList(FileConfiguration config) {
        List<String> priority = config.getStringList("jail.provider-priority");
        if (priority == null || priority.isEmpty()) {
            priority = new ArrayList<String>();
            priority.add("EssentialsX");
            priority.add("CMI");
            priority.add("DeluxeJails");
            priority.add("Jails");
            priority.add("JailSystem");
            priority.add("AdvancedBan");
            priority.add("LiteBans");
            priority.add("Prison");
        }
        return priority;
    }

    private void registerBuiltInProviders() {
        register(new EssentialsJailProvider(plugin));
        register(new CMIJailProvider(plugin));
        register(new DeluxeJailsProvider(plugin));
        register(new JailsProvider(plugin));
        register(new JailSystemProvider(plugin));
        register(new AdvancedBanJailProvider(plugin));
        register(new LiteBansJailProvider(plugin));
        register(new PrisonJailProvider(plugin));
        applyOverrides();
    }

    private void applyOverrides() {
        ConfigurationSection overrides = plugin.getConfig().getConfigurationSection("jail.overrides");
        if (overrides == null) {
            return;
        }
        for (String key : overrides.getKeys(false)) {
            ConfigurationSection section = overrides.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            for (JailProvider provider : providers) {
                if (!provider.getName().equalsIgnoreCase(key) || !(provider instanceof CommandJailProvider)) {
                    continue;
                }
                CommandJailProvider command = (CommandJailProvider) provider;
                String jailCommand = section.getString("jail-command", command.getJailCommand());
                String unjailCommand = section.getString("unjail-command", command.getUnjailCommand());
                command.commands(jailCommand, unjailCommand);
                if (section.contains("permanent-command")) {
                    command.permanentCommand(section.getString("permanent-command"));
                }
                if (section.contains("list-command")) {
                    command.listCommand(section.getString("list-command"));
                }
            }
        }
    }

    private void registerCustomProviders(FileConfiguration config) {
        ConfigurationSection custom = config.getConfigurationSection("jail.custom-jails");
        if (custom == null) {
            custom = config.getConfigurationSection("custom-jails");
        }
        if (custom != null) {
            for (String key : custom.getKeys(false)) {
                ConfigurationSection section = custom.getConfigurationSection(key);
                if (section == null) {
                    continue;
                }
                String jailCommand = section.getString("jail-command", section.getString("command", ""));
                if (jailCommand == null || jailCommand.trim().isEmpty()) {
                    plugin.getLogger().warning("Custom jail provider '" + key + "' has no jail-command, skipping.");
                    continue;
                }
                CustomJailProvider provider = new CustomJailProvider(
                        plugin,
                        section.getString("name", key),
                        emptyToNull(section.getString("plugin", "")),
                        jailCommand,
                        emptyToNull(section.getString("permanent-command", "")),
                        section.getString("unjail-command", ""),
                        section.getStringList("jails"),
                        section.getString("time-format", "short"),
                        section.getBoolean("supports-permanent", true),
                        section.getBoolean("supports-timed", true),
                        section.getBoolean("supports-offline", false));
                if (section.contains("list-command")) {
                    provider.listCommand(section.getString("list-command"));
                }
                register(provider);
            }
        }

        List<Map<?, ?>> simple = config.getMapList("jail.providers");
        if (simple == null || simple.isEmpty()) {
            simple = config.getMapList("providers");
        }
        if (simple != null) {
            for (Map<?, ?> entry : simple) {
                Map<String, Object> values = normalize(entry);
                String name = stringOf(values.get("name"));
                String command = stringOf(values.get("command"));
                if (name.isEmpty() || command.isEmpty()) {
                    continue;
                }
                CustomJailProvider provider = new CustomJailProvider(
                        plugin,
                        name,
                        emptyToNull(stringOf(values.get("plugin"))),
                        command,
                        emptyToNull(stringOf(values.get("permanent-command"))),
                        stringOf(values.get("unjail-command")),
                        stringListOf(values.get("jails")),
                        stringOf(values.get("time-format")),
                        true,
                        true,
                        false);
                register(provider);
            }
        }
    }

    private void register(JailProvider provider) {
        providers.add(provider);
    }


    public JailProvider getActiveProvider() {
        return activeProvider;
    }

    public boolean hasProvider() {
        return activeProvider != null && activeProvider.isAvailable();
    }

    public String getActiveProviderName() {
        return activeProvider == null ? "none" : activeProvider.getName();
    }

    public List<JailProvider> getProviders() {
        return new ArrayList<JailProvider>(providers);
    }

    public List<JailInfo> getJails() {
        if (!hasProvider()) {
            return new ArrayList<JailInfo>();
        }
        try {
            List<JailInfo> jails = activeProvider.getJails();
            return jails == null ? new ArrayList<JailInfo>() : new ArrayList<JailInfo>(jails);
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.WARNING, "Failed to read jails from " + getActiveProviderName(), throwable);
            return new ArrayList<JailInfo>();
        }
    }

    public boolean jailPlayer(CommandSender executor, OfflinePlayer target, String jail, long durationSeconds, String reason) {
        if (!hasProvider()) {
            return false;
        }
        if (durationSeconds <= 0L && !activeProvider.supportsPermanent()) {
            return false;
        }
        if (durationSeconds > 0L && !activeProvider.supportsTimed()) {
            return false;
        }
        if (!target.isOnline() && !activeProvider.supportsOffline()) {
            plugin.getLogger().info(getActiveProviderName()
                    + " cannot jail offline players; the punishment will still be dispatched.");
        }
        try {
            return activeProvider.jailPlayer(executor, target, jail, durationSeconds, reason);
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.WARNING, "Jail request failed", throwable);
            return false;
        }
    }

    public boolean unjailPlayer(CommandSender executor, OfflinePlayer target) {
        if (!hasProvider()) {
            return false;
        }
        try {
            return activeProvider.unjailPlayer(executor, target);
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.WARNING, "Unjail request failed", throwable);
            return false;
        }
    }

    private static String emptyToNull(String value) {
        return (value == null || value.trim().isEmpty()) ? null : value.trim();
    }

    private static String stringOf(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringListOf(Object value) {
        List<String> list = new ArrayList<String>();
        if (value instanceof List) {
            for (Object entry : (List<Object>) value) {
                if (entry != null) {
                    list.add(String.valueOf(entry));
                }
            }
        } else if (value instanceof String) {
            for (String part : ((String) value).split(",")) {
                list.add(part.trim());
            }
        }
        return list;
    }

    private static Map<String, Object> normalize(Map<?, ?> raw) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() != null) {
                values.put(String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT), entry.getValue());
            }
        }
        return values;
    }
}
