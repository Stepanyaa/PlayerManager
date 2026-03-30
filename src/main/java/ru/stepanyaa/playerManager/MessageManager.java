/**
 * MIT License
 *
 * PlayerManager
 * Copyright (c) 2026 Stepanyaa
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
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class MessageManager {

    private static final String MODRINTH_API_URL = "https://api.modrinth.com/v2/project/player_manager/version";
    private static final String CURRENT_VERSION = "1.1.2";
    private static final String[] SUPPORTED_LANGUAGES = {"en", "ru", "de", "fr", "tr", "pl", "pt"};;

    private final PlayerManager plugin;

    private String language;
    private File messagesFile;
    private FileConfiguration messagesConfig;

    private String latestVersion = null;
    private final Set<UUID> notifiedAdmins = new HashSet<>();

    private boolean isFirstEnable = true;

    public MessageManager(PlayerManager plugin) {
        this.plugin = plugin;
    }

    public void init() {
        loadLanguage();
        loadMessages();
        updateMessagesFiles();
        updateConfigFile();
        isFirstEnable = false;

    }

    public void reload() {
        loadLanguage();
        loadMessages();
        updateConfigFile();
        updateMessagesFiles();
    }

    private void loadLanguage() {
        this.language = plugin.getConfig().getString("language", "en");
        if (!Arrays.asList(SUPPORTED_LANGUAGES).contains(this.language)) {
            plugin.getLogger().warning(
                    "Unsupported language '" + this.language + "' in config.yml, defaulting to 'en'");
            this.language = "en";
        }
    }

    public String getLanguage() {
        return language;
    }

    public String getMessage(String key, String defaultValue) {
        String message = messagesConfig.getString(key, defaultValue);
        if (message == null || message.isEmpty()) {
            return ChatColor.translateAlternateColorCodes('&', defaultValue);
        }
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    private void loadMessages() {
        String fileName = "messages_" + language + ".yml";
        File langFolder = new File(plugin.getDataFolder(), "lang");
        if (!langFolder.exists()) langFolder.mkdirs();

        messagesFile = new File(langFolder, fileName);

        try {
            if (!messagesFile.exists()) {
                if (plugin.getResource("lang/" + fileName) != null) {
                    plugin.saveResource("lang/" + fileName, false);
                    plugin.getLogger().info("Created messages file: lang/" + fileName);
                } else {
                    plugin.getLogger().warning("Messages file lang/" + fileName + " not found in plugin jar!");
                    messagesConfig = new YamlConfiguration();
                    return;
                }
            }
            messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load messages file: " + e.getMessage());
            messagesConfig = new YamlConfiguration();
        }
    }

    public void updateMessagesFiles() {
        File langFolder = new File(plugin.getDataFolder(), "lang");
        if (!langFolder.exists()) langFolder.mkdirs();

        for (String lang : SUPPORTED_LANGUAGES) {
            String fileName = "messages_" + lang + ".yml";
            File file = new File(langFolder, fileName);

            if (!file.exists()) {
                if (plugin.getResource("lang/" + fileName) != null) {
                    plugin.saveResource("lang/" + fileName, false);
                    plugin.getLogger().info("Created messages file: lang/" + fileName);
                } else {
                    plugin.getLogger().warning("Resource lang/" + fileName + " not found in plugin!");
                    continue;
                }
            }

            YamlConfiguration existing = YamlConfiguration.loadConfiguration(file);
            String existingVersion = existing.getString("version", "0.0.0");

            if (existingVersion.equals(CURRENT_VERSION)) {
                if (isFirstEnable) {
                    plugin.getLogger().info("Messages file lang/" + fileName + " is up-to-date (version " + CURRENT_VERSION + ").");
                }
                continue;
            }

            if (plugin.getResource("lang/" + fileName) != null) {
                try {
                    plugin.saveResource("lang/" + fileName, true);
                    YamlConfiguration updated = YamlConfiguration.loadConfiguration(file);
                    updated.set("version", CURRENT_VERSION);
                    updated.save(file);
                    plugin.getLogger().info("Updated messages file lang/" + fileName + " to version " + CURRENT_VERSION);
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to update messages file " + fileName);
                }
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
                        String[] cur = CURRENT_VERSION.split("\\.");
                        String[] hi  = highest.split("\\.");
                        if (cur.length == 3 && hi.length == 3) {
                            int cMaj = Integer.parseInt(cur[0]);
                            int cMin = Integer.parseInt(cur[1]);
                            int cPat = Integer.parseInt(cur[2]);
                            int hMaj = Integer.parseInt(hi[0]);
                            int hMin = Integer.parseInt(hi[1]);
                            int hPat = Integer.parseInt(hi[2]);
                            if (cMaj == hMaj && cMin == hMin && hPat == cPat + 1) {
                                latestVersion = highest;
                                plugin.getLogger().warning(
                                        "*** UPDATE AVAILABLE *** A new version of PlayerManagers ("
                                                + latestVersion + ") is available at:\n"
                                                + "https://modrinth.com/plugin/player_manager/versions");
                            }
                        }
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
                + getMessage("update.available",
                "A new version of PlayerManager (%version%) is available at "
                        + "https://modrinth.com/plugin/player_manager/versions")
                .replace("%version%", latestVersion);

        notifiedAdmins.add(player.getUniqueId());
        Bukkit.getScheduler().runTaskLater(plugin, () -> player.sendMessage(msg), 20L);
    }

    private boolean isNewerVersion(String newVersion, String currentVersion) {
        try {
            String[] newParts = newVersion.replace("-SNAPSHOT", "").split("\\.");
            String[] curParts = currentVersion.replace("-SNAPSHOT", "").split("\\.");
            int length = Math.max(newParts.length, curParts.length);
            for (int i = 0; i < length; i++) {
                int nv = i < newParts.length ? Integer.parseInt(newParts[i]) : 0;
                int cv = i < curParts.length ? Integer.parseInt(curParts[i]) : 0;
                if (nv > cv) return true;
                if (nv < cv) return false;
            }
            return false;
        } catch (NumberFormatException e) {
            plugin.getLogger().warning(
                    "Invalid version format: newVersion=" + newVersion + ", currentVersion=" + currentVersion);
            return false;
        }
    }
}