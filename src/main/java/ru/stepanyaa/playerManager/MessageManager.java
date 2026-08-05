/**
 * MIT License
 *
 * PlayerManager
 * Copyright (c) 2025 Stepanyaa
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package ru.stepanyaa.playerManager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MessageManager {

    private static final String MODRINTH_API_URL = "https://api.modrinth.com/v2/project/player_manager/version";
    private static final String CURRENT_VERSION = "2.0.0";
    private static final String[] SUPPORTED_LOCALES =
            {"en_us", "ru_ru", "de_de", "fr_fr", "tr_tr", "pl_pl", "pt_br"};
    private static final Map<String, String> LEGACY_FILES = new LinkedHashMap<>();
    private static final Map<String, String> LANGUAGE_FALLBACK = new HashMap<>();

    static {
        LEGACY_FILES.put("messages_en.yml", "en_us.yml");
        LEGACY_FILES.put("messages_ru.yml", "ru_ru.yml");
        LEGACY_FILES.put("messages_de.yml", "de_de.yml");
        LEGACY_FILES.put("messages_fr.yml", "fr_fr.yml");
        LEGACY_FILES.put("messages_tr.yml", "tr_tr.yml");
        LEGACY_FILES.put("messages_pl.yml", "pl_pl.yml");
        LEGACY_FILES.put("messages_pt.yml", "pt_br.yml");

        LANGUAGE_FALLBACK.put("en", "en_us");
        LANGUAGE_FALLBACK.put("ru", "ru_ru");
        LANGUAGE_FALLBACK.put("be", "ru_ru");
        LANGUAGE_FALLBACK.put("uk", "ru_ru");
        LANGUAGE_FALLBACK.put("kk", "ru_ru");
        LANGUAGE_FALLBACK.put("de", "de_de");
        LANGUAGE_FALLBACK.put("fr", "fr_fr");
        LANGUAGE_FALLBACK.put("tr", "tr_tr");
        LANGUAGE_FALLBACK.put("pl", "pl_pl");
        LANGUAGE_FALLBACK.put("pt", "pt_br");
    }

    private final PlayerManager plugin;

    private String defaultLocale = "en_us";
    private boolean autoLanguage = true;

    private final Map<String, FileConfiguration> locales = new LinkedHashMap<>();
    private final Map<UUID, String> playerLocales = new ConcurrentHashMap<>();

    private UUID viewer;

    private String latestVersion = null;
    private final Set<UUID> notifiedAdmins = new HashSet<>();

    private boolean isFirstEnable = true;

    public MessageManager(PlayerManager plugin) {
        this.plugin = plugin;
    }

    public void init() {
        migrateLegacyFiles();
        loadSettings();
        loadLocales();
        updateMessagesFiles();
        updateConfigFile();
        isFirstEnable = false;
    }

    public void reload() {
        migrateLegacyFiles();
        loadSettings();
        loadLocales();
        updateConfigFile();
        updateMessagesFiles();
        cacheOnlinePlayerLocales();
    }

    private void loadSettings() {
        String configured = plugin.getConfig().getString("language", "auto");
        if (configured == null || configured.trim().isEmpty()) {
            configured = "auto";
        }
        configured = configured.trim().toLowerCase(Locale.ROOT);

        this.autoLanguage = "auto".equals(configured)
                || plugin.getConfig().getBoolean("auto-language", true);

        String fallback = plugin.getConfig().getString("fallback-language", "en_us");
        if ("auto".equals(configured)) {
            this.defaultLocale = normalise(fallback, "en_us");
        } else {
            this.defaultLocale = normalise(configured, normalise(fallback, "en_us"));
        }
    }

    private String normalise(String raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (value.isEmpty() || "auto".equals(value)) {
            return fallback;
        }
        if (isShipped(value) || new File(langFolder(), value + ".yml").exists()) {
            return value;
        }
        String language = value.contains("_") ? value.substring(0, value.indexOf('_')) : value;
        String mapped = LANGUAGE_FALLBACK.get(language);
        if (mapped != null) {
            return mapped;
        }
        for (File file : listLocaleFiles()) {
            String name = file.getName().toLowerCase(Locale.ROOT).replace(".yml", "");
            if (name.startsWith(language + "_") || name.equals(language)) {
                return name;
            }
        }
        return fallback;
    }

    private boolean isShipped(String locale) {
        return Arrays.asList(SUPPORTED_LOCALES).contains(locale);
    }

    private File langFolder() {
        File folder = new File(plugin.getDataFolder(), "lang");
        if (!folder.exists()) {
            folder.mkdirs();
        }
        return folder;
    }

    private File[] listLocaleFiles() {
        File[] files = langFolder().listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        return files == null ? new File[0] : files;
    }

    private void migrateLegacyFiles() {
        File folder = langFolder();
        for (Map.Entry<String, String> entry : LEGACY_FILES.entrySet()) {
            File old = new File(folder, entry.getKey());
            if (!old.exists()) {
                continue;
            }
            File renamed = new File(folder, entry.getValue());
            if (renamed.exists()) {
                if (!old.renameTo(new File(folder, entry.getKey() + ".old"))) {
                    old.delete();
                }
                continue;
            }
            if (old.renameTo(renamed)) {
                plugin.getLogger().info("Migrated lang/" + entry.getKey() + " to lang/" + entry.getValue());
            }
        }
    }

    private void loadLocales() {
        locales.clear();
        File folder = langFolder();

        for (String locale : SUPPORTED_LOCALES) {
            File file = new File(folder, locale + ".yml");
            if (!file.exists() && plugin.getResource("lang/" + locale + ".yml") != null) {
                plugin.saveResource("lang/" + locale + ".yml", false);
            }
        }

        for (File file : listLocaleFiles()) {
            String locale = file.getName().toLowerCase(Locale.ROOT).replace(".yml", "");
            try {
                locales.put(locale, YamlConfiguration.loadConfiguration(file));
            } catch (Exception exception) {
                plugin.getLogger().warning("Failed to load lang/" + file.getName() + ": " + exception.getMessage());
            }
        }

        if (!locales.containsKey(defaultLocale)) {
            plugin.getLogger().warning("Language file lang/" + defaultLocale
                    + ".yml is missing, falling back to en_us.");
            defaultLocale = "en_us";
            if (!locales.containsKey("en_us")) {
                locales.put("en_us", new YamlConfiguration());
            }
        }
    }

    public boolean isAutoLanguage() {
        return autoLanguage;
    }

    public String getLanguage() {
        return defaultLocale;
    }

    public String getLocale(Player player) {
        if (player == null) {
            return defaultLocale;
        }
        if (!autoLanguage) {
            return defaultLocale;
        }
        String cached = playerLocales.get(player.getUniqueId());
        if (cached != null) {
            return cached;
        }
        return cacheLocale(player);
    }

    public String cacheLocale(Player player) {
        if (player == null) {
            return defaultLocale;
        }
        String raw = readClientLocale(player);
        String locale = normalise(raw, defaultLocale);
        playerLocales.put(player.getUniqueId(), locale);
        return locale;
    }

    public void forget(UUID uuid) {
        playerLocales.remove(uuid);
    }

    public void cacheOnlinePlayerLocales() {
        playerLocales.clear();
        for (Player online : Bukkit.getOnlinePlayers()) {
            cacheLocale(online);
        }
    }

    private String readClientLocale(Player player) {
        try {
            return player.getLocale();
        } catch (Throwable ignored) {
        }
        try {
            Object spigot = player.spigot();
            Method method = spigot.getClass().getMethod("getLocale");
            Object value = method.invoke(spigot);
            if (value != null) {
                return value.toString();
            }
        } catch (Throwable ignored) {
        }
        return defaultLocale;
    }

    public void setViewer(CommandSender sender) {
        if (sender instanceof Player) {
            this.viewer = ((Player) sender).getUniqueId();
        } else {
            this.viewer = null;
        }
    }

    public void clearViewer() {
        this.viewer = null;
    }

    public String getMessage(String key, String defaultValue) {
        String locale = defaultLocale;
        if (autoLanguage && viewer != null) {
            String cached = playerLocales.get(viewer);
            if (cached != null) {
                locale = cached;
            }
        }
        return lookup(locale, key, defaultValue);
    }

    public String getMessage(CommandSender sender, String key, String defaultValue) {
        if (sender instanceof Player) {
            return lookup(getLocale((Player) sender), key, defaultValue);
        }
        return lookup(defaultLocale, key, defaultValue);
    }

    public String getMessageFor(String locale, String key, String defaultValue) {
        return lookup(normalise(locale, defaultLocale), key, defaultValue);
    }

    public List<String> getList(String key) {
        FileConfiguration config = locales.get(defaultLocale);
        if (config != null && config.isList(key)) {
            return config.getStringList(key);
        }
        return java.util.Collections.emptyList();
    }

    private String lookup(String locale, String key, String defaultValue) {
        String message = readValue(locales.get(locale), key);
        if (message == null) {
            message = readValue(locales.get(defaultLocale), key);
        }
        if (message == null) {
            message = readValue(locales.get("en_us"), key);
        }
        if (message == null) {
            message = defaultValue == null ? key : defaultValue;
        }
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    private String readValue(FileConfiguration config, String key) {
        if (config == null || key == null) {
            return null;
        }
        Object value = config.get(key);
        if (value instanceof ConfigurationSection || value instanceof Map || value instanceof List) {
            value = config.get(key + ".name");
        }
        if (value == null
                || value instanceof ConfigurationSection
                || value instanceof Map
                || value instanceof List) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isEmpty() ? null : text;
    }

    private boolean migrateLegacyKeys(YamlConfiguration existing) {
        boolean changed = false;
        changed |= moveSection(existing, "filter.recently-left", "filter.recently-left-categories", true);
        changed |= moveSection(existing, "action.action", "action", true);
        changed |= moveSection(existing, "gui.ban.duration", "ban.duration", false);
        if (existing.isConfigurationSection("gui.ban")) {
            existing.set("gui.ban", null);
            changed = true;
        }
        if (existing.isConfigurationSection("gui.reason") && existing.isString("gui.reason.name")) {
            existing.set("gui.reason-label", existing.getString("gui.reason.name"));
            existing.set("gui.reason.name", null);
            changed = true;
        }
        return changed;
    }

    private boolean moveSection(YamlConfiguration existing, String from, String to, boolean removeSource) {
        if (!existing.isConfigurationSection(from)) {
            return false;
        }
        ConfigurationSection section = existing.getConfigurationSection(from);
        if (section != null) {
            for (String childKey : section.getKeys(false)) {
                Object value = section.get(childKey);
                if (value instanceof ConfigurationSection || value == null) {
                    continue;
                }
                String target = to + "." + childKey;
                if (!existing.contains(target)) {
                    existing.set(target, value);
                }
            }
        }
        if (removeSource) {
            existing.set(from, null);
        }
        return true;
    }

    public void updateMessagesFiles() {
        File folder = langFolder();

        for (String locale : SUPPORTED_LOCALES) {
            String resource = "lang/" + locale + ".yml";
            File file = new File(folder, locale + ".yml");

            if (!file.exists()) {
                if (plugin.getResource(resource) != null) {
                    plugin.saveResource(resource, false);
                    plugin.getLogger().info("Created messages file: " + resource);
                }
                continue;
            }

            InputStream stream = plugin.getResource(resource);
            if (stream == null) {
                continue;
            }

            YamlConfiguration existing = YamlConfiguration.loadConfiguration(file);
            YamlConfiguration bundled = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));

            boolean migrated = migrateLegacyKeys(existing);
            boolean upToDate = CURRENT_VERSION.equals(existing.getString("version", "0.0.0"));
            int added = 0;
            for (String key : bundled.getKeys(true)) {
                if (bundled.isConfigurationSection(key)) {
                    continue;
                }
                if (!existing.contains(key)) {
                    existing.set(key, bundled.get(key));
                    added++;
                }
            }

            if (added == 0 && !migrated && upToDate) {
                if (isFirstEnable) {
                    plugin.getLogger().info("Messages file lang/" + locale
                            + ".yml is up-to-date (version " + CURRENT_VERSION + ").");
                }
                continue;
            }

            existing.set("version", CURRENT_VERSION);
            try {
                existing.save(file);
                locales.put(locale, YamlConfiguration.loadConfiguration(file));
                plugin.getLogger().info("Updated lang/" + locale + ".yml to version "
                        + CURRENT_VERSION + " (" + added + " new keys).");
            } catch (IOException exception) {
                plugin.getLogger().warning("Failed to update lang/" + locale + ".yml: " + exception.getMessage());
            }
        }
    }

    public void updateConfigFile() {
        File configFile = new File(plugin.getDataFolder(), "config.yml");

        if (!configFile.exists()) {
            plugin.saveResource("config.yml", false);
            plugin.getLogger().info(getMessage(
                    "warning.config-file-create", "Created config file: config.yml"));
            return;
        }

        FileConfiguration current = YamlConfiguration.loadConfiguration(configFile);
        String currentVersion = current.getString("config-version", "0");

        InputStream defaultStream = plugin.getResource("config.yml");
        if (defaultStream == null) {
            plugin.getLogger().warning(getMessage(
                    "warning.config-file-not-found", "Resource config.yml not found in plugin!"));
            return;
        }

        FileConfiguration defaultCfg = YamlConfiguration.loadConfiguration(
                new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
        String defaultVersion = defaultCfg.getString("config-version", CURRENT_VERSION);

        if (currentVersion.equals(defaultVersion)) {
            plugin.getLogger().info(getMessage(
                    "warning.config-file-up-to-date",
                    "Config file config.yml is up-to-date (version %version%).")
                    .replace("%version%", defaultVersion));
            return;
        }

        for (String key : defaultCfg.getKeys(true)) {
            if (!current.contains(key)) {
                current.set(key, defaultCfg.get(key));
            }
        }
        current.set("config-version", defaultVersion);

        try {
            current.save(configFile);
            plugin.getLogger().info(getMessage(
                    "warning.config-file-updated", "Updated config.yml to version %version%.")
                    .replace("%version%", defaultVersion));
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to update config.yml: " + e.getMessage());
        }
    }

    public void checkForUpdates() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                URL url = new URL(MODRINTH_API_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "PlayerManager/" + CURRENT_VERSION);
                conn.connect();

                if (conn.getResponseCode() == 200) {
                    JsonArray versions = JsonParser
                            .parseReader(new InputStreamReader(conn.getInputStream()))
                            .getAsJsonArray();

                    String highest = null;
                    for (JsonElement el : versions) {
                        String num  = el.getAsJsonObject().get("version_number").getAsString();
                        String type = el.getAsJsonObject().get("version_type").getAsString();
                        if (num.contains("-SNAPSHOT") && !type.equals("release")) continue;
                        if (highest == null || isNewerVersion(num, highest)) {
                            highest = num;
                        }
                    }

                    if (highest != null && isNewerVersion(highest, CURRENT_VERSION)) {
                        latestVersion = highest;
                        plugin.getLogger().warning(
                                "*** UPDATE AVAILABLE *** A new version of PlayerManager ("
                                        + latestVersion + ") is available at:\n"
                                        + "https://modrinth.com/plugin/player_manager/versions");
                    }
                }
                conn.disconnect();
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to check for updates: " + e.getMessage());
            }
        });
    }

    public void notifyUpdateIfAvailable(Player player) {
        if (latestVersion == null) return;
        if (!player.hasPermission("playermanager.admin") && !player.hasPermission("playermanager.updates")) return;
        if (notifiedAdmins.contains(player.getUniqueId())) return;

        String msg = ChatColor.YELLOW + "" + ChatColor.BOLD
                + getMessage(player, "update.available",
                "A new version of PlayerManager (%version%) is available at "
                        + "https://modrinth.com/plugin/player_manager/versions")
                .replace("%version%", latestVersion);

        notifiedAdmins.add(player.getUniqueId());
        Bukkit.getScheduler().runTaskLater(plugin, () -> player.sendMessage(msg), 20L);
    }

    private boolean isNewerVersion(String newVersion, String currentVersion) {
        try {
            String[] newParts = newVersion.replace("-SNAPSHOT", "").replace("v", "").split("\\.");
            String[] curParts = currentVersion.replace("-SNAPSHOT", "").replace("v", "").split("\\.");
            int length = Math.max(newParts.length, curParts.length);
            for (int i = 0; i < length; i++) {
                int nv = i < newParts.length ? Integer.parseInt(newParts[i].trim()) : 0;
                int cv = i < curParts.length ? Integer.parseInt(curParts[i].trim()) : 0;
                if (nv > cv) return true;
                if (nv < cv) return false;
            }
            return false;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
