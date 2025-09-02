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

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import java.lang.reflect.Field;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;

public class PlayerSearchGUI implements Listener, InventoryHolder {
    private final PlayerManager plugin;
    private Inventory inventory;
    private final Map<Integer, PlayerResult> slotMap = new ConcurrentHashMap<>();
    private int currentPage = 0;
    private String currentSearch = "";
    private Filter currentFilter;
    private final Map<UUID, PlayerResult> playerMenuTargets;
    private final SimpleDateFormat dateFormat;
    private final Set<UUID> playersInGUI;
    private final Map<UUID, ChatAction> pendingActions;
    private final Map<UUID, String> lastOpenedMenu;
    private final Map<UUID, Integer> lastPage;
    private final Map<UUID, String> lastTarget;

    @FunctionalInterface
    interface ChatAction {
        void execute(String message, Player player);
    }
    private List<PlayerResult> cachedResults = new ArrayList<>();

    public PlayerSearchGUI(PlayerManager plugin) {
        this.currentFilter = Filter.ALL;
        this.playerMenuTargets = new ConcurrentHashMap<>();
        this.dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        this.dateFormat.setTimeZone(TimeZone.getDefault() != null ? TimeZone.getDefault() : TimeZone.getTimeZone("UTC"));
        this.playersInGUI = Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.pendingActions = new ConcurrentHashMap<>();
        this.lastOpenedMenu = new ConcurrentHashMap<>();
        this.lastPage = new ConcurrentHashMap<>();
        this.lastTarget = new ConcurrentHashMap<>();
        this.plugin = plugin;
        this.inventory = Bukkit.createInventory(this, 54, plugin.getMessage("gui.title", "Player Management"));
    }

    public void openLastGUIMenu(Player admin) {
        if (!admin.hasPermission("playermanager.admin") && !admin.hasPermission("playermanager.gui")) {
            admin.sendMessage(ChatColor.RED + plugin.getMessage("error.no-permission", "You don't have permission!"));
            return;
        }
        String lastMenu = lastOpenedMenu.getOrDefault(admin.getUniqueId(), "search");
        this.currentPage = lastPage.getOrDefault(admin.getUniqueId(), 0);
        String lastTargetName = this.lastTarget.get(admin.getUniqueId());
        if (lastMenu.equals("player") && lastTargetName != null) {
            PlayerResult result = getPlayerResultByName(lastTargetName);
            if (result != null) {
                openPlayerMenu(admin, result);
                return;
            }
        } else if (lastMenu.equals("punishment") && lastTargetName != null) {
            PlayerResult result = getPlayerResultByName(lastTargetName);
            if (result != null) {
                openPunishmentGUI(admin, result);
                return;
            }
        } else if (lastMenu.equals("player_info") && lastTargetName != null) {
            PlayerResult result = getPlayerResultByName(lastTargetName);
            if (result != null) {
                openPlayerInfoGUI(admin, result);
                return;
            }
        } else if (lastMenu.equals("gamemode") && lastTargetName != null) {
            PlayerResult result = getPlayerResultByName(lastTargetName);
            if (result != null) {
                openGamemodeGUI(admin, result);
                return;
            }
        } else if (lastMenu.equals("gamemode_confirm") && lastTargetName != null) {
            PlayerResult result = getPlayerResultByName(lastTargetName);
            if (result != null) {
                openGamemodeConfirmGUI(admin, result);
                return;
            }
        }
        openSearchGUI(admin);
    }

    public void openSearchGUI(Player admin) {
        if (!admin.hasPermission("playermanager.admin") && !admin.hasPermission("playermanager.gui")) {
            admin.sendMessage(ChatColor.RED + plugin.getMessage("error.no-permission", "You don't have permission!"));
            return;
        }
        this.inventory = Bukkit.createInventory(this, 54, plugin.getMessage("gui.title", "Player Management"));
        ItemStack loadingItem = new ItemStack(Material.REDSTONE);
        ItemMeta loadingMeta = loadingItem.getItemMeta();
        if (loadingMeta != null) {
            loadingMeta.setDisplayName(ChatColor.YELLOW + plugin.getMessage("gui.loading", "Loading..."));
            loadingItem.setItemMeta(loadingMeta);
        }
        this.inventory.setItem(4, loadingItem);
        admin.openInventory(this.inventory);
        this.playersInGUI.add(admin.getUniqueId());
        setLastOpenedMenu(admin.getUniqueId(), "search");
        plugin.savePlayerMenuState(admin);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<PlayerResult> results = getSearchResults(admin, currentSearch);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (admin.isOnline() && playersInGUI.contains(admin.getUniqueId())) {
                    setupSearchGUI(admin, results);
                    admin.updateInventory();
                }
            });
        });
    }

    private PlayerResult getPlayerResultByName(String name) {
        for (OfflinePlayer offlinePlayer : Bukkit.getOfflinePlayers()) {
            if (offlinePlayer.getName() != null && offlinePlayer.getName().equalsIgnoreCase(name)) {
                String uuidStr = offlinePlayer.getUniqueId().toString();
                String path = "players." + uuidStr + ".";
                PlayerResult result = new PlayerResult();
                result.uuid = uuidStr;
                result.name = offlinePlayer.getName();
                result.firstPlayed = plugin.getPlayerDataConfig().getLong(path + "first_played", offlinePlayer.getFirstPlayed());
                result.lastLogin = plugin.getPlayerDataConfig().getLong(path + "last_login", offlinePlayer.getFirstPlayed());
                result.lastLogout = plugin.getPlayerDataConfig().getLong(path + "last_logout", offlinePlayer.getLastPlayed());
                result.online = offlinePlayer.isOnline();
                result.banned = offlinePlayer.isBanned();
                boolean needsSave = false;
                boolean storedBanned = plugin.getPlayerDataConfig().getBoolean(path + "banned", false);
                if (storedBanned != result.banned) {
                    plugin.getPlayerDataConfig().set(path + "banned", result.banned);
                    needsSave = true;
                }
                result.headTexture = plugin.getPlayerDataConfig().getString(path + "head_texture", "");
                if (result.headTexture.isEmpty()) {
                    String newTexture = fetchHeadTexture(offlinePlayer);
                    if (!newTexture.isEmpty()) {
                        result.headTexture = newTexture;
                        plugin.getPlayerDataConfig().set(path + "head_texture", result.headTexture);
                        needsSave = true;
                    }
                }
                if (needsSave) {
                    plugin.savePlayerDataConfig();
                }
                return result;
            }
        }
        return null;
    }

    private String fetchHeadTexture(OfflinePlayer player) {
        String uuidStr = player.getUniqueId().toString();
        String cachedTexture = plugin.getPlayerDataConfig().getString("players." + uuidStr + ".head_texture", "");

        if (!cachedTexture.isEmpty()) {
            return cachedTexture;
        }

        try {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastTextureRequestTime < 100000) {
                return "";
            }

            ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) skull.getItemMeta();

            if (meta != null) {
                meta.setOwningPlayer(player);
                skull.setItemMeta(meta);

                Field profileField = meta.getClass().getDeclaredField("profile");
                profileField.setAccessible(true);
                GameProfile gameProfile = (GameProfile) profileField.get(meta);

                if (gameProfile != null && gameProfile.getProperties().containsKey("textures")) {
                    Property texture = gameProfile.getProperties().get("textures").iterator().next();

                    plugin.getPlayerDataConfig().set("players." + uuidStr + ".head_texture", texture.getValue());
                    plugin.savePlayerDataConfig();

                    lastTextureRequestTime = System.currentTimeMillis();
                    return texture.getValue();
                }
            }
        } catch (Exception e) {
            lastTextureRequestTime = System.currentTimeMillis() + 100000;
        }

        return "";
    }
    private long lastTextureRequestTime = 100000;

    private void setupSearchGUI(Player admin, List<PlayerResult> results) {
        this.inventory.clear();
        this.slotMap.clear();

        this.inventory.setItem(47, createFilterItem(Filter.ALL));
        this.inventory.setItem(48, createFilterItem(Filter.ONLINE));
        this.inventory.setItem(50, createFilterItem(Filter.OFFLINE));
        this.inventory.setItem(51, createFilterItem(Filter.BANNED));

        String searchText = plugin.getMessage("gui.search", "Search: %query%")
                .replace("%query%", currentSearch.isEmpty() ? plugin.getMessage("gui.search-all", "all") : currentSearch);
        ItemStack searchItem = new ItemStack(Material.COMPASS);
        ItemMeta searchMeta = searchItem.getItemMeta();
        if (searchMeta != null) {
            searchMeta.setDisplayName(ChatColor.YELLOW + searchText);
            List<String> searchLore = new ArrayList<>();
            searchLore.add(plugin.getMessage("gui.search-hint", "&7Left click: Enter query | Right click: Reset search"));
            searchMeta.setLore(searchLore);
            searchItem.setItemMeta(searchMeta);
        }
        this.inventory.setItem(4, searchItem);

        ItemStack closeItem = new ItemStack(Material.BARRIER);
        ItemMeta closeMeta = closeItem.getItemMeta();
        if (closeMeta != null) {
            closeMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.close", "Close"));
            closeItem.setItemMeta(closeMeta);
        }
        this.inventory.setItem(49, closeItem);

        int totalPages = (int) Math.ceil((double) results.size() / 36.0);
        String pageInfo = plugin.getMessage("gui.page-info", "Page %current%/%total%")
                .replace("%current%", String.valueOf(currentPage + 1))
                .replace("%total%", String.valueOf(totalPages));
        int start = currentPage * 36;
        int end = Math.min(start + 36, results.size());

        for (int i = start; i < end; i++) {
            PlayerResult result = results.get(i);
            ItemStack head = createPlayerHead(result);
            if (head != null) {
                this.inventory.setItem(i - start + 9, head);
                this.slotMap.put(i - start + 9, result);
            }
        }

        if (currentPage > 0) {
            ItemStack prevPage = new ItemStack(Material.ARROW);
            ItemMeta prevMeta = prevPage.getItemMeta();
            if (prevMeta != null) {
                prevMeta.setDisplayName(ChatColor.YELLOW + plugin.getMessage("gui.previous-page", "Previous page"));
                List<String> prevLore = new ArrayList<>();
                prevLore.add(ChatColor.GRAY + pageInfo);
                prevLore.add(ChatColor.GRAY + plugin.getMessage("gui.shift-rmb-page", "Shift+RMB: Skip 5 pages"));
                prevMeta.setLore(prevLore);
                prevPage.setItemMeta(prevMeta);
            }
            this.inventory.setItem(45, prevPage);
        } else {
            ItemStack noPrev = new ItemStack(Material.RED_STAINED_GLASS_PANE);
            ItemMeta noPrevMeta = noPrev.getItemMeta();
            if (noPrevMeta != null) {
                noPrevMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.no-page", "No page"));
                List<String> noPrevLore = new ArrayList<>();
                noPrevLore.add(ChatColor.GRAY + pageInfo);
                noPrevLore.add(ChatColor.GRAY + plugin.getMessage("gui.shift-rmb-page", "Shift+RMB: Skip 5 pages"));
                noPrevMeta.setLore(noPrevLore);
                noPrev.setItemMeta(noPrevMeta);
            }
            this.inventory.setItem(45, noPrev);
        }

        if ((currentPage + 1) * 36 < results.size()) {
            ItemStack nextPage = new ItemStack(Material.ARROW);
            ItemMeta nextMeta = nextPage.getItemMeta();
            if (nextMeta != null) {
                nextMeta.setDisplayName(ChatColor.YELLOW + plugin.getMessage("gui.next-page", "Next page"));
                List<String> nextLore = new ArrayList<>();
                nextLore.add(ChatColor.GRAY + pageInfo);
                nextLore.add(ChatColor.GRAY + plugin.getMessage("gui.shift-rmb-page", "Shift+RMB: Skip 5 pages"));
                nextMeta.setLore(nextLore);
                nextPage.setItemMeta(nextMeta);
            }
            this.inventory.setItem(53, nextPage);
        } else {
            ItemStack noNext = new ItemStack(Material.RED_STAINED_GLASS_PANE);
            ItemMeta noNextMeta = noNext.getItemMeta();
            if (noNextMeta != null) {
                noNextMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.no-page", "No page"));
                List<String> noNextLore = new ArrayList<>();
                noNextLore.add(ChatColor.GRAY + pageInfo);
                noNextLore.add(ChatColor.GRAY + plugin.getMessage("gui.shift-rmb-page", "Shift+RMB: Skip 5 pages"));
                noNextMeta.setLore(noNextLore);
                noNext.setItemMeta(noNextMeta);
            }
            this.inventory.setItem(53, noNext);
        }
    }

    private List<PlayerResult> getSearchResults(Player admin, String search) {
        if (cachedResults != null && search.equals(currentSearch) && !cachedResults.isEmpty()) {
            return new ArrayList<>(cachedResults);
        }

        List<PlayerResult> results = new ArrayList<>();
        String searchLower = search.toLowerCase().trim();

        for (OfflinePlayer offlinePlayer : Bukkit.getOfflinePlayers()) {
            if (offlinePlayer.getName() == null || plugin.isAdminPlayer(offlinePlayer)) {
                continue;
            }

            String name = offlinePlayer.getName();
            String nameLower = name.toLowerCase();
            String uuidStr = offlinePlayer.getUniqueId().toString();
            if (!searchLower.isEmpty() && !nameLower.contains(searchLower) && !nameLower.matches(".*" + searchLower + ".*")) {
                continue;
            }

            String path = "players." + uuidStr + ".";
            PlayerResult result = new PlayerResult();
            result.uuid = uuidStr;
            result.name = name;
            result.firstPlayed = plugin.getPlayerDataConfig().getLong(path + "first_played", offlinePlayer.getFirstPlayed());
            result.lastLogin = plugin.getPlayerDataConfig().getLong(path + "last_login", offlinePlayer.getFirstPlayed());
            result.lastLogout = plugin.getPlayerDataConfig().getLong(path + "last_logout", offlinePlayer.getLastPlayed());
            result.online = offlinePlayer.isOnline();
            result.banned = offlinePlayer.isBanned();
            boolean needsSave = false;

            boolean storedBanned = plugin.getPlayerDataConfig().getBoolean(path + "banned", false);
            if (storedBanned != result.banned) {
                plugin.getPlayerDataConfig().set(path + "banned", result.banned);
                needsSave = true;
            }

            result.headTexture = plugin.getPlayerDataConfig().getString(path + "head_texture", "");
            if (result.headTexture.isEmpty()) {
                String newTexture = fetchHeadTexture(offlinePlayer);
                if (!newTexture.isEmpty()) {
                    result.headTexture = newTexture;
                    plugin.getPlayerDataConfig().set(path + "head_texture", result.headTexture);
                    needsSave = true;
                }
            }

            if (needsSave) {
                plugin.savePlayerDataConfig();
            }

            switch (currentFilter) {
                case ONLINE:
                    if (!result.online) continue;
                    break;
                case OFFLINE:
                    if (result.online) continue;
                    break;
                case BANNED:
                    if (!result.banned) continue;
                    break;
                default:
                    break;
            }
            results.add(result);
        }

        results.sort(Comparator.comparing(r -> r.name.toLowerCase()));
        cachedResults = new ArrayList<>(results);
        return results;
    }

    private ItemStack createPlayerHead(PlayerResult result) {
        UUID playerUUID = UUID.fromString(result.uuid);
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerUUID);

        if (plugin.isAdminPlayer(offlinePlayer)) {
            return null;
        }

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setDisplayName(ChatColor.YELLOW + result.name);
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + plugin.getMessage("gui.uuid", "UUID") + ": " + ChatColor.WHITE + result.uuid);
        lore.add(ChatColor.GRAY + plugin.getMessage("gui.first-played", "First played") + ": " + ChatColor.WHITE + formatDate(result.firstPlayed));
        lore.add(ChatColor.GRAY + plugin.getMessage("gui.last-login", "Last login") + ": " + ChatColor.WHITE + formatDate(result.lastLogin));
        lore.add(ChatColor.GRAY + plugin.getMessage("gui.last-logout", "Last logout") + ": " + ChatColor.WHITE + formatDate(result.lastLogout));
        if (!result.online) {
            lore.add(ChatColor.GRAY + plugin.getMessage("gui.time-since-logout", "Time since logout") + ": " + ChatColor.WHITE + formatTimeAgo(result.lastLogout));
        }
        lore.add(ChatColor.GRAY + plugin.getMessage("gui.status", "Status") + ": " + (result.online ? ChatColor.GREEN + plugin.getMessage("status.online", "Online") : ChatColor.RED + plugin.getMessage("status.offline", "Offline")));
        lore.add(ChatColor.GRAY + plugin.getMessage("gui.ban-status", "Ban status") + ": " + (result.banned ? ChatColor.RED + plugin.getMessage("status.banned", "Banned") : ChatColor.GREEN + plugin.getMessage("status.not-banned", "Not banned")));
        lore.add("");
        lore.add(plugin.getMessage("gui.actions", "&7Left click: Menu | Right click: Teleport | Shift+Right click: Punishments"));
        meta.setLore(lore);
        if (!result.headTexture.isEmpty()) {
            try {
                GameProfile profile = new GameProfile(UUID.fromString(result.uuid), null);
                profile.getProperties().put("textures", new Property("textures", result.headTexture));
                Field profileField = meta.getClass().getDeclaredField("profile");
                profileField.setAccessible(true);
                profileField.set(meta, profile);
            } catch (Exception e) {
            }
        } else {
            meta.setOwningPlayer(offlinePlayer);
        }
        head.setItemMeta(meta);
        return head;
    }

    private ItemStack createFilterItem(Filter filter) {
        ItemStack item = new ItemStack(filter.getMaterial());
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(filter.getColor() + filter.getDisplayName(plugin));
        if (currentFilter == filter) {
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GREEN + plugin.getMessage("gui.filter-active", "Active filter"));
            meta.setLore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }

    public void openPlayerMenu(Player player, PlayerResult result) {
        if (!player.hasPermission("playermanager.admin") && !player.hasPermission("playermanager.gui")) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.no-permission", "You don't have permission!"));
            return;
        }
        Inventory playerMenu = Bukkit.createInventory(this, 27, plugin.getMessage("gui.player-menu-prefix", "Player Menu: ") + ChatColor.YELLOW + result.name);
        ItemStack headItem = createPlayerHead(result);
        playerMenu.setItem(4, headItem);
        ItemStack infoItem = new ItemStack(Material.BOOK);
        ItemMeta infoMeta = infoItem.getItemMeta();
        infoMeta.setDisplayName(ChatColor.YELLOW + plugin.getMessage("gui.info", "Information"));
        List<String> infoLore = new ArrayList<>();
        infoLore.add(ChatColor.GRAY + plugin.getMessage("gui.uuid", "UUID") + ": " + ChatColor.WHITE + result.uuid);
        infoLore.add(ChatColor.GRAY + plugin.getMessage("gui.first-played", "First played") + ": " + ChatColor.WHITE + formatDate(result.firstPlayed));
        infoLore.add(ChatColor.GRAY + plugin.getMessage("gui.last-login", "Last login") + ": " + ChatColor.WHITE + formatDate(result.lastLogin));
        infoLore.add(ChatColor.GRAY + plugin.getMessage("gui.last-logout", "Last logout") + ": " + ChatColor.WHITE + formatDate(result.lastLogout));
        if (!result.online) {
            infoLore.add(ChatColor.GRAY + plugin.getMessage("gui.time-since-logout", "Time since logout") + ": " + ChatColor.WHITE + formatTimeAgo(result.lastLogout));
        }
        infoLore.add(ChatColor.GRAY + plugin.getMessage("gui.status", "Status") + ": " + (result.online ? ChatColor.GREEN + plugin.getMessage("status.online", "Online") : ChatColor.RED + plugin.getMessage("status.offline", "Offline")));
        infoLore.add(ChatColor.GRAY + plugin.getMessage("gui.ban-status", "Ban status") + ": " + (result.banned ? ChatColor.RED + plugin.getMessage("status.banned", "Banned") : ChatColor.GREEN + plugin.getMessage("status.not-banned", "Not banned")));
        infoMeta.setLore(infoLore);
        infoItem.setItemMeta(infoMeta);
        playerMenu.setItem(10, infoItem);
        if (getConfigValue("features.player-teleportation")) {
            ItemStack teleportItem = new ItemStack(Material.ENDER_PEARL);
            ItemMeta teleportMeta = teleportItem.getItemMeta();
            teleportMeta.setDisplayName(ChatColor.GREEN + plugin.getMessage("gui.teleport", "Teleport"));
            teleportItem.setItemMeta(teleportMeta);
            playerMenu.setItem(11, teleportItem);
        }
        if (getConfigValue("features.inventory-inspection")) {
            ItemStack inventoryItem = new ItemStack(Material.CHEST);
            ItemMeta inventoryMeta = inventoryItem.getItemMeta();
            inventoryMeta.setDisplayName(ChatColor.AQUA + plugin.getMessage("gui.inspect-inventory", "Inspect Inventory"));
            inventoryItem.setItemMeta(inventoryMeta);
            playerMenu.setItem(14, inventoryItem);
        }
        if (getConfigValue("features.ender-chest-inspection")) {
            ItemStack enderChestItem = new ItemStack(Material.ENDER_CHEST);
            ItemMeta enderChestMeta = enderChestItem.getItemMeta();
            enderChestMeta.setDisplayName(ChatColor.DARK_PURPLE + plugin.getMessage("gui.ender-chest", "Inspect Ender Chest"));
            enderChestItem.setItemMeta(enderChestMeta);
            playerMenu.setItem(15, enderChestItem);
        }
        if (getConfigValue("features.punishment-system")) {
            ItemStack punishmentsItem = new ItemStack(Material.IRON_SWORD);
            ItemMeta punishmentsMeta = punishmentsItem.getItemMeta();
            punishmentsMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.punishments", "Punishments"));
            punishmentsItem.setItemMeta(punishmentsMeta);
            playerMenu.setItem(12, punishmentsItem);
        }
        if (getConfigValue("features.player-info-actions")) {
            ItemStack playerInfoItem = new ItemStack(Material.NAME_TAG);
            ItemMeta playerInfoMeta = playerInfoItem.getItemMeta();
            playerInfoMeta.setDisplayName(ChatColor.BLUE + plugin.getMessage("gui.player-info", "Player Info & Actions"));
            playerInfoItem.setItemMeta(playerInfoMeta);
            playerMenu.setItem(16, playerInfoItem);
        }
        ItemStack backItem = new ItemStack(Material.ARROW);
        ItemMeta backMeta = backItem.getItemMeta();
        backMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.back", "Back"));
        backItem.setItemMeta(backMeta);
        playerMenu.setItem(22, backItem);
        player.openInventory(playerMenu);
        setPlayerMenuTarget(player.getUniqueId(), result);
        setLastOpenedMenu(player.getUniqueId(), "player");
        setLastTarget(player.getUniqueId(), result.name);
        plugin.savePlayerMenuState(player);
    }

    public void openPlayerInfoGUI(Player player, PlayerResult result) {
        if (!player.hasPermission("playermanager.admin") && !player.hasPermission("playermanager.gui")) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.no-permission", "You don't have permission!"));
            return;
        }
        if (!getConfigValue("features.player-info-actions")) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.player-info-actions-disabled", "Player Info & Actions is disabled in config!"));
            return;
        }
        Inventory playerInfoGUI = Bukkit.createInventory(this, 27, plugin.getMessage("gui.player-info-prefix", "Player Info: ") + ChatColor.YELLOW + result.name);
        ItemStack headItem = createPlayerHead(result);
        playerInfoGUI.setItem(4, headItem);

        if (result.online) {
            Player target = Bukkit.getPlayer(result.name);
            ItemStack healthItem = new ItemStack(Material.APPLE);
            ItemMeta healthMeta = healthItem.getItemMeta();
            healthMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.health", "Health"));
            List<String> healthLore = new ArrayList<>();
            healthLore.add(ChatColor.GRAY + plugin.getMessage("gui.current-health", "Current Health") + ": " + ChatColor.WHITE + String.format("%.1f/%.1f", target.getHealth(), target.getMaxHealth()));
            healthMeta.setLore(healthLore);
            healthItem.setItemMeta(healthMeta);
            playerInfoGUI.setItem(10, healthItem);

            ItemStack healItem = new ItemStack(Material.GOLDEN_APPLE);
            ItemMeta healMeta = healItem.getItemMeta();
            healMeta.setDisplayName(ChatColor.GREEN + plugin.getMessage("gui.heal", "Heal"));
            healItem.setItemMeta(healMeta);
            playerInfoGUI.setItem(12, healItem);

            ItemStack killItem = new ItemStack(Material.SKELETON_SKULL);
            ItemMeta killMeta = killItem.getItemMeta();
            killMeta.setDisplayName(ChatColor.DARK_RED + plugin.getMessage("gui.kill", "Kill"));
            killItem.setItemMeta(killMeta);
            playerInfoGUI.setItem(14, killItem);

            ItemStack statsItem = new ItemStack(Material.PAPER);
            ItemMeta statsMeta = statsItem.getItemMeta();
            statsMeta.setDisplayName(ChatColor.YELLOW + plugin.getMessage("gui.stats", "Player Stats"));
            List<String> statsLore = new ArrayList<>();
            statsLore.add(ChatColor.GRAY + plugin.getMessage("gui.level", "Level") + ": " + ChatColor.WHITE + target.getLevel());
            statsLore.add(ChatColor.GRAY + plugin.getMessage("gui.hunger", "Hunger") + ": " + ChatColor.WHITE + target.getFoodLevel() + "/20");
            statsLore.add(ChatColor.GRAY + plugin.getMessage("gui.health", "Health") + ": " + ChatColor.WHITE + String.format("%.1f/%.1f", target.getHealth(), target.getMaxHealth()));
            statsLore.add(ChatColor.GRAY + plugin.getMessage("gui.location", "Location") + ": " + ChatColor.WHITE + String.format("X: %.1f, Y: %.1f, Z: %.1f", target.getLocation().getX(), target.getLocation().getY(), target.getLocation().getZ()));
            statsLore.add(ChatColor.GRAY + plugin.getMessage("gui.gamemode-gui", "Gamemode") + ": " + ChatColor.WHITE + target.getGameMode().toString());
            statsMeta.setLore(statsLore);
            statsItem.setItemMeta(statsMeta);
            playerInfoGUI.setItem(16, statsItem);
        }

        ItemStack gamemodeItem = new ItemStack(Material.COMMAND_BLOCK);
        ItemMeta gamemodeMeta = gamemodeItem.getItemMeta();
        gamemodeMeta.setDisplayName(ChatColor.BLUE + plugin.getMessage("gui.gamemode", "Change Gamemode"));
        gamemodeItem.setItemMeta(gamemodeMeta);
        playerInfoGUI.setItem(18, gamemodeItem);

        ItemStack backItem = new ItemStack(Material.ARROW);
        ItemMeta backMeta = backItem.getItemMeta();
        backMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.back", "Back"));
        backItem.setItemMeta(backMeta);
        playerInfoGUI.setItem(22, backItem);

        player.openInventory(playerInfoGUI);
        setPlayerMenuTarget(player.getUniqueId(), result);
        setLastOpenedMenu(player.getUniqueId(), "player_info");
        setLastTarget(player.getUniqueId(), result.name);
        plugin.savePlayerMenuState(player);
    }

    public void openGamemodeGUI(Player player, PlayerResult result) {
        if (!player.hasPermission("playermanager.admin") && !player.hasPermission("playermanager.gui") && !player.hasPermission("playermanager.gamemode")) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.no-permission", "You don't have permission!"));
            return;
        }
        Inventory gamemodeGUI = Bukkit.createInventory(this, 27, plugin.getMessage("gui.gamemode-prefix", "Change Gamemode: ") + ChatColor.YELLOW + result.name);
        ItemStack headItem = createPlayerHead(result);
        gamemodeGUI.setItem(4, headItem);

        ItemStack survivalItem = new ItemStack(Material.GRASS_BLOCK);
        ItemMeta survivalMeta = survivalItem.getItemMeta();
        survivalMeta.setDisplayName(ChatColor.GREEN + plugin.getMessage("gui.survival", "Survival"));
        survivalItem.setItemMeta(survivalMeta);
        gamemodeGUI.setItem(10, survivalItem);

        ItemStack creativeItem = new ItemStack(Material.DIAMOND);
        ItemMeta creativeMeta = creativeItem.getItemMeta();
        creativeMeta.setDisplayName(ChatColor.AQUA + plugin.getMessage("gui.creative", "Creative"));
        creativeItem.setItemMeta(creativeMeta);
        gamemodeGUI.setItem(12, creativeItem);

        ItemStack adventureItem = new ItemStack(Material.MAP);
        ItemMeta adventureMeta = adventureItem.getItemMeta();
        adventureMeta.setDisplayName(ChatColor.BLUE + plugin.getMessage("gui.adventure", "Adventure"));
        adventureItem.setItemMeta(adventureMeta);
        gamemodeGUI.setItem(14, adventureItem);

        ItemStack spectatorItem = new ItemStack(Material.FEATHER);
        ItemMeta spectatorMeta = spectatorItem.getItemMeta();
        spectatorMeta.setDisplayName(ChatColor.GRAY + plugin.getMessage("gui.spectator", "Spectator"));
        spectatorItem.setItemMeta(spectatorMeta);
        gamemodeGUI.setItem(16, spectatorItem);

        ItemStack backItem = new ItemStack(Material.ARROW);
        ItemMeta backMeta = backItem.getItemMeta();
        backMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.back", "Back"));
        backItem.setItemMeta(backMeta);
        gamemodeGUI.setItem(22, backItem);

        player.openInventory(gamemodeGUI);
        setPlayerMenuTarget(player.getUniqueId(), result);
        setLastOpenedMenu(player.getUniqueId(), "gamemode");
        setLastTarget(player.getUniqueId(), result.name);
        plugin.savePlayerMenuState(player);
    }

    public void openGamemodeConfirmGUI(Player player, PlayerResult result) {
        if (!player.hasPermission("playermanager.admin") && !player.hasPermission("playermanager.gui") && !player.hasPermission("playermanager.gamemode")) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.no-permission", "You don't have permission!"));
            return;
        }
        Inventory confirmGUI = Bukkit.createInventory(this, 27, plugin.getMessage("gui.confirm-prefix", "Confirm Creative: ") + ChatColor.YELLOW + result.name);
        ItemStack headItem = createPlayerHead(result);
        confirmGUI.setItem(4, headItem);

        ItemStack confirmItem = new ItemStack(Material.GREEN_STAINED_GLASS_PANE);
        ItemMeta confirmMeta = confirmItem.getItemMeta();
        confirmMeta.setDisplayName(ChatColor.GREEN + plugin.getMessage("gui.confirm", "Confirm"));
        confirmItem.setItemMeta(confirmMeta);
        confirmGUI.setItem(11, confirmItem);

        ItemStack cancelItem = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta cancelMeta = cancelItem.getItemMeta();
        cancelMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.cancel", "Cancel"));
        cancelItem.setItemMeta(cancelMeta);
        confirmGUI.setItem(15, cancelItem);

        ItemStack backItem = new ItemStack(Material.ARROW);
        ItemMeta backMeta = backItem.getItemMeta();
        backMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.back", "Back"));
        backItem.setItemMeta(backMeta);
        confirmGUI.setItem(22, backItem);

        player.openInventory(confirmGUI);
        setPlayerMenuTarget(player.getUniqueId(), result);
        setLastOpenedMenu(player.getUniqueId(), "gamemode_confirm");
        setLastTarget(player.getUniqueId(), result.name);
        plugin.savePlayerMenuState(player);
    }

    private boolean getConfigValue(String key) {
        return plugin.getConfig().getBoolean(key, true);
    }

    private void openPunishmentGUI(Player player, PlayerResult result) {
        if (!getConfigValue("features.punishment-system")) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.punishment-system-disabled", "Punishment system is disabled!"));
            return;
        }

        if (!player.hasPermission("playermanager.admin") && !player.hasPermission("playermanager.gui")) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.no-permission", "You don't have permission!"));
            return;
        }

        this.inventory = Bukkit.createInventory(this, 27, plugin.getMessage("gui.punishment-prefix", "Punishments: ") + result.name);

        ItemStack headItem = createPlayerHead(result);
        inventory.setItem(4, headItem);

        ItemStack[] items = new ItemStack[6];
        String[] materials = {"NETHERITE_SWORD", "BLAZE_ROD", "IRON_BARS", "PAPER", "BOOK", "EMERALD"};
        String[] keys = {"ban", "kick", "jail", "warn", "mute", "unban"};
        int[] slots = {10, 11, 12, 14, 15, 16};

        for (int i = 0; i < items.length; i++) {
            items[i] = new ItemStack(Material.valueOf(materials[i]));
            ItemMeta meta = items[i].getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', plugin.getMessage("punishment." + keys[i], keys[i])));
                List<String> lore = new ArrayList<>();

                if (i == 5) {
                    lore.add(ChatColor.GRAY + plugin.getMessage("gui.actions-lmb-unban", "LMB: Quick unban"));
                } else if (i == 2) {
                    lore.add(ChatColor.GRAY + plugin.getMessage("gui.actions-lmb-jail", "LMB: Open jail menu"));
                } else {
                    lore.add(ChatColor.GRAY + plugin.getMessage("gui.actions-lmb", "LMB: Reason"));
                    lore.add(ChatColor.GRAY + plugin.getMessage("gui.actions-shift-rmb", "Shift+RMB: Quick " + keys[i]));
                }

                meta.setLore(lore);
                items[i].setItemMeta(meta);
            }
            this.inventory.setItem(slots[i], items[i]);
        }

        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        if (backMeta != null) {
            backMeta.setDisplayName(ChatColor.translateAlternateColorCodes('&', plugin.getMessage("gui.back", "Back")));
            back.setItemMeta(backMeta);
        }
        this.inventory.setItem(22, back);

        player.openInventory(this.inventory);
        setLastOpenedMenu(player.getUniqueId(), "punishment");
        setPlayerMenuTarget(player.getUniqueId(), result);
        setLastTarget(player.getUniqueId(), result.name);
        plugin.savePlayerMenuState(player);
    }
    private void openJailGUI(Player player, PlayerResult result) {
        if (!getConfigValue("features.punishment-system")) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.punishment-system-disabled", "Punishment system is disabled!"));
            return;
        }

        if (!player.hasPermission("playermanager.admin") && !player.hasPermission("playermanager.gui")) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.no-permission", "You don't have permission!"));
            return;
        }

        this.inventory = Bukkit.createInventory(this, 27, plugin.getMessage("gui.jail-prefix", "Jail: ") + result.name);
        ItemStack headItem = createPlayerHead(result);
        inventory.setItem(4, headItem);

        ItemStack jail = new ItemStack(Material.IRON_BARS);
        ItemMeta jailMeta = jail.getItemMeta();
        if (jailMeta != null) {
            jailMeta.setDisplayName(ChatColor.translateAlternateColorCodes('&', plugin.getMessage("punishment.jail", "Jail")));
            List<String> jailLore = new ArrayList<>();
            jailLore.add(ChatColor.GRAY + plugin.getMessage("gui.actions-lmb", "LMB: Reason"));
            jailLore.add(ChatColor.GRAY + plugin.getMessage("gui.actions-shift-rmb", "Shift+RMB: Quick jail"));
            jailMeta.setLore(jailLore);
            jail.setItemMeta(jailMeta);
        }
        this.inventory.setItem(12, jail);

        ItemStack unjail = new ItemStack(Material.EMERALD);
        ItemMeta unjailMeta = unjail.getItemMeta();
        if (unjailMeta != null) {
            unjailMeta.setDisplayName(ChatColor.translateAlternateColorCodes('&', plugin.getMessage("punishment.unjail", "Unjail")));
            List<String> unjailLore = new ArrayList<>();
            unjailLore.add(ChatColor.GRAY + plugin.getMessage("gui.actions-lmb-unjail", "LMB: Quick unjail"));
            unjailMeta.setLore(unjailLore);
            unjail.setItemMeta(unjailMeta);
        }
        this.inventory.setItem(14, unjail);

        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        if (backMeta != null) {
            backMeta.setDisplayName(ChatColor.translateAlternateColorCodes('&', plugin.getMessage("gui.back", "Back")));
            back.setItemMeta(backMeta);
        }
        this.inventory.setItem(22, back);

        player.openInventory(this.inventory);
        setLastOpenedMenu(player.getUniqueId(), "jail");
        setPlayerMenuTarget(player.getUniqueId(), result);
        setLastTarget(player.getUniqueId(), result.name);
        plugin.savePlayerMenuState(player);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getInventory().equals(inventory)) {
        }
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        if (event.getInventory().getHolder() != this) return;
        event.setCancelled(true);
        if (event.getCurrentItem() == null || event.getCurrentItem().getType() == Material.AIR) return;
        int slot = event.getRawSlot();
        String currentMenu = lastOpenedMenu.getOrDefault(player.getUniqueId(), "search");
        if (currentMenu.equals("search")) {
            handleSearchClick(event, player, slot);
        } else if (currentMenu.equals("player")) {
            handlePlayerMenuClick(event, player, slot);
        } else if (currentMenu.equals("punishment")) {
            handlePunishmentClick(event, player, slot);
        } else if (currentMenu.equals("jail")) {
            handleJailClick(event, player, slot);
        } else if (currentMenu.equals("player_info")) {
            handlePlayerInfoClick(event, player, slot);
        } else if (currentMenu.equals("gamemode")) {
            handleGamemodeClick(event, player, slot);
        } else if (currentMenu.equals("gamemode_confirm")) {
            handleGamemodeConfirmClick(event, player, slot);
        }
    }
    private final Map<UUID, Long> lastPageChangeTime = new HashMap<>();

    private void handleSearchClick(InventoryClickEvent event, Player player, int slot) {
        if (slot == 4) {
            if (event.getClick() == ClickType.LEFT) {
                player.closeInventory();
                String cancelText = plugin.getMessage("gui.cancel", "[Cancel]");
                TextComponent message = new TextComponent(ChatColor.YELLOW + plugin.getMessage("gui.enter-search", "Enter name to search: ") + " ");
                TextComponent cancel = new TextComponent(ChatColor.RED + cancelText);
                cancel.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/playermanager reset"));
                message.addExtra(cancel);
                player.spigot().sendMessage(message);
                pendingActions.put(player.getUniqueId(), (msg, p) -> {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        String input = ChatColor.stripColor(msg.trim());
                        String cancelTextRu = ChatColor.stripColor(plugin.getMessage("gui.cancel", "[Cancel]").replaceAll("[\\[\\]]", ""));
                        String cancelTextEn = "Cancel";
                        if (input.equalsIgnoreCase(cancelTextRu) || input.equalsIgnoreCase(cancelTextEn)) {
                            resetSearch(p);
                            return;
                        }
                        currentSearch = input;
                        currentPage = 0;
                        lastPage.put(p.getUniqueId(), 0);
                        cachedResults.clear();
                        p.openInventory(inventory);
                        playersInGUI.add(p.getUniqueId());
                        setLastOpenedMenu(p.getUniqueId(), "search");
                        plugin.savePlayerMenuState(p);
                        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                            List<PlayerResult> results = getSearchResults(p, currentSearch);
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                if (p.isOnline() && playersInGUI.contains(p.getUniqueId())) {
                                    setupSearchGUI(p, results);
                                    p.updateInventory();
                                    pendingActions.remove(p.getUniqueId());
                                }
                            });
                        });
                    });
                });
            } else if (event.getClick() == ClickType.RIGHT) {
                resetSearch(player);
            }
            return;
        }
        if (slot == 49) {
            player.closeInventory();
            pendingActions.remove(player.getUniqueId());
            return;
        }
        if (slot == 45 && currentPage > 0) {
            long currentTime = System.currentTimeMillis();
            long lastChange = lastPageChangeTime.getOrDefault(player.getUniqueId(), 0L);
            if (currentTime - lastChange < 1000) {
                return;
            }
            lastPageChangeTime.put(player.getUniqueId(), currentTime);
            if (event.getClick() == ClickType.SHIFT_RIGHT) {
                currentPage = Math.max(0, currentPage - 5);
            } else {
                currentPage--;
            }
            lastPage.put(player.getUniqueId(), currentPage);
            List<PlayerResult> results = cachedResults.isEmpty() ? getSearchResults(player, currentSearch) : cachedResults;
            setupSearchGUI(player, results);
            player.updateInventory();
            plugin.savePlayerMenuState(player);
            return;
        }
        if (slot == 53) {
            long currentTime = System.currentTimeMillis();
            long lastChange = lastPageChangeTime.getOrDefault(player.getUniqueId(), 0L);
            if (currentTime - lastChange < 1000) {
                return;
            }
            lastPageChangeTime.put(player.getUniqueId(), currentTime);
            List<PlayerResult> results = cachedResults.isEmpty() ? getSearchResults(player, currentSearch) : cachedResults;
            boolean hasNextPage = (currentPage + 1) * 36 < results.size();
            if (hasNextPage) {
                if (event.getClick() == ClickType.SHIFT_RIGHT) {
                    currentPage = Math.min(currentPage + 5, (int) Math.ceil((double) results.size() / 36) - 1);
                } else {
                    currentPage++;
                }
                lastPage.put(player.getUniqueId(), currentPage);
                setupSearchGUI(player, results);
                player.updateInventory();
                plugin.savePlayerMenuState(player);
            }
            return;
        }
        if (slot >= 47 && slot <= 51) {
            Filter newFilter = null;
            switch (slot) {
                case 47:
                    newFilter = Filter.ALL;
                    break;
                case 48:
                    newFilter = Filter.ONLINE;
                    break;
                case 50:
                    newFilter = Filter.OFFLINE;
                    break;
                case 51:
                    newFilter = Filter.BANNED;
                    break;
            }
            if (newFilter != null && newFilter != currentFilter) {
                currentFilter = newFilter;
                currentPage = 0;
                lastPage.put(player.getUniqueId(), 0);
                cachedResults.clear();
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    List<PlayerResult> results = getSearchResults(player, currentSearch);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline() && playersInGUI.contains(player.getUniqueId())) {
                            setupSearchGUI(player, results);
                            player.updateInventory();
                            plugin.savePlayerMenuState(player);
                        }
                    });
                });
            }
            return;
        }
        PlayerResult result = slotMap.get(slot);
        if (result == null) return;
        if (event.getClick() == ClickType.LEFT) {
            openPlayerMenu(player, result);
        } else if (event.getClick() == ClickType.RIGHT) {
            if (!getConfigValue("features.player-teleportation")) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.teleportation-disabled", "Teleportation is disabled!"));
                return;
            }
            Player target = Bukkit.getPlayer(result.name);
            if (target == null) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.player-offline", "Player is offline."));
                return;
            }
            player.teleport(target);
            player.sendMessage(ChatColor.GREEN + plugin.getMessage("action.teleported", "Teleported to %player%").replace("%player%", result.name));
        } else if (event.getClick() == ClickType.SHIFT_RIGHT) {
            if (!getConfigValue("features.punishment-system")) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.punishment-system-disabled", "Punishment system is disabled!"));
                return;
            }
            openPunishmentGUI(player, result);
        }
    }

    private void handlePlayerMenuClick(InventoryClickEvent event, Player player, int slot) {
        PlayerResult result = playerMenuTargets.get(player.getUniqueId());
        if (result == null) return;
        if (slot == 22) {
            openSearchGUI(player);
            return;
        }
        if (slot == 11 && getConfigValue("features.player-teleportation")) {
            Player target = Bukkit.getPlayer(result.name);
            if (target == null) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.player-offline", "Player is offline."));
                return;
            }
            if (target == player) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.self-teleport", "You can't teleport to yourself."));
                return;
            }
            player.teleport(target);
            player.sendMessage(ChatColor.GREEN + plugin.getMessage("action.teleported", "Teleported to %player%").replace("%player%", result.name));
            return;
        }
        if (slot == 14 && getConfigValue("features.inventory-inspection")) {
            Player target = Bukkit.getPlayer(result.name);
            if (target == null) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.player-offline-inventory", "Player is offline, inventory modification is impossible."));
                return;
            }
            player.openInventory(target.getInventory());
            return;
        }
        if (slot == 15 && getConfigValue("features.ender-chest-inspection")) {
            Player target = Bukkit.getPlayer(result.name);
            if (target == null) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.player-offline-inventory", "Player is offline, inventory modification is impossible."));
                return;
            }
            player.openInventory(target.getEnderChest());
            return;
        }
        if (slot == 12 && getConfigValue("features.punishment-system")) {
            openPunishmentGUI(player, result);
            return;
        }
        if (slot == 16 && getConfigValue("features.player-info-actions")) {
            Player target = Bukkit.getPlayer(result.name);
            if (target == null) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.player-offline", "Player is offline."));
                return;
            }
            openPlayerInfoGUI(player, result);
            return;
        }
    }

    private void handlePunishmentClick(InventoryClickEvent event, Player player, int slot) {
        PlayerResult result = playerMenuTargets.get(player.getUniqueId());
        if (result == null) return;

        if (slot == 22) {
            openPlayerMenu(player, result);
            return;
        }

        if (player.getUniqueId().toString().equals(result.uuid)) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.self-punish", "You can't punish yourself."));
            return;
        }

        int index = -1;
        if (slot == 10) index = 0;
        else if (slot == 11) index = 1;
        else if (slot == 12) index = 2;
        else if (slot == 14) index = 3;
        else if (slot == 15) index = 4;
        else if (slot == 16) index = 5;

        if (index == -1) return;

        String command = null;
        switch (index) {
            case 0: command = "ban"; break;
            case 1: command = "kick"; break;
            case 2: command = "jail"; break;
            case 3: command = "warn"; break;
            case 4: command = "mute"; break;
            case 5: command = "unban"; break;
        }

        boolean needsSave = false;
        String path = "players." + result.uuid + ".";

        if (event.getClick() == ClickType.LEFT) {
            if (index == 5) {
                if (!result.banned) {
                    player.sendMessage(ChatColor.RED + plugin.getMessage("error.not-banned", "This player is not banned!"));
                    return;
                }
                Bukkit.dispatchCommand(player, command + " " + result.name);
                player.sendMessage(ChatColor.YELLOW + plugin.getMessage("action.executed", "Executed: %command% for %player%")
                        .replace("%command%", command)
                        .replace("%player%", result.name));
                result.banned = false;
                plugin.getPlayerDataConfig().set(path + "banned", false);
                needsSave = true;
            } else if (index == 2) {
                openJailGUI(player, result);
            } else {
                if (index != 0 && !result.online) {
                    player.sendMessage(ChatColor.RED + plugin.getMessage("error.player-offline", "Player is offline."));
                    return;
                }
                if (index == 0 && result.banned) {
                    player.sendMessage(ChatColor.RED + plugin.getMessage("error.already-banned", "This player is already banned!"));
                    return;
                }
                player.sendMessage(ChatColor.RED + plugin.getMessage("action.click-to-copy", "Click to copy"));
                TextComponent msg = new TextComponent(ChatColor.RED + "/" + command + " " + result.name + " ");
                msg.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/" + command + " " + result.name + " "));
                player.spigot().sendMessage(msg);
                player.closeInventory();
            }
        } else if (event.getClick() == ClickType.SHIFT_RIGHT && index != 5) {
            if (index != 0 && !result.online) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.player-offline", "Player is offline."));
                return;
            }
            if (index == 0 && result.banned) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.already-banned", "This player is already banned!"));
                return;
            }
            Bukkit.dispatchCommand(player, command + " " + result.name);
            player.sendMessage(ChatColor.YELLOW + plugin.getMessage("action.executed", "Executed: %command% for %player%")
                    .replace("%command%", command)
                    .replace("%player%", result.name));
            if (index == 0) {
                result.banned = true;
                plugin.getPlayerDataConfig().set(path + "banned", true);
                needsSave = true;
            }
            player.closeInventory();
        } else {
            return;
        }

        if (needsSave) {
            plugin.savePlayerDataConfig();
        }
        if (index != 5 && event.getClick() != ClickType.LEFT) {
            openPunishmentGUI(player, result);
        }
        if (index != 2 && event.getClick() != ClickType.LEFT) {
            openPunishmentGUI(player, result);
        }
        refreshOpenGUIs();
        event.setCancelled(true);
    }

    private void handleJailClick(InventoryClickEvent event, Player player, int slot) {
        PlayerResult result = playerMenuTargets.get(player.getUniqueId());
        if (result == null) return;

        if (slot == 22) {
            openPunishmentGUI(player, result);
            return;
        }

        if (player.getUniqueId().toString().equals(result.uuid)) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.self-punish", "You can't punish yourself."));
            return;
        }

        String command = null;
        if (slot == 12) {
            command = "jail";
        } else if (slot == 14) {
            command = "unjail";
        } else {
            return;
        }

        if (event.getClick() == ClickType.LEFT) {
            if (slot == 14) {
                Player target = Bukkit.getPlayer(result.name);
                if (target == null) {
                    player.sendMessage(ChatColor.RED + plugin.getMessage("error.player-offline", "Player is offline."));
                    return;
                }
                Bukkit.dispatchCommand(player, command + " " + result.name);
                player.sendMessage(ChatColor.YELLOW + plugin.getMessage("action.executed", "Executed: %command% for %player%")
                        .replace("%command%", command)
                        .replace("%player%", result.name));
            } else {
                if (!result.online) {
                    player.sendMessage(ChatColor.RED + plugin.getMessage("error.player-offline", "Player is offline."));
                    return;
                }
                TextComponent msg = new TextComponent(ChatColor.YELLOW + "/" + command + " " + result.name + " ");
                msg.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/" + command + " " + result.name + " "));
                player.spigot().sendMessage(msg);
                player.sendMessage(ChatColor.GRAY + plugin.getMessage("action.click-to-copy", "Click to copy"));
                player.closeInventory();
            }
        } else if (event.getClick() == ClickType.SHIFT_RIGHT && slot == 12) {
            if (!result.online) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.player-offline", "Player is offline."));
                return;
            }
            Bukkit.dispatchCommand(player, command + " " + result.name);
            player.sendMessage(ChatColor.YELLOW + plugin.getMessage("action.executed", "Executed: %command% for %player%")
                    .replace("%command%", command)
                    .replace("%player%", result.name));
            player.closeInventory();
        } else {
            return;
        }

        if (slot != 14) {
            openJailGUI(player, result);
        }
        refreshOpenGUIs();
        event.setCancelled(true);
    }
    private void handlePlayerInfoClick(InventoryClickEvent event, Player player, int slot) {
        PlayerResult result = playerMenuTargets.get(player.getUniqueId());
        if (result == null) return;
        if (slot == 22) {
            openPlayerMenu(player, result);
            return;
        }
        if (!result.online) {
            if (slot == 10 || slot == 12 || slot == 14 || slot == 16) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.player-offline", "Player is offline."));
                return;
            }
        }
        if (slot == 12) {
            Player target = Bukkit.getPlayer(result.name);
            if (player.getUniqueId().toString().equals(result.uuid)) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.self-punish", "You can't punish yourself."));
                return;
            }
            Bukkit.dispatchCommand(player, "heal " + result.name);
            player.sendMessage(ChatColor.YELLOW + plugin.getMessage("action.executed", "Executed: %command% for %player%")
                    .replace("%command%", "heal")
                    .replace("%player%", result.name));
            openPlayerInfoGUI(player, result);
            return;
        }
        if (slot == 14) {
            Player target = Bukkit.getPlayer(result.name);
            if (player.getUniqueId().toString().equals(result.uuid)) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.self-punish", "You can't punish yourself."));
                return;
            }
            Bukkit.dispatchCommand(player, "kill " + result.name);
            player.sendMessage(ChatColor.YELLOW + plugin.getMessage("action.executed", "Executed: %command% for %player%")
                    .replace("%command%", "kill")
                    .replace("%player%", result.name));
            openPlayerInfoGUI(player, result);
            return;
        }
        if (slot == 18) {
            openGamemodeGUI(player, result);
            return;
        }
    }

    private void handleGamemodeClick(InventoryClickEvent event, Player player, int slot) {
        PlayerResult result = playerMenuTargets.get(player.getUniqueId());
        if (result == null) return;
        if (slot == 22) {
            openPlayerInfoGUI(player, result);
            return;
        }
        if (!result.online) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.player-offline", "Player is offline."));
            return;
        }
        if (player.getUniqueId().toString().equals(result.uuid)) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.self-punish", "You can't punish yourself."));
            return;
        }
        String gamemode = null;
        if (slot == 10) {
            gamemode = "survival";
        } else if (slot == 12) {
            openGamemodeConfirmGUI(player, result);
            return;
        } else if (slot == 14) {
            gamemode = "adventure";
        } else if (slot == 16) {
            gamemode = "spectator";
        }
        if (gamemode != null) {
            Bukkit.dispatchCommand(player, "gamemode " + gamemode + " " + result.name);
            player.sendMessage(ChatColor.YELLOW + plugin.getMessage("action.executed", "Executed: %command% for %player%")
                    .replace("%command%", "gamemode " + gamemode)
                    .replace("%player%", result.name));
            openPlayerInfoGUI(player, result);
        }
    }

    private void handleGamemodeConfirmClick(InventoryClickEvent event, Player player, int slot) {
        PlayerResult result = playerMenuTargets.get(player.getUniqueId());
        if (result == null) return;
        if (slot == 22) {
            openGamemodeGUI(player, result);
            return;
        }
        if (slot == 11) {
            if (!result.online) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.player-offline", "Player is offline."));
                return;
            }
            if (player.getUniqueId().toString().equals(result.uuid)) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.self-punish", "You can't punish yourself."));
                return;
            }
            Bukkit.dispatchCommand(player, "gamemode creative " + result.name);
            player.sendMessage(ChatColor.YELLOW + plugin.getMessage("action.executed", "Executed: %command% for %player%")
                    .replace("%command%", "gamemode creative")
                    .replace("%player%", result.name));
            openPlayerInfoGUI(player, result);
            return;
        }
        if (slot == 15) {
            openGamemodeGUI(player, result);
            return;
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("playermanager.admin") && !player.hasPermission("playermanager.gui")) {
            return;
        }

        if (event.getAction() == Action.RIGHT_CLICK_BLOCK || event.getItem() != null) {
            return;
        }

        boolean punishmentEnabled = getConfigValue("features.punishment-system");
        boolean teleportEnabled = getConfigValue("features.player-teleportation");
        boolean inventoryEnabled = getConfigValue("features.inventory-inspection");
        boolean enderChestEnabled = getConfigValue("features.ender-chest-inspection");
        boolean playerInfoEnabled = getConfigValue("features.player-info-actions");
        boolean allFeaturesDisabled = !punishmentEnabled && !teleportEnabled && !inventoryEnabled && !enderChestEnabled && !playerInfoEnabled;

        if (event.getAction() == Action.RIGHT_CLICK_AIR && player.isSneaking()) {
            if (allFeaturesDisabled) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.all-features-disabled", "All features are disabled in config!"));
                return;
            }
            openLastGUIMenu(player);
            event.setCancelled(true);
        }
    }

    public Inventory getInventory() {
        return this.inventory;
    }

    public void refreshOpenGUIs() {
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            Iterator<UUID> iterator = this.playersInGUI.iterator();
            while (iterator.hasNext()) {
                UUID playerId = iterator.next();
                Player player = Bukkit.getPlayer(playerId);
                if (player == null || !player.isOnline() || (!player.hasPermission("playermanager.admin") && !player.hasPermission("playermanager.gui"))) {
                    iterator.remove();
                } else if (player.getOpenInventory().getTopInventory().getHolder() instanceof PlayerSearchGUI) {
                    String currentMenu = lastOpenedMenu.getOrDefault(player.getUniqueId(), "search");
                    if (currentMenu.equals("search")) {
                        List<PlayerResult> results = cachedResults.isEmpty() ? getSearchResults(player, currentSearch) : cachedResults;
                        setupSearchGUI(player, results);
                        player.updateInventory();
                    } else if (currentMenu.equals("player")) {
                        PlayerResult result = playerMenuTargets.get(player.getUniqueId());
                        if (result != null) {
                            openPlayerMenu(player, result);
                        } else {
                            List<PlayerResult> results = cachedResults.isEmpty() ? getSearchResults(player, currentSearch) : cachedResults;
                            setupSearchGUI(player, results);
                        }
                    } else if (currentMenu.equals("punishment")) {
                        PlayerResult result = playerMenuTargets.get(player.getUniqueId());
                        if (result != null) {
                            openPunishmentGUI(player, result);
                        } else {
                            List<PlayerResult> results = cachedResults.isEmpty() ? getSearchResults(player, currentSearch) : cachedResults;
                            setupSearchGUI(player, results);
                        }
                    } else if (currentMenu.equals("player_info")) {
                        PlayerResult result = playerMenuTargets.get(player.getUniqueId());
                        if (result != null) {
                            openPlayerInfoGUI(player, result);
                        } else {
                            List<PlayerResult> results = cachedResults.isEmpty() ? getSearchResults(player, currentSearch) : cachedResults;
                            setupSearchGUI(player, results);
                        }
                    } else if (currentMenu.equals("gamemode")) {
                        PlayerResult result = playerMenuTargets.get(player.getUniqueId());
                        if (result != null) {
                            openGamemodeGUI(player, result);
                        } else {
                            List<PlayerResult> results = cachedResults.isEmpty() ? getSearchResults(player, currentSearch) : cachedResults;
                            setupSearchGUI(player, results);
                        }
                    } else if (currentMenu.equals("gamemode_confirm")) {
                        PlayerResult result = playerMenuTargets.get(player.getUniqueId());
                        if (result != null) {
                            openGamemodeConfirmGUI(player, result);
                        } else {
                            List<PlayerResult> results = cachedResults.isEmpty() ? getSearchResults(player, currentSearch) : cachedResults;
                            setupSearchGUI(player, results);
                        }
                    }
                } else {
                    iterator.remove();
                }
            }
        });
    }

    public void resetSearch(Player player) {
        if (!player.hasPermission("playermanager.admin") && !player.hasPermission("playermanager.gui")) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.no-permission", "You don't have permission!"));
            return;
        }
        this.currentSearch = "";
        this.currentFilter = Filter.ALL;
        this.currentPage = 0;
        this.cachedResults.clear();
        this.lastPage.put(player.getUniqueId(), 0);
        this.lastTarget.remove(player.getUniqueId());
        this.setLastOpenedMenu(player.getUniqueId(), "search");
        player.closeInventory();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<PlayerResult> results = getSearchResults(player, "");
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    this.inventory = Bukkit.createInventory(this, 54, plugin.getMessage("gui.title", "Player Management"));
                    setupSearchGUI(player, results);
                    player.openInventory(this.inventory);
                    playersInGUI.add(player.getUniqueId());
                    player.updateInventory();
                    player.sendMessage(ChatColor.GREEN + plugin.getMessage("action.search-cancelled", "Search cancelled"));
                    pendingActions.remove(player.getUniqueId());
                    plugin.savePlayerMenuState(player);
                }
            });
        });
    }

    public void setPlayerMenuTarget(UUID playerId, PlayerResult result) {
        this.playerMenuTargets.put(playerId, result);
    }

    public String getLastOpenedMenu(UUID playerId) {
        return this.lastOpenedMenu.getOrDefault(playerId, "search");
    }

    public void setLastOpenedMenu(UUID playerId, String menu) {
        this.lastOpenedMenu.put(playerId, menu);
    }

    public int getLastPage(UUID playerId) {
        return this.lastPage.getOrDefault(playerId, 0);
    }

    public void setLastPage(UUID playerId, int page) {
        this.lastPage.put(playerId, page);
    }

    public String getLastTarget(UUID playerId) {
        return this.lastTarget.get(playerId);
    }

    public void setLastTarget(UUID playerId, String target) {
        if (target != null) {
            this.lastTarget.put(playerId, target);
        } else {
            this.lastTarget.remove(playerId);
        }
    }

    private String formatDate(long timestamp) {
        if (timestamp == 0) return "-";
        return this.dateFormat.format(new Date(timestamp));
    }

    private String formatTimeAgo(long timestamp) {
        if (timestamp == 0) return "-";
        long seconds = (System.currentTimeMillis() - timestamp) / 1000L;
        if (seconds < 60L) {
            return seconds + " " + plugin.getMessage("time.seconds-ago", "seconds ago");
        } else if (seconds < 3600L) {
            return seconds / 60L + " " + plugin.getMessage("time.minutes-ago", "minutes ago");
        } else {
            return seconds < 86400L ?
                    seconds / 3600L + " " + plugin.getMessage("time.hours-ago", "hours ago") :
                    seconds / 86400L + " " + plugin.getMessage("time.days-ago", "days ago");
        }
    }

    enum Filter {
        ALL("COMPASS", ChatColor.YELLOW, "filter.all"),
        ONLINE("LIME_DYE", ChatColor.GREEN, "filter.online"),
        OFFLINE("GRAY_DYE", ChatColor.RED, "filter.offline"),
        BANNED("REDSTONE", ChatColor.DARK_RED, "filter.banned");

        private final String material;
        private final ChatColor color;
        private final String messageKey;

        Filter(String material, ChatColor color, String messageKey) {
            this.material = material;
            this.color = color;
            this.messageKey = messageKey;
        }

        public Material getMaterial() {
            try {
                return Material.valueOf(material);
            } catch (IllegalArgumentException e) {
                return Material.STONE;
            }
        }

        public ChatColor getColor() {
            return color;
        }

        public String getDisplayName(PlayerManager plugin) {
            return plugin.getMessage(messageKey, this.name());
        }
    }

    static class PlayerResult {
        String uuid;
        String name;
        long firstPlayed;
        long lastLogin;
        long lastLogout;
        boolean online;
        boolean banned;
        String headTexture;
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        ChatAction action = pendingActions.get(player.getUniqueId());
        if (action != null) {
            event.setCancelled(true);
            action.execute(event.getMessage(), player);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player) {
            Player player = (Player) event.getPlayer();
            if (event.getInventory().getHolder() instanceof PlayerSearchGUI) {
                playersInGUI.remove(player.getUniqueId());
                pendingActions.remove(player.getUniqueId());
            }
        }
    }
}