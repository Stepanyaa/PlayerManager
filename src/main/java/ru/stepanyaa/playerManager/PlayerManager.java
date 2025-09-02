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
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
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

public final class PlayerManager extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private File playerDataFile;
    private FileConfiguration playerDataConfig;
    private File messagesFile;
    private FileConfiguration messagesConfig;
    private PlayerSearchGUI playerSearchGUI;
    private String language;
    private static final String MODRINTH_API_URL = "https://api.modrinth.com/v2/project/playermanagers/version";
    private static final String CURRENT_VERSION = "1.0.4";
    private String latestVersion = null;
    private final Set<UUID> notifiedAdmins = new HashSet<>();
    private static final String[] SUPPORTED_LANGUAGES = {"en", "ru"};

    public void onEnable() {
        this.saveDefaultConfig();
        this.reloadConfig();
        this.language = getConfig().getString("language", "en");
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
        this.updateMessagesFiles();
        this.updateConfigFile();
        this.loadMessages();
        this.playerSearchGUI = new PlayerSearchGUI(this);
        this.getServer().getPluginManager().registerEvents(this, this);
        this.getServer().getPluginManager().registerEvents(this.playerSearchGUI, this);
        PluginCommand command = this.getCommand("playermanager");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
            command.setPermissionMessage(ChatColor.RED + getMessage("error.no-permission", "You don't have permission!"));
        } else {
            getLogger().warning("Failed to register command 'playermanager'!");
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.loadPlayerMenuState(player);
            this.updatePlayerDataOnJoin(player);
        }
        checkForUpdates();
        getLogger().info("PlayerManager enabled with language: " + language);
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
            YamlConfiguration existingConfig = messageFile.exists() ?
                    YamlConfiguration.loadConfiguration(messageFile) : new YamlConfiguration();

            String currentFileVersion = existingConfig.getString("version", "0.0.0");

            if (currentFileVersion.equals(CURRENT_VERSION)) {
                getLogger().info("Messages file " + fileName + " is up-to-date (version " + CURRENT_VERSION + ")");
                continue;
            }

            if (getResource(fileName) != null) {
                try {
                    saveResource(fileName, true);
                    getLogger().info("Replaced " + fileName + " with new version " + CURRENT_VERSION);
                } catch (Exception e) {
                    getLogger().warning("Failed to replace " + fileName + ": " + e.getMessage());
                }
            } else {
                getLogger().warning("Resource " + fileName + " not found in plugin");
            }
        }
    }

    private void updateConfigFile() {
        File configFile = new File(getDataFolder(), "config.yml");
        YamlConfiguration existingConfig = configFile.exists() ?
                YamlConfiguration.loadConfiguration(configFile) : new YamlConfiguration();

        String currentFileVersion = existingConfig.getString("config-version", "0.0.0");

        if (currentFileVersion.equals(CURRENT_VERSION)) {
            getLogger().info("Config file config.yml is up-to-date (version " + CURRENT_VERSION + ")");
            return;
        }

        if (getResource("config.yml") != null) {
            try {
                saveResource("config.yml", true);
                getLogger().info("Replaced config.yml with new version " + CURRENT_VERSION);
            } catch (Exception e) {
                getLogger().warning("Failed to replace config.yml: " + e.getMessage());
            }
        } else {
            getLogger().warning("Resource config.yml not found in plugin");
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
        } else {
            String uuidStr = offlinePlayer.getUniqueId().toString();
            return this.playerDataConfig.getBoolean("players." + uuidStr + ".isAdmin", false);
        }
    }

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
            return commands;
        } else if (args.length == 2 && Arrays.asList("ban", "kick", "warn", "mute", "unban", "teleport", "inventory").contains(args[0].toLowerCase())) {
            String action = args[0].toLowerCase();
            if (sender.hasPermission("playermanager.admin") || sender.hasPermission("playermanager." + action)) {
                List<String> playerNames = new ArrayList<>();
                for (OfflinePlayer offlinePlayer : Bukkit.getOfflinePlayers()) {
                    if (offlinePlayer.getName() == null) continue;

                    if (isAdminPlayer(offlinePlayer)) {
                        continue;
                    }

                    playerNames.add(offlinePlayer.getName());
                }
                return playerNames;
            }
        }
        return Collections.emptyList();
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

    public void onDisable() {
        this.saveAllPlayerMenuStates();
        notifiedAdmins.clear();
        getLogger().info("PlayerManager disabled");
    }

    private void loadMessages() {
        String messagesFileName = "messages_" + language + ".yml";
        this.messagesFile = new File(this.getDataFolder(), messagesFileName);

        if (!this.messagesFile.exists()) {
            if (getResource(messagesFileName) != null) {
                this.saveResource(messagesFileName, false);
                getLogger().info("Created default messages file: " + messagesFileName);
            } else {
                getLogger().warning("Messages file for language '" + language + "' not found, falling back to English");
                messagesFileName = "messages_en.yml";
                this.messagesFile = new File(this.getDataFolder(), messagesFileName);
                if (!this.messagesFile.exists() && getResource(messagesFileName) != null) {
                    this.saveResource(messagesFileName, false);
                }
            }
        }
        try {
            this.messagesConfig = YamlConfiguration.loadConfiguration(this.messagesFile);

            if (getResource(messagesFileName) != null) {
                YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(Objects.requireNonNull(getResource(messagesFileName))));
                boolean needsSave = false;
                for (String key : defaultConfig.getKeys(true)) {
                    if (!messagesConfig.contains(key)) {
                        messagesConfig.set(key, defaultConfig.get(key));
                        needsSave = true;
                        getLogger().info("Added missing key to messages: " + key);
                    }
                }
                if (needsSave) {
                    try {
                        messagesConfig.save(messagesFile);
                        getLogger().info("Updated messages file with missing keys: " + messagesFileName);
                    } catch (IOException e) {
                        getLogger().warning("Failed to save updated messages file: " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            getLogger().severe("Failed to load messages file: " + e.getMessage());
            this.messagesConfig = new YamlConfiguration();
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
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        String uuidStr = player.getUniqueId().toString();
        String path = "players." + uuidStr + ".";
        this.playerDataConfig.set(path + "last_logout", System.currentTimeMillis());
        this.savePlayerMenuState(player);
        this.savePlayerDataConfig();
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
        String path = "players." + "targetUUID" + ".";
        boolean needsSave = false;
        switch (action) {
            case "ban":
                if (!getConfig().getBoolean("features.punishment-system", true)) {
                    admin.sendMessage(ChatColor.RED + getMessage("error.punishment-system-disabled", "Punishment system is disabled!"));
                    return true;
                }
                Bukkit.dispatchCommand(admin, "ban " + targetName);
                if (!this.playerDataConfig.getBoolean(path + "banned", false)) {
                    this.playerDataConfig.set(path + "banned", true);
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
                Bukkit.dispatchCommand(admin, "kick " + targetName);
                admin.sendMessage(ChatColor.YELLOW + getMessage("action.executed", "Executed: %command% for %player%")
                        .replace("%command%", "kick").replace("%player%", targetName));
                break;
            case "warn":
                if (!getConfig().getBoolean("features.punishment-system", true)) {
                    admin.sendMessage(ChatColor.RED + getMessage("error.punishment-system-disabled", "Punishment system is disabled!"));
                    return true;
                }
                Bukkit.dispatchCommand(admin, "warn " + targetName);
                admin.sendMessage(ChatColor.YELLOW + getMessage("action.executed", "Executed: %command% for %player%")
                        .replace("%command%", "warn").replace("%player%", targetName));
                break;
            case "mute":
                if (!getConfig().getBoolean("features.punishment-system", true)) {
                    admin.sendMessage(ChatColor.RED + getMessage("error.punishment-system-disabled", "Punishment system is disabled!"));
                    return true;
                }
                Bukkit.dispatchCommand(admin, "mute " + targetName);
                admin.sendMessage(ChatColor.YELLOW + getMessage("action.executed", "Executed: %command% for %player%")
                        .replace("%command%", "mute").replace("%player%", targetName));
                break;
            case "unban":
                if (!getConfig().getBoolean("features.punishment-system", true)) {
                    admin.sendMessage(ChatColor.RED + getMessage("error.punishment-system-disabled", "Punishment system is disabled!"));
                    return true;
                }
                if (!target.isBanned()) {
                    admin.sendMessage(ChatColor.RED + getMessage("error.not-banned", "This player is not banned!"));
                    return true;
                }
                Bukkit.dispatchCommand(admin, "pardon " + targetName);
                if (this.playerDataConfig.getBoolean(path + "banned", false)) {
                    this.playerDataConfig.set(path + "banned", false);
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
            try {
                if (playerDataFile.delete()) {
                    getLogger().info("Deleted corrupted player_data.yml, recreating...");
                    playerDataFile.createNewFile();
                    this.playerDataConfig = new YamlConfiguration();
                    this.playerDataConfig.createSection("players");
                    this.playerDataConfig.save(playerDataFile);
                    getLogger().info("Recreated player_data.yml");
                }
            } catch (IOException ex) {
                getLogger().severe("Failed to recreate player_data.yml: " + ex.getMessage());
                ex.printStackTrace();
            }
        }
    }

    public String getLanguage() {
        return language;
    }
}