/**
 * MIT License
 *
 * PlayerManager
 * Copyright (c) 2025 Stepanyaa

 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:

 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.

 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package ru.stepanyaa.playerManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

public class PlayerManager extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private File playerDataFile;
    private FileConfiguration playerDataConfig;
    private File messagesFile;
    private FileConfiguration messagesConfig;
    private PlayerSearchGUI playerSearchGUI;
    private String language;
    private static final String MODRINTH_API_URL = "https://api.modrinth.com/v2/project/playermanagers/version";
    private static final String CURRENT_VERSION = "1.0.7";
    private String latestVersion = null;
    private final Set<UUID> notifiedAdmins = new HashSet<>();
    private static final String[] SUPPORTED_LANGUAGES = {"en", "ru"};
    private final List<String> cachedPlayerNames = new ArrayList<>();
    private final Set<String> adminUUIDs = new HashSet<>();
    public final Map<UUID, BiConsumer<String, Player>> pendingActions = new HashMap<>();
    private boolean isFirstEnable = true;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        this.reloadConfig();
        this.language = getConfig().getString("language", "en");
        if (!Arrays.asList(SUPPORTED_LANGUAGES).contains(this.language)) {
            getLogger().warning("Unsupported language '" + this.language + "' in config.yml, defaulting to 'en'");
            this.language = "en";
        }
        this.playerDataFile = new File(this.getDataFolder(), "player_data.yml");
        if (!this.playerDataFile.exists()) {
            this.playerDataFile.getParentFile().mkdirs();
            this.saveResource("player_data.yml", false);
        }
        this.playerDataConfig = YamlConfiguration.loadConfiguration(this.playerDataFile);
        if (!this.playerDataFile.canWrite()) {
            getLogger().severe("Cannot write to player_data.yml! Check file permissions in " + this.playerDataFile.getAbsolutePath());
        }
        if (this.playerDataConfig.getConfigurationSection("players") == null) {
            this.playerDataConfig.createSection("players");
            this.savePlayerDataConfig();
        }
        this.loadMessages();
        this.updateMessagesFiles();
        this.updateConfigFile();
        this.playerSearchGUI = new PlayerSearchGUI(this);
        this.getServer().getPluginManager().registerEvents(this, this);
        this.getServer().getPluginManager().registerEvents(this.playerSearchGUI, this);
        PluginCommand command = this.getCommand("playermanager");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
            command.setPermissionMessage(ChatColor.RED + getMessage("error.no-permission", "You don't have permission!"));
        } else {
            getLogger().warning(getMessage("warning.command-register-fail", "Failed to register command 'playermanager'!"));
        }
        cachedPlayerNames.clear();
        for (OfflinePlayer offlinePlayer : Bukkit.getOfflinePlayers()) {
            String name = offlinePlayer.getName();
            if (name != null && !name.isEmpty()) {
                cachedPlayerNames.add(name.toLowerCase());
            }
        }
        cachedPlayerNames.sort(String.CASE_INSENSITIVE_ORDER);
        getLogger().info("Initialized player name cache: " + cachedPlayerNames.size() + " players");

        adminUUIDs.clear();
        adminUUIDs.addAll(getConfig().getStringList("admin-uuids"));

        java.util.logging.Logger authlibLogger = java.util.logging.Logger.getLogger("com.mojang.authlib");
        authlibLogger.setLevel(java.util.logging.Level.WARNING);

        for (Player player : Bukkit.getOnlinePlayers()) {
            this.loadPlayerMenuState(player);
            this.updatePlayerDataOnJoin(player);
        }
        checkForUpdates();
        checkConfigInactivityPeriod();
        schedulePlayerDataCleanup();
        getLogger().info(getMessage("warning.plugin-enabled", "PlayerManager enabled with language: %lang%")
                .replace("%lang%", language));

        this.isFirstEnable = false;
    }

    private void schedulePlayerDataCleanup() {

    }


    private void checkConfigInactivityPeriod() {
        long maxInactivityDays = getConfig().getLong("max-inactivity-days", 30);
        if (maxInactivityDays > 365) {
            getLogger().severe(ChatColor.RED + getMessage("warning.max-inactivity-too-high", "Warning: max-inactivity-days (%days%) is set too high! Recommended maximum is 365 days.")
                    .replace("%days%", String.valueOf(maxInactivityDays)));
        }
    }

    private void checkForUpdates() {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                URL url = new URL(MODRINTH_API_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "PlayerManager/" + CURRENT_VERSION);
                conn.connect();
                if (conn.getResponseCode() == 200) {
                    JsonArray versions = JsonParser.parseReader(new InputStreamReader(conn.getInputStream())).getAsJsonArray();
                    String highestVersion = null;
                    for (JsonElement element : versions) {
                        String versionNumber = element.getAsJsonObject().get("version_number").getAsString();
                        String versionType = element.getAsJsonObject().get("version_type").getAsString();
                        if (versionNumber.contains("-SNAPSHOT") && !versionType.equals("release")) {
                            continue;
                        }
                        if (highestVersion == null || isNewerVersion(versionNumber, highestVersion)) {
                            highestVersion = versionNumber;
                        }
                    }
                    if (highestVersion != null && isNewerVersion(highestVersion, CURRENT_VERSION)) {
                        String[] currentParts = CURRENT_VERSION.split("\\.");
                        String[] highestParts = highestVersion.split("\\.");
                        if (currentParts.length == 3 && highestParts.length == 3) {
                            int currentMajor = Integer.parseInt(currentParts[0]);
                            int currentMinor = Integer.parseInt(currentParts[1]);
                            int currentPatch = Integer.parseInt(currentParts[2]);
                            int highestMajor = Integer.parseInt(highestParts[0]);
                            int highestMinor = Integer.parseInt(highestParts[1]);
                            int highestPatch = Integer.parseInt(highestParts[2]);
                            if (currentMajor == highestMajor && currentMinor == highestMinor && highestPatch == currentPatch + 1) {
                                latestVersion = highestVersion;
                                String consoleMessage = "*** UPDATE AVAILABLE *** A new version of PlayerManager (" + latestVersion + ") is available at https://modrinth.com/plugin/playermanagers/versions";
                                getLogger().info(consoleMessage);
                            }
                        }
                    }
                }
                conn.disconnect();
            } catch (Exception e) {
                getLogger().warning("Failed to check for updates: " + e.getMessage());
            }
        });
    }

    private void updateMessagesFiles() {
        for (String lang : SUPPORTED_LANGUAGES) {
            String fileName = "messages_" + lang + ".yml";
            File messageFile = new File(getDataFolder(), fileName);
            if (!messageFile.exists()) {
                if (getResource(fileName) != null) {
                    saveResource(fileName, false);
                    getLogger().info(getMessage("warning.messages-file-create", "Created messages file: %file%")
                            .replace("%file%", fileName));
                } else {
                    getLogger().warning(getMessage("warning.messages-file-not-found", "Messages file %file% not found in plugin!")
                            .replace("%file%", fileName));
                    continue;
                }
            }
            YamlConfiguration existingConfig = YamlConfiguration.loadConfiguration(messageFile);
            String currentFileVersion = existingConfig.getString("version", "0.0.0");
            if (currentFileVersion.equals(CURRENT_VERSION)) {
                if (isFirstEnable) {
                    getLogger().info(getMessage("warning.messages-file-up-to-date", "Messages file %file% is up-to-date (version %version%).")
                            .replace("%file%", fileName)
                            .replace("%version%", CURRENT_VERSION));
                }
                continue;
            }
            if (getResource(fileName) != null) {
                try {
                    saveResource(fileName, true);
                    getLogger().info(getMessage("warning.messages-file-updated", "Updated messages file %file% to version %version%.")
                            .replace("%file%", fileName)
                            .replace("%version%", CURRENT_VERSION));
                    YamlConfiguration newConfig = YamlConfiguration.loadConfiguration(messageFile);
                    newConfig.set("version", CURRENT_VERSION);
                    newConfig.save(messageFile);
                } catch (Exception e) {
                    getLogger().warning("Failed to update messages file " + fileName + ": " + e.getMessage());
                }
            } else {
                getLogger().warning(getMessage("warning.messages-file-not-found", "Messages file %file% not found in plugin!")
                        .replace("%file%", fileName));
            }
        }
    }

    private void updateConfigFile() {
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            saveResource("config.yml", false);
            getLogger().info(getMessage("warning.config-file-create", "Created config file: config.yml"));
        }
        YamlConfiguration existingConfig = YamlConfiguration.loadConfiguration(configFile);
        String currentFileVersion = existingConfig.getString("config-version", "0.0.0");
        if (currentFileVersion.equals(CURRENT_VERSION)) {
            if (isFirstEnable) {
                getLogger().info(getMessage("warning.config-file-up-to-date", "Config file config.yml is up-to-date (version %version%).")
                        .replace("%version%", CURRENT_VERSION));
            }
            return;
        }
        if (getResource("config.yml") != null) {
            try {
                saveResource("config.yml", true);
                getLogger().info(getMessage("warning.config-file-updated", "Updated config.yml to version %version%.")
                        .replace("%version%", CURRENT_VERSION));
                YamlConfiguration newConfig = YamlConfiguration.loadConfiguration(configFile);
                newConfig.set("config-version", CURRENT_VERSION);
                newConfig.save(configFile);
            } catch (Exception e) {
                getLogger().warning("Failed to update config.yml: " + e.getMessage());
            }
        } else {
            getLogger().warning(getMessage("warning.config-file-not-found", "Resource config.yml not found in plugin!"));
        }
    }

    private boolean isNewerVersion(String newVersion, String currentVersion) {
        try {
            String cleanNewVersion = newVersion.replace("-SNAPSHOT", "");
            String cleanCurrentVersion = currentVersion.replace("-SNAPSHOT", "");
            String[] newParts = cleanNewVersion.split("\\.");
            String[] currentParts = cleanCurrentVersion.split("\\.");
            int length = Math.max(newParts.length, currentParts.length);
            for (int i = 0; i < length; i++) {
                int newPart = i < newParts.length ? Integer.parseInt(newParts[i]) : 0;
                int currentPart = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;
                if (newPart > currentPart) return true;
                if (newPart < currentPart) return false;
            }
            return false;
        } catch (NumberFormatException e) {
            getLogger().warning("Invalid version format: newVersion=" + newVersion + ", currentVersion=" + currentVersion);
            return false;
        }
    }

    public boolean isAdminPlayer(Player player) {
        return player.hasPermission("playermanager.admin") ||
                player.hasPermission("playermanager.gui");
    }

    public boolean isAdminPlayer(OfflinePlayer offlinePlayer) {
        if (offlinePlayer.isOnline()) {
            return offlinePlayer.getPlayer().hasPermission("playermanager.admin") ||
                    offlinePlayer.getPlayer().hasPermission("playermanager.gui");
        }
        return adminUUIDs.contains(offlinePlayer.getUniqueId().toString());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("playermanager.admin") && !hasAnyPermission(sender)) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            List<String> commands = new ArrayList<>();
            if (sender.hasPermission("playermanager.admin") || sender.hasPermission("playermanager.gui")) {
                commands.add("gui");
            }
            if (sender.hasPermission("playermanager.admin") || sender.hasPermission("playermanager.reload")) {
                commands.add("reload");
            }
            if (sender.hasPermission("playermanager.admin") || sender.hasPermission("playermanager.gui") || sender.hasPermission("playermanager.reset")) {
                commands.add("reset");
            }
            if (sender.hasPermission("playermanager.admin") || sender.hasPermission("playermanager.ban")) {
                commands.add("ban");
            }
            if (sender.hasPermission("playermanager.admin") || sender.hasPermission("playermanager.kick")) {
                commands.add("kick");
            }
            if (sender.hasPermission("playermanager.admin") || sender.hasPermission("playermanager.warn")) {
                commands.add("warn");
            }
            if (sender.hasPermission("playermanager.admin") || sender.hasPermission("playermanager.mute")) {
                commands.add("mute");
            }
            if (sender.hasPermission("playermanager.admin") || sender.hasPermission("playermanager.unban")) {
                commands.add("unban");
            }
            if (sender.hasPermission("playermanager.admin") || sender.hasPermission("playermanager.teleport")) {
                commands.add("teleport");
            }
            if (sender.hasPermission("playermanager.admin") || sender.hasPermission("playermanager.inventory")) {
                commands.add("inventory");
            }
            return commands.stream()
                    .filter(cmd -> cmd.toLowerCase().startsWith(args[0].toLowerCase()))
                    .sorted()
                    .collect(Collectors.toList());
        } else if (args.length == 2 && Arrays.asList("ban", "kick", "warn", "mute", "unban", "teleport", "inventory").contains(args[0].toLowerCase())) {
            String action = args[0].toLowerCase();
            if (sender.hasPermission("playermanager.admin") || sender.hasPermission("playermanager." + action)) {
                String partialName = args[1].toLowerCase();
                List<String> matches = new ArrayList<>();
                if (action.equals("ban")) {
                    for (String name : cachedPlayerNames) {
                        if (name.startsWith(partialName) && !adminUUIDs.contains(getUUIDForName(name))) {
                            matches.add(name.substring(0, 1).toUpperCase() + name.substring(1));
                        }
                    }
                } else if (action.equals("unban")) {
                    ConfigurationSection playersSection = playerDataConfig.getConfigurationSection("players");
                    if (playersSection != null) {
                        for (String uuid : playersSection.getKeys(false)) {
                            if (playerDataConfig.getBoolean("players." + uuid + ".banned", false)) {
                                String name = playerDataConfig.getString("players." + uuid + ".name", "");
                                if (!name.isEmpty() && name.toLowerCase().startsWith(partialName) && !adminUUIDs.contains(uuid)) {
                                    matches.add(name.substring(0, 1).toUpperCase() + name.substring(1));
                                }
                            }
                        }
                    }
                } else {
                    for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                        String name = onlinePlayer.getName().toLowerCase();
                        if (name.startsWith(partialName) && !adminUUIDs.contains(onlinePlayer.getUniqueId().toString())) {
                            matches.add(onlinePlayer.getName());
                        }
                    }
                }
                if (matches.size() > 50) {
                    matches = matches.subList(0, 50);
                }
                return matches;
            }
        } else if (args.length == 3 && Arrays.asList("ban", "warn", "mute").contains(args[0].toLowerCase())) {
            return Collections.singletonList("<reason>");
        }
        return Collections.emptyList();
    }

    private String getUUIDForName(String name) {
        String path = "players.";
        ConfigurationSection playersSection = playerDataConfig.getConfigurationSection("players");
        if (playersSection != null) {
            for (String uuid : playersSection.getKeys(false)) {
                if (name.equalsIgnoreCase(playerDataConfig.getString(path + uuid + ".name"))) {
                    return uuid;
                }
            }
        }
        return null;
    }

    private boolean hasAnyPermission(CommandSender sender) {
        return sender.hasPermission("playermanager.gui") ||
                sender.hasPermission("playermanager.reload") ||
                sender.hasPermission("playermanager.reset") ||
                sender.hasPermission("playermanager.ban") ||
                sender.hasPermission("playermanager.kick") ||
                sender.hasPermission("playermanager.warn") ||
                sender.hasPermission("playermanager.mute") ||
                sender.hasPermission("playermanager.unban") ||
                sender.hasPermission("playermanager.teleport") ||
                sender.hasPermission("playermanager.inventory");
    }

    private void loadMessages() {
        String messagesFileName = "messages_" + language + ".yml";
        messagesFile = new File(getDataFolder(), messagesFileName);
        try {
            if (!messagesFile.exists()) {
                if (getResource(messagesFileName) != null) {
                    saveResource(messagesFileName, false);
                    getLogger().info("Created messages file: " + messagesFileName);
                } else {
                    getLogger().warning("Messages file " + messagesFileName + " not found in plugin!");
                    messagesConfig = new YamlConfiguration();
                    return;
                }
            }
            messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
            String currentFileVersion = messagesConfig.getString("version", "0.0.0");
            if (!currentFileVersion.equals(CURRENT_VERSION)) {
                if (getResource(messagesFileName) != null) {
                    File backupFile = new File(getDataFolder(), messagesFileName + ".backup");
                    if (messagesFile.renameTo(backupFile)) {
                        getLogger().info("Backed up old messages file to: " + messagesFileName + ".backup");
                    }
                    saveResource(messagesFileName, true);
                    messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
                    messagesConfig.set("version", CURRENT_VERSION);
                    messagesConfig.save(messagesFile);
                    getLogger().info("Updated messages file " + messagesFileName + " to version " + CURRENT_VERSION);
                } else {
                    getLogger().warning("Resource " + messagesFileName + " not found in plugin!");
                }
            } else if (isFirstEnable) {
                getLogger().info("Messages file " + messagesFileName + " is up-to-date (version " + CURRENT_VERSION + ").");
            }
        } catch (Exception e) {
            getLogger().severe("Failed to load messages file: " + e.getMessage());
            messagesConfig = new YamlConfiguration();
        }
    }

    public String getMessage(String key, String defaultValue) {
        String message = messagesConfig.getString(key, defaultValue);
        if (message == null || message.isEmpty()) {
            return defaultValue;
        }
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    public void loadPlayerMenuState(Player player) {
        if (!player.hasPermission("playermanager.admin") && !player.hasPermission("playermanager.gui")) return;
        UUID uuid = player.getUniqueId();
        String lastMenu = this.playerDataConfig.getString("admin." + uuid + ".lastOpenedMenu", "search");
        int lastPage = this.playerDataConfig.getInt("admin." + uuid + ".lastPage", 0);
        String lastTarget = this.playerDataConfig.getString("admin." + uuid + ".lastTarget");
        this.playerSearchGUI.setLastOpenedMenu(uuid, lastMenu);
        this.playerSearchGUI.setLastPage(uuid, lastPage);
        this.playerSearchGUI.setLastTarget(uuid, lastTarget);
    }

    public void savePlayerMenuState(Player player) {
        if (player == null || !player.isOnline() || (!player.hasPermission("playermanager.admin") && !player.hasPermission("playermanager.gui"))) return;
        UUID uuid = player.getUniqueId();
        String lastMenu = this.playerSearchGUI.getLastOpenedMenu(uuid);
        int lastPage = this.playerSearchGUI.getLastPage(uuid);
        String lastTarget = this.playerSearchGUI.getLastTarget(uuid);
        boolean needsSave = false;
        String currentLastMenu = this.playerDataConfig.getString("admin." + uuid + ".lastOpenedMenu");
        int currentLastPage = this.playerDataConfig.getInt("admin." + uuid + ".lastPage", -1);
        String currentLastTarget = this.playerDataConfig.getString("admin." + uuid + ".lastTarget");
        if (!Objects.equals(currentLastMenu, lastMenu) || currentLastPage != lastPage || !Objects.equals(currentLastTarget, lastTarget)) {
            this.playerDataConfig.set("admin." + uuid + ".lastOpenedMenu", lastMenu);
            this.playerDataConfig.set("admin." + uuid + ".lastPage", lastPage);
            this.playerDataConfig.set("admin." + uuid + ".lastTarget", lastTarget);
            needsSave = true;
        }
        if (needsSave) {
            this.savePlayerDataConfig();
        }
    }

    private void saveAllPlayerMenuStates() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.savePlayerMenuState(player);
        }
    }

    private void updatePlayerDataOnJoin(Player player) {
        String uuidStr = player.getUniqueId().toString();
        String path = "players." + uuidStr + ".";
        boolean needsSave = false;

        boolean isAdmin = isAdminPlayer(player);
        boolean storedAdmin = this.playerDataConfig.getBoolean(path + "isAdmin", false);
        if (storedAdmin != isAdmin) {
            this.playerDataConfig.set(path + "isAdmin", isAdmin);
            needsSave = true;
        }

        if (!this.playerDataConfig.contains(path + "first_played")) {
            this.playerDataConfig.set(path + "first_played", player.getFirstPlayed());
            needsSave = true;
        }
        this.playerDataConfig.set(path + "last_login", System.currentTimeMillis());
        this.playerDataConfig.set(path + "name", player.getName());
        this.playerDataConfig.set(path + "ip", player.getAddress().getAddress().getHostAddress());
        boolean storedBanned = this.playerDataConfig.getBoolean(path + "banned", false);
        boolean isBanned = player.isBanned();
        if (storedBanned != isBanned) {
            this.playerDataConfig.set(path + "banned", isBanned);
            needsSave = true;
        }
        String storedName = this.playerDataConfig.getString(path + "name", "");
        if (!player.getName().equals(storedName)) {
            this.playerDataConfig.set(path + "name", player.getName());
            needsSave = true;
        }
        if (needsSave) {
            this.savePlayerDataConfig();
        }
        if (latestVersion != null && (player.hasPermission("playermanager.admin") || player.hasPermission("playermanager.updates")) && !notifiedAdmins.contains(player.getUniqueId())) {
            String playerMessage = ChatColor.YELLOW + "" + ChatColor.BOLD + getMessage("update.available", "A new version of PlayerManager (%version%) is available at https://modrinth.com/plugin/playermanagers/versions")
                    .replace("%version%", latestVersion);
            notifiedAdmins.add(player.getUniqueId());
            Bukkit.getScheduler().runTaskLater(this, () -> player.sendMessage(playerMessage), 20L);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        this.updatePlayerDataOnJoin(event.getPlayer());
        this.loadPlayerMenuState(event.getPlayer());
        String name = event.getPlayer().getName().toLowerCase();
        if (!cachedPlayerNames.contains(name)) {
            cachedPlayerNames.add(name);
            cachedPlayerNames.sort(String.CASE_INSENSITIVE_ORDER);
            getLogger().info("Added " + name + " to player name cache");
        }
        if (isAdminPlayer(event.getPlayer())) {
            adminUUIDs.add(event.getPlayer().getUniqueId().toString());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        String uuidStr = player.getUniqueId().toString();
        String path = "players." + uuidStr + ".";
        long currentTime = System.currentTimeMillis();
        this.playerDataConfig.set(path + "last_logout", currentTime);
        this.playerDataConfig.set(path + "name", player.getName());
        this.savePlayerMenuState(player);
        try {
            this.playerDataConfig.save(playerDataFile);
        } catch (IOException e) {
            getLogger().severe("Failed to save player_data.yml for " + player.getName() + ": " + e.getMessage());
            e.printStackTrace();
        }
        if (!isAdminPlayer(player)) {
            adminUUIDs.remove(uuidStr);
        }
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (event.getMessage().toLowerCase().startsWith("/playermanager reset")) {
            if (!event.getPlayer().hasPermission("playermanager.admin") && !event.getPlayer().hasPermission("playermanager.gui") && !event.getPlayer().hasPermission("playermanager.reset")) {
                event.getPlayer().sendMessage(ChatColor.RED + getMessage("error.no-permission", "You don't have permission!"));
                event.setCancelled(true);
                return;
            }
            this.playerSearchGUI.resetSearch(event.getPlayer());
            event.setCancelled(true);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("playermanager.admin") && !hasAnyPermission(sender)) {
            sender.sendMessage(ChatColor.RED + getMessage("error.no-permission", "You don't have permission!"));
            return true;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + getMessage("error.player-only", "This command is for players only!"));
            return true;
        }
        Player admin = (Player) sender;
        if (args.length == 0) {
            sender.sendMessage(ChatColor.RED + getMessage("command.usage", "Usage: /playermanager <gui | reload | reset | ban | kick | warn | mute | unban | teleport | inventory>"));
            return true;
        }
        String action = args[0].toLowerCase();
        if (action.equals("gui")) {
            if (!sender.hasPermission("playermanager.admin") && !sender.hasPermission("playermanager.gui")) {
                sender.sendMessage(ChatColor.RED + getMessage("error.no-permission", "You don't have permission!"));
                return true;
            }
            if (args.length != 1) {
                sender.sendMessage(ChatColor.RED + getMessage("command.usage-gui", "Usage: /playermanager gui"));
                return true;
            }
            this.playerSearchGUI.openLastGUIMenu(admin);
            return true;
        }
        if (action.equals("reload")) {
            if (!sender.hasPermission("playermanager.admin") && !sender.hasPermission("playermanager.reload")) {
                sender.sendMessage(ChatColor.RED + getMessage("error.no-permission", "You don't have permission!"));
                return true;
            }
            if (args.length != 1) {
                sender.sendMessage(ChatColor.RED + getMessage("command.usage-reload", "Usage: /playermanager reload"));
                return true;
            }
            this.reloadPlugin(admin);
            return true;
        }
        if (action.equals("reset")) {
            if (!sender.hasPermission("playermanager.admin") && !sender.hasPermission("playermanager.gui") && !sender.hasPermission("playermanager.reset")) {
                sender.sendMessage(ChatColor.RED + getMessage("error.no-permission", "You don't have permission!"));
                return true;
            }
            if (args.length != 1) {
                sender.sendMessage(ChatColor.RED + getMessage("command.usage", "Usage: /playermanager reset"));
                return true;
            }
            this.playerSearchGUI.resetSearch(admin);
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + getMessage("command.usage", "Usage: /playermanager <gui | reload | reset | ban | kick | warn | mute | unban | teleport | inventory>"));
            return true;
        }
        String targetName = args[1];
        OfflinePlayer target = Arrays.stream(Bukkit.getOfflinePlayers())
                .filter(p -> p.getName() != null && p.getName().equalsIgnoreCase(targetName))
                .findFirst()
                .orElse(null);
        if (target == null) {
            admin.sendMessage(ChatColor.RED + getMessage("error.no-player-data", "Player data is not available."));
            return true;
        }

        if (isAdminPlayer(target)) {
            admin.sendMessage(ChatColor.RED + getMessage("error.cannot-target-admin", "You cannot target another admin."));
            return true;
        }

        if (target.getUniqueId().equals(admin.getUniqueId())) {
            if (action.equals("teleport")) {
                admin.sendMessage(ChatColor.RED + getMessage("error.self-teleport", "You can't teleport to yourself."));
            } else if (Arrays.asList("ban", "kick", "warn", "mute", "unban").contains(action)) {
                admin.sendMessage(ChatColor.RED + getMessage("error.self-punish", "You can't punish yourself."));
            }
            return true;
        }
        if (!sender.hasPermission("playermanager.admin") && !sender.hasPermission("playermanager." + action)) {
            sender.sendMessage(ChatColor.RED + getMessage("error.no-permission", "You don't have permission!"));
            return true;
        }
        String path = "players." + target.getUniqueId().toString() + ".";
        boolean needsSave = false;
        switch (action) {
            case "ban":
                if (!getConfig().getBoolean("features.punishment-system", true)) {
                    admin.sendMessage(ChatColor.RED + getMessage("error.punishment-system-disabled", "Punishment system is disabled!"));
                    return true;
                }
                if (playerDataConfig.getBoolean(path + "banned", false)) {
                    admin.sendMessage(ChatColor.RED + getMessage("error.already-banned", "This player is already banned!"));
                    return true;
                }
                String banReason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "Banned by admin";
                Bukkit.dispatchCommand(admin, "ban " + targetName + " " + banReason);
                if (!playerDataConfig.getBoolean(path + "banned", false)) {
                    playerDataConfig.set(path + "banned", true);
                    needsSave = true;
                }
                admin.sendMessage(ChatColor.YELLOW + getMessage("action.executed", "Executed: %command% for %player%")
                        .replace("%command%", "ban").replace("%player%", targetName));
                break;
            case "kick":
                if (!getConfig().getBoolean("features.punishment-system", true)) {
                    admin.sendMessage(ChatColor.RED + getMessage("error.punishment-system-disabled", "Punishment system is disabled!"));
                    return true;
                }
                Player onlinePlayer = Bukkit.getPlayer(targetName);
                if (onlinePlayer == null) {
                    admin.sendMessage(ChatColor.RED + getMessage("error.player-offline", "Player is offline."));
                    return true;
                }
                String kickReason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "Kicked by admin";
                Bukkit.dispatchCommand(admin, "kick " + targetName + " " + kickReason);
                admin.sendMessage(ChatColor.YELLOW + getMessage("action.executed", "Executed: %command% for %player%")
                        .replace("%command%", "kick").replace("%player%", targetName));
                break;
            case "warn":
                if (!getConfig().getBoolean("features.punishment-system", true)) {
                    admin.sendMessage(ChatColor.RED + getMessage("error.punishment-system-disabled", "Punishment system is disabled!"));
                    return true;
                }
                onlinePlayer = Bukkit.getPlayer(targetName);
                if (onlinePlayer == null) {
                    admin.sendMessage(ChatColor.RED + getMessage("error.player-offline", "Player is offline."));
                    return true;
                }
                String warnReason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "Warned by admin";
                Bukkit.dispatchCommand(admin, "warn " + targetName + " " + warnReason);
                admin.sendMessage(ChatColor.YELLOW + getMessage("action.executed", "Executed: %command% for %player%")
                        .replace("%command%", "warn").replace("%player%", targetName));
                break;
            case "mute":
                if (!getConfig().getBoolean("features.punishment-system", true)) {
                    admin.sendMessage(ChatColor.RED + getMessage("error.punishment-system-disabled", "Punishment system is disabled!"));
                    return true;
                }
                onlinePlayer = Bukkit.getPlayer(targetName);
                if (onlinePlayer == null) {
                    admin.sendMessage(ChatColor.RED + getMessage("error.player-offline", "Player is offline."));
                    return true;
                }
                String muteReason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "Muted by admin";
                Bukkit.dispatchCommand(admin, "mute " + targetName + " " + muteReason);
                admin.sendMessage(ChatColor.YELLOW + getMessage("action.executed", "Executed: %command% for %player%")
                        .replace("%command%", "mute").replace("%player%", targetName));
                break;
            case "unban":
                if (!getConfig().getBoolean("features.punishment-system", true)) {
                    admin.sendMessage(ChatColor.RED + getMessage("error.punishment-system-disabled", "Punishment system is disabled!"));
                    return true;
                }
                if (!playerDataConfig.getBoolean(path + "banned", false)) {
                    admin.sendMessage(ChatColor.RED + getMessage("error.not-banned", "This player is not banned!"));
                    return true;
                }
                Bukkit.dispatchCommand(admin, "pardon " + targetName);
                if (playerDataConfig.getBoolean(path + "banned", false)) {
                    playerDataConfig.set(path + "banned", false);
                    needsSave = true;
                }
                admin.sendMessage(ChatColor.YELLOW + getMessage("action.executed", "Executed: %command% for %player%")
                        .replace("%command%", "unban").replace("%player%", targetName));
                break;
            case "teleport":
                if (!getConfig().getBoolean("features.player-teleportation", true)) {
                    admin.sendMessage(ChatColor.RED + getMessage("error.teleportation-disabled", "Teleportation is disabled!"));
                    return true;
                }
                Player targetPlayer = Bukkit.getPlayer(targetName);
                if (targetPlayer == null) {
                    admin.sendMessage(ChatColor.RED + getMessage("error.player-offline", "Player is offline."));
                    return true;
                }
                admin.teleport(targetPlayer);
                admin.sendMessage(ChatColor.GREEN + getMessage("action.teleported", "Teleported to %player%").replace("%player%", targetName));
                break;
            case "inventory":
                if (!getConfig().getBoolean("features.inventory-inspection", true)) {
                    admin.sendMessage(ChatColor.RED + getMessage("error.inventory-inspection-disabled", "Inventory inspection is disabled!"));
                    return true;
                }
                Player targetInvPlayer = Bukkit.getPlayer(targetName);
                if (targetInvPlayer == null) {
                    admin.sendMessage(ChatColor.RED + getMessage("error.player-offline-inventory", "Player is offline, inventory modification is impossible."));
                    return true;
                }
                admin.openInventory(targetInvPlayer.getInventory());
                break;
            default:
                sender.sendMessage(ChatColor.RED + getMessage("command.usage", "Usage: /playermanager <gui | reload | reset | ban | kick | warn | mute | unban | teleport | inventory>"));
                return true;
        }
        if (needsSave) {
            this.savePlayerDataConfig();
        }
        this.playerSearchGUI.refreshOpenGUIs();
        return true;
    }

    private void reloadPlugin(Player player) {
        this.reloadConfig();
        this.language = getConfig().getString("language", "en");
        if (!Arrays.asList(SUPPORTED_LANGUAGES).contains(this.language)) {
            getLogger().warning("Unsupported language '" + this.language + "' in config.yml, defaulting to 'en'");
            this.language = "en";
        }
        this.updateConfigFile();
        this.updateMessagesFiles();
        this.loadMessages();
        for (Player p : Bukkit.getOnlinePlayers()) {
            this.loadPlayerMenuState(p);
        }
        player.sendMessage(ChatColor.GREEN + getMessage("action.config-reloaded", "Configuration reloaded."));
        this.playerSearchGUI.refreshOpenGUIs();
    }

    public FileConfiguration getPlayerDataConfig() {
        return this.playerDataConfig;
    }

    public void savePlayerDataConfig() {
        try {
            this.playerDataConfig.save(this.playerDataFile);
        } catch (IOException e) {
            getLogger().severe("Failed to save player_data.yml at " + playerDataFile.getAbsolutePath() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    public String getLanguage() {
        return language;
    }

    public void updatePlayerBanStatus(String uuid, boolean banned) {
        String path = "players." + uuid + ".banned";
        playerDataConfig.set(path, banned);
        savePlayerDataConfig();
    }
}