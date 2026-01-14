/**
 * MIT License
 *
 * PlayerManager
 * Copyright (c) 2026 Stepanyaa

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

import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import java.lang.reflect.Field;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import org.bukkit.scheduler.BukkitRunnable;

import static jdk.tools.jlink.internal.plugins.PluginsResourceBundle.getMessage;

public class PlayerSearchGUI implements Listener, InventoryHolder {
    private final PlayerManager plugin;
    private final PlayerManagerIpGUI ipGUI;
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
    private final Map<UUID, Long> lastFilterClick = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRefreshClick = new ConcurrentHashMap<>();
    private boolean isValidUUID(String str) {
        if (str == null || str.isEmpty()) return false;
        String clean = str.replace("-", "");
        if (clean.length() < 32) return false;
        return clean.matches("[0-9a-fA-F]{32}");
    }

    @FunctionalInterface
    interface ChatAction {
        void execute(String message, Player player);
    }

    private List<PlayerResult> cachedResults = new ArrayList<>();
    private List<PlayerResult> unfilteredResults = new ArrayList<>();

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
        this.ipGUI = new PlayerManagerIpGUI(plugin, this);
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
            openSearchGUI(admin);
        } else if (lastMenu.equals("ban_type") && lastTargetName != null) {
            PlayerResult result = getPlayerResultByName(lastTargetName);
            if (result != null) {
                openBanTypeGUI(admin, result);
                return;
            }
        } else if (lastMenu.equals("ban_duration") && lastTargetName != null) {
            PlayerResult result = getPlayerResultByName(lastTargetName);
            if (result != null) {
                openBanDurationGUI(admin, result);
                return;
            }
        } else if (lastMenu.equals("ban_reason") && lastTargetName != null) {
            PlayerResult result = getPlayerResultByName(lastTargetName);
            if (result != null) {
                String duration = plugin.getPlayerDataConfig().getString("players." + result.uuid + ".pending_ban_duration", "permanent");
                openBanReasonGUI(admin, result, duration);
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
                if (result.headTexture.isEmpty() && result.online) {
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
        if (!player.isOnline()) {
            return "";
        }
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

    public void setupSearchGUI(Player player, List<PlayerResult> results) {
        this.inventory.clear();
        this.slotMap.clear();

        ItemStack refreshItem = new ItemStack(Material.CLOCK);
        ItemMeta refreshMeta = refreshItem.getItemMeta();
        if (refreshMeta != null) {
            refreshMeta.setDisplayName(ChatColor.GREEN + plugin.getMessage("gui.refresh", "Refresh List"));
            List<String> refreshLore = new ArrayList<>();
            refreshLore.add(ChatColor.GRAY + plugin.getMessage("gui.refresh-hint", "Click to refresh player list"));
            refreshMeta.setLore(refreshLore);
            refreshItem.setItemMeta(refreshMeta);
        }
        this.inventory.setItem(0, refreshItem);

        if (results.isEmpty()) {
            ItemStack noResultsItem = new ItemStack(Material.BARRIER);
            ItemMeta meta = noResultsItem.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.no-results", "No players found"));
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "Try changing the search query or filter");
                meta.setLore(lore);
                noResultsItem.setItemMeta(meta);
            }
            this.inventory.setItem(22, noResultsItem);
        }

        if (player.hasPermission("playermanager.ip-search")) {
            if (plugin.getConfig().getBoolean("features.ip-search", true)) {
                ItemStack ipSearchItem = new ItemStack(Material.PAPER);
                ItemMeta ipSearchMeta = ipSearchItem.getItemMeta();
                if (ipSearchMeta != null) {
                    ipSearchMeta.setDisplayName(ChatColor.GREEN + plugin.getMessage("gui.ip-search", "IP Search"));
                    List<String> ipSearchLore = new ArrayList<>();
                    ipSearchLore.add(ChatColor.GRAY + plugin.getMessage("gui.ip-search-hint", "Left click: Search by IP"));
                    ipSearchMeta.setLore(ipSearchLore);
                    ipSearchItem.setItemMeta(ipSearchMeta);
                }
                this.inventory.setItem(1, ipSearchItem);
            } else {
                plugin.getLogger().info("IP search feature is disabled in config.");
            }
        }

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
            searchLore.add(ChatColor.GRAY + plugin.getMessage("gui.search-hint", "Left click: Enter query | Right click: Reset search"));
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
                noNextMeta.setLore(noNextLore);
                noNext.setItemMeta(noNextMeta);
            }
            this.inventory.setItem(53, noNext);
        }
    }

    public void setCurrentSearch(String search) {
        this.currentSearch = search;
    }

    public void setCurrentPage(int page) {
        this.currentPage = page;
    }

    public void setLastPage(UUID playerId, int page) {
        this.lastPage.put(playerId, page);
    }

    public void setCachedResults(List<PlayerResult> results) {
        this.cachedResults = new ArrayList<>(results);
        this.unfilteredResults = new ArrayList<>(results);
    }


    private List<PlayerResult> getSearchResults(Player admin, String search) {
        List<PlayerResult> results = new ArrayList<>();
        ConfigurationSection playersSection = plugin.getPlayerDataConfig().getConfigurationSection("players");
        if (playersSection == null) return results;

        String lowerSearch = search.toLowerCase().replace("-", "");
        boolean isUUIDSearch = isValidUUID(lowerSearch);

        long maxInactivityDays = plugin.getConfig().getLong("max-inactivity-days", 30);
        long maxInactivityMillis = maxInactivityDays * 24 * 60 * 60 * 1000L;
        long currentTime = System.currentTimeMillis();

        for (String uuid : playersSection.getKeys(false)) {
            String path = "players." + uuid + ".";
            String name = plugin.getPlayerDataConfig().getString(path + "name", "").toLowerCase();

            long lastActivity = Math.max(
                    plugin.getPlayerDataConfig().getLong(path + "last_login", 0L),
                    plugin.getPlayerDataConfig().getLong(path + "last_logout", 0L)
            );
            if (lastActivity > 0 && (currentTime - lastActivity) > maxInactivityMillis) {
                continue;
            }

            boolean matches = false;

            if (isUUIDSearch) {
                String cleanUUID = uuid.replace("-", "").toLowerCase();
                if (cleanUUID.contains(lowerSearch)) {
                    matches = true;
                }
            } else {
                if (name.contains(lowerSearch)) {
                    matches = true;
                }
            }

            if (matches && currentFilter != Filter.ALL) {
                OfflinePlayer offline = Bukkit.getOfflinePlayer(UUID.fromString(uuid));
                boolean online = offline.isOnline();
                boolean banned = offline.isBanned();

                if (currentFilter == Filter.ONLINE && !online) matches = false;
                if (currentFilter == Filter.OFFLINE && online) matches = false;
                if (currentFilter == Filter.BANNED && !banned) matches = false;
            }

            if (matches) {
                PlayerResult result = new PlayerResult();
                result.uuid = uuid;
                result.name = plugin.getPlayerDataConfig().getString(path + "name", "Unknown");
                result.firstPlayed = plugin.getPlayerDataConfig().getLong(path + "first_played", 0L);
                result.lastLogin = plugin.getPlayerDataConfig().getLong(path + "last_login", 0L);
                result.lastLogout = plugin.getPlayerDataConfig().getLong(path + "last_logout", 0L);
                result.online = Bukkit.getPlayer(UUID.fromString(uuid)) != null;
                result.banned = Bukkit.getBanList(org.bukkit.BanList.Type.NAME).isBanned(result.name);
                result.headTexture = plugin.getPlayerDataConfig().getString(path + "head_texture", "");
                results.add(result);
            }
        }

        results.sort((a, b) -> {
            if (currentFilter == Filter.ONLINE || currentFilter == Filter.OFFLINE) {
                return Boolean.compare(b.online, a.online);
            } else if (currentFilter == Filter.BANNED) {
                return Boolean.compare(b.banned, a.banned);
            } else {
                return a.name.compareToIgnoreCase(b.name);
            }
        });

        cachedResults = results;
        unfilteredResults = new ArrayList<>(results);
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
        if (result.online && !result.headTexture.isEmpty()) {
            try {
                GameProfile profile = new GameProfile(UUID.fromString(result.uuid), null);
                profile.getProperties().put("textures", new Property("textures", result.headTexture));
                Field profileField = meta.getClass().getDeclaredField("profile");
                profileField.setAccessible(true);
                profileField.set(meta, profile);
            } catch (Exception e) {
            }
        } else if (result.online) {
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

    private List<PlayerResult> applyFilter(List<PlayerResult> results) {
        List<PlayerResult> filteredResults = new ArrayList<>();
        for (PlayerResult result : results) {
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
                case ALL:
                default:
                    break;
            }
            filteredResults.add(result);
        }
        return filteredResults;
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
            List<String> teleportLore = new ArrayList<>();
            teleportLore.add(ChatColor.GRAY + plugin.getMessage("gui.teleport-lmb","Left click: Teleport to player"));
            teleportLore.add(ChatColor.GRAY + plugin.getMessage("gui.teleport-rmb","Right click: Bring player to you"));
            teleportMeta.setLore(teleportLore);
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
        if (!player.hasPermission("playermanager.admin") && !player.hasPermission("playermanager.gui") && !player.hasPermission("playermanager.punishment")) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.no-permission", "You don't have permission!"));
            return;
        }
        if (!getConfigValue("features.punishment-system")) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.punishment-system-disabled", "Punishment system is disabled in config!"));
            return;
        }
        if (player.getUniqueId().toString().equals(result.uuid)) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.self-punish", "You can't punish yourself."));
            return;
        }
        if (plugin.isAdminPlayer(Bukkit.getOfflinePlayer(UUID.fromString(result.uuid)))) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.cannot-target-admin", "You cannot target another admin."));
            return;
        }
        Inventory punishmentGUI = Bukkit.createInventory(this, 27, plugin.getMessage("gui.punishment-prefix", "Punishments: ") + ChatColor.YELLOW + result.name);
        ItemStack headItem = createPlayerHead(result);
        punishmentGUI.setItem(4, headItem);

        ItemStack banItem = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta banMeta = banItem.getItemMeta();
        banMeta.setDisplayName(ChatColor.DARK_RED + plugin.getMessage("gui.ban", "Ban"));
        List<String> banLore = new ArrayList<>();
        banLore.add(ChatColor.RED + (result.banned ? plugin.getMessage("status.already-banned", "Already banned") : plugin.getMessage("gui.click-to-ban", "Click to ban")));
        banLore.add(ChatColor.YELLOW + plugin.getMessage("gui.actions-shift-rmb", "Shift+RMB: Quick action"));
        banMeta.setLore(banLore);
        banItem.setItemMeta(banMeta);
        punishmentGUI.setItem(10, banItem);

        ItemStack kickItem = new ItemStack(Material.BLAZE_ROD);
        ItemMeta kickMeta = kickItem.getItemMeta();
        kickMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.kick", "Kick"));
        List<String> kickLore = new ArrayList<>();
        kickLore.add(ChatColor.GRAY + plugin.getMessage("gui.click-to-kick", "Click to kick"));
        if (!result.online) {
            kickLore.add(ChatColor.DARK_GRAY + plugin.getMessage("gui.actions-shift-rmb", "Shift+RMB: Quick action"));
        }
        kickMeta.setLore(kickLore);
        kickItem.setItemMeta(kickMeta);
        punishmentGUI.setItem(11, kickItem);

        if (getConfigValue("features.jail-system")) {
            ItemStack jailItem = new ItemStack(Material.IRON_BARS);
            ItemMeta jailMeta = jailItem.getItemMeta();
            jailMeta.setDisplayName(ChatColor.GRAY + plugin.getMessage("gui.jail", "Jail"));
            List<String> jailLore = new ArrayList<>();
            jailLore.add(ChatColor.GRAY + plugin.getMessage("gui.click-to-jail", "Click to jail"));
            if (!result.online) {
                jailLore.add(ChatColor.DARK_GRAY + plugin.getMessage("gui.actions-shift-rmb", "Shift+RMB: Quick action"));
            }
            jailMeta.setLore(jailLore);
            jailItem.setItemMeta(jailMeta);
            punishmentGUI.setItem(12, jailItem);
        }

        ItemStack warnItem = new ItemStack(Material.PAPER);
        ItemMeta warnMeta = warnItem.getItemMeta();
        warnMeta.setDisplayName(ChatColor.YELLOW + plugin.getMessage("gui.warn", "Warn"));
        List<String> warnLore = new ArrayList<>();
        warnLore.add(ChatColor.GRAY + plugin.getMessage("gui.click-to-warn", "Click to warn"));
        if (!result.online) {
            warnLore.add(ChatColor.DARK_GRAY + plugin.getMessage("gui.actions-shift-rmb", "Shift+RMB: Quick action"));
        }
        warnMeta.setLore(warnLore);
        warnItem.setItemMeta(warnMeta);
        punishmentGUI.setItem(14, warnItem);

        ItemStack muteItem = new ItemStack(Material.BOOK);
        ItemMeta muteMeta = muteItem.getItemMeta();
        muteMeta.setDisplayName(ChatColor.GRAY + plugin.getMessage("gui.mute", "Mute"));
        List<String> muteLore = new ArrayList<>();
        muteLore.add(ChatColor.GRAY + plugin.getMessage("gui.click-to-mute", "Click to mute"));
        if (!result.online) {
            muteLore.add(ChatColor.DARK_GRAY + plugin.getMessage("gui.actions-shift-rmb", "Shift+RMB: Quick action"));
        }
        muteMeta.setLore(muteLore);
        muteItem.setItemMeta(muteMeta);
        punishmentGUI.setItem(15, muteItem);

        ItemStack unbanItem = new ItemStack(Material.EMERALD);
        ItemMeta unbanMeta = unbanItem.getItemMeta();
        unbanMeta.setDisplayName(ChatColor.GREEN + plugin.getMessage("gui.unban", "Unban"));
        List<String> unbanLore = new ArrayList<>();
        unbanLore.add(ChatColor.GREEN + (result.banned ? plugin.getMessage("gui.click-to-unban", "Click to unban") : plugin.getMessage("status.not-banned", "Not banned")));
        unbanLore.add(ChatColor.YELLOW + plugin.getMessage("gui.actions-shift-rmb", "Shift+RMB: Quick action"));
        unbanMeta.setLore(unbanLore);
        unbanItem.setItemMeta(unbanMeta);
        punishmentGUI.setItem(16, unbanItem);

        ItemStack backItem = new ItemStack(Material.ARROW);
        ItemMeta backMeta = backItem.getItemMeta();
        backMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.back", "Back"));
        backItem.setItemMeta(backMeta);
        punishmentGUI.setItem(22, backItem);

        player.openInventory(punishmentGUI);
        setPlayerMenuTarget(player.getUniqueId(), result);
        setLastOpenedMenu(player.getUniqueId(), "punishment");
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

    private void openMuteDurationGUI(Player player, PlayerResult result) {
        this.inventory = Bukkit.createInventory(this, 27, plugin.getMessage("gui.mute-duration-title", "Mute Duration: ") + result.name);

        ItemStack[] durations = new ItemStack[8];
        String[] durationKeys = {"1h", "12h", "1d", "3d", "7d", "14d", "permanent", "custom"};
        String[] materials = {"CLOCK", "CLOCK", "CLOCK", "CLOCK", "CLOCK", "CLOCK", "CLOCK", "PAPER"};
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 17};

        for (int i = 0; i < durations.length; i++) {
            durations[i] = new ItemStack(Material.valueOf(materials[i]));
            ItemMeta meta = durations[i].getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.YELLOW + plugin.getMessage("mute.duration." + durationKeys[i], durationKeys[i]));
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + plugin.getMessage("gui.actions-lmb-duration", "LMB: Select duration"));
                meta.setLore(lore);
                durations[i].setItemMeta(meta);
            }
            this.inventory.setItem(slots[i], durations[i]);
        }

        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        if (backMeta != null) {
            backMeta.setDisplayName(ChatColor.translateAlternateColorCodes('&', plugin.getMessage("gui.back", "Back")));
            back.setItemMeta(backMeta);
        }
        this.inventory.setItem(22, back);

        player.openInventory(this.inventory);
        setLastOpenedMenu(player.getUniqueId(), "mute_duration");
        setPlayerMenuTarget(player.getUniqueId(), result);
        setLastTarget(player.getUniqueId(), result.name);
        plugin.savePlayerMenuState(player);
    }

    private void openWarnDurationGUI(Player player, PlayerResult result) {
        this.inventory = Bukkit.createInventory(this, 27, plugin.getMessage("gui.warn-duration-title", "Warn Duration: ") + result.name);

        ItemStack[] durations = new ItemStack[8];
        String[] durationKeys = {"1h", "12h", "1d", "3d", "7d", "14d", "permanent", "custom"};
        String[] materials = {"CLOCK", "CLOCK", "CLOCK", "CLOCK", "CLOCK", "CLOCK", "CLOCK", "PAPER"};
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 17};

        for (int i = 0; i < durations.length; i++) {
            durations[i] = new ItemStack(Material.valueOf(materials[i]));
            ItemMeta meta = durations[i].getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.YELLOW + plugin.getMessage("warn.duration." + durationKeys[i], durationKeys[i]));
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + plugin.getMessage("gui.actions-lmb-duration", "LMB: Select duration"));
                meta.setLore(lore);
                durations[i].setItemMeta(meta);
            }
            this.inventory.setItem(slots[i], durations[i]);
        }

        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        if (backMeta != null) {
            backMeta.setDisplayName(ChatColor.translateAlternateColorCodes('&', plugin.getMessage("gui.back", "Back")));
            back.setItemMeta(backMeta);
        }
        this.inventory.setItem(22, back);

        player.openInventory(this.inventory);
        setLastOpenedMenu(player.getUniqueId(), "warn_duration");
        setPlayerMenuTarget(player.getUniqueId(), result);
        setLastTarget(player.getUniqueId(), result.name);
        plugin.savePlayerMenuState(player);
    }

    private void openBanDurationGUI(Player player, PlayerResult result) {
        Inventory banDurationGUI = Bukkit.createInventory(this, 27, plugin.getMessage("gui.ban-duration-title", "Ban Duration: ") + ChatColor.YELLOW + result.name);

        ItemStack[] durations = new ItemStack[8];
        String[] durationKeys = {"1h", "12h", "1d", "3d", "7d", "14d", "permanent", "custom"};
        String[] materials = {"CLOCK", "CLOCK", "CLOCK", "CLOCK", "CLOCK", "CLOCK", "BARRIER", "PAPER"};
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 17};

        for (int i = 0; i < durations.length; i++) {
            durations[i] = new ItemStack(Material.valueOf(materials[i]));
            ItemMeta meta = durations[i].getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.YELLOW + plugin.getMessage("ban.duration." + durationKeys[i], durationKeys[i]));
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + plugin.getMessage("gui.actions-lmb-duration", "LMB: Select duration"));
                if (durationKeys[i].equals("custom") && !isTempPunishPluginInstalled()) {
                    lore.add(ChatColor.RED + plugin.getMessage("error.temp-plugin-missing", "Temp ban plugin not installed! Install AdvancedBan or LiteBans."));
                }
                meta.setLore(lore);
                durations[i].setItemMeta(meta);
            }
            banDurationGUI.setItem(slots[i], durations[i]);
        }

        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        if (backMeta != null) {
            backMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.back", "Back"));
            back.setItemMeta(backMeta);
        }
        banDurationGUI.setItem(22, back);

        player.openInventory(banDurationGUI);
        setPlayerMenuTarget(player.getUniqueId(), result);
        setLastOpenedMenu(player.getUniqueId(), "ban_duration");
        setLastTarget(player.getUniqueId(), result.name);
        plugin.savePlayerDataConfig();
        plugin.savePlayerMenuState(player);
    }

    private void handleBanDurationClick(Player player, int slot, InventoryClickEvent event) {
        PlayerResult result = playerMenuTargets.get(player.getUniqueId());
        if (result == null) {
            plugin.getLogger().warning("No PlayerResult found for player " + player.getName());
            return;
        }
        if (player.getUniqueId().toString().equals(result.uuid)) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.self-punish", "You can't punish yourself."));
            return;
        }

        if (slot == 22) {
            openBanTypeGUI(player, result);
            return;
        }

        String duration = null;
        if (slot == 10) duration = "1h";
        else if (slot == 11) duration = "12h";
        else if (slot == 12) duration = "1d";
        else if (slot == 13) duration = "3d";
        else if (slot == 14) duration = "7d";
        else if (slot == 15) duration = "14d";
        else if (slot == 16) duration = "permanent";
        else if (slot == 17) {
            if (!isTempPunishPluginInstalled()) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.temp-plugin-missing", "Temp ban plugin not installed! Install AdvancedBan or LiteBans."));
                return;
            }
            player.sendMessage(ChatColor.YELLOW + plugin.getMessage("action.enter-duration", "Enter ban duration (e.g., 1d, 1w, 1m):"));
            plugin.getPlayerDataConfig().set("admin." + player.getUniqueId() + ".pending_action", "ban_duration");
            plugin.savePlayerDataConfig();
            player.closeInventory();
            plugin.pendingActions.put(player.getUniqueId(), (message, p) -> {
                String customDuration = message.trim();
                if (!customDuration.matches("\\d+[smhdwMy]")) {
                    p.sendMessage(ChatColor.RED + plugin.getMessage("error.invalid-duration", "Invalid duration format! Use e.g., 1d, 1w, 1m."));
                    return;
                }
                plugin.getPlayerDataConfig().set("admin." + p.getUniqueId() + ".pending_ban_duration", customDuration);
                plugin.getPlayerDataConfig().set("admin." + p.getUniqueId() + ".pending_action", null);
                plugin.savePlayerDataConfig();
                openTemporaryBanReason(p, result, customDuration);
            });
            return;
        }

        if (duration != null) {
            plugin.getPlayerDataConfig().set("admin." + player.getUniqueId() + ".pending_ban_duration", duration);
            plugin.savePlayerDataConfig();
            openTemporaryBanReason(player, result, duration);
        }
    }

    public void openBanReasonGUI(Player player, PlayerResult result, String duration) {
        if (!player.hasPermission("playermanager.ban")) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.no-permission", "You don't have permission!"));
            return;
        }
        if (player.getUniqueId().toString().equals(result.uuid)) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.self-punish", "You can't punish yourself."));
            return;
        }
        Player targetPlayer = result.online ? Bukkit.getPlayer(result.uuid) : null;
        if (targetPlayer != null && targetPlayer.hasPermission("playermanager.admin")) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.cannot-target-admin", "You cannot target another admin."));
            return;
        }
        if (!plugin.getConfig().getBoolean("features.punishment-system", true)) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.punishment-system-disabled", "Punishment system is disabled!"));
            return;
        }
        inventory = Bukkit.createInventory(this, 27, plugin.getMessage("gui.ban-reason-title", "Select Ban Reason"));
        setLastOpenedMenu(player.getUniqueId(), "ban_reason");
        setLastTarget(player.getUniqueId(), result.name);
        setPlayerMenuTarget(player.getUniqueId(), result);

        ItemStack griefingItem = new ItemStack(Material.TNT);
        ItemMeta griefingMeta = griefingItem.getItemMeta();
        if (griefingMeta != null) {
            griefingMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.reason.griefing", "Griefing"));
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + plugin.getMessage("gui.click-to-ban", "Click to ban for griefing"));
            griefingMeta.setLore(lore);
            griefingItem.setItemMeta(griefingMeta);
        }
        inventory.setItem(10, griefingItem);

        ItemStack cheatingItem = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta cheatingMeta = cheatingItem.getItemMeta();
        if (cheatingMeta != null) {
            cheatingMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.reason.cheating", "Cheating"));
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + plugin.getMessage("gui.click-to-ban", "Click to ban for cheating"));
            cheatingMeta.setLore(lore);
            cheatingItem.setItemMeta(cheatingMeta);
        }
        inventory.setItem(11, cheatingItem);

        ItemStack spamItem = new ItemStack(Material.BOOK);
        ItemMeta spamMeta = spamItem.getItemMeta();
        if (spamMeta != null) {
            spamMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.reason.spam", "Spam"));
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + plugin.getMessage("gui.click-to-ban", "Click to ban for spam"));
            spamMeta.setLore(lore);
            spamItem.setItemMeta(spamMeta);
        }
        inventory.setItem(12, spamItem);

        ItemStack customItem = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta customMeta = customItem.getItemMeta();
        if (customMeta != null) {
            customMeta.setDisplayName(ChatColor.GREEN + plugin.getMessage("gui.reason.custom", "Custom Reason"));
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + plugin.getMessage("gui.enter-custom-reason", "Enter custom reason in chat"));
            customMeta.setLore(lore);
            customItem.setItemMeta(customMeta);
        }
        inventory.setItem(14, customItem);

        ItemStack backItem = new ItemStack(Material.ARROW);
        ItemMeta backMeta = backItem.getItemMeta();
        if (backMeta != null) {
            backMeta.setDisplayName(ChatColor.YELLOW + plugin.getMessage("gui.back", "Back"));
            backItem.setItemMeta(backMeta);
        }
        inventory.setItem(22, backItem);

        playersInGUI.add(player.getUniqueId());
        player.openInventory(inventory);
        player.updateInventory();
    }


    private void handleBanReasonClick(Player player, int slot, InventoryClickEvent event) {
        PlayerResult result = playerMenuTargets.get(player.getUniqueId());
        if (result == null) {
            plugin.getLogger().warning("No PlayerResult found for player " + player.getName());
            return;
        }
        if (player.getUniqueId().toString().equals(result.uuid)) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.self-punish", "You can't punish yourself."));
            return;
        }

        if (slot == 22) {
            openBanTypeGUI(player, result);
            return;
        }

        String reason = null;
        if (slot == 10) reason = "griefing";
        else if (slot == 11) reason = "cheating";
        else if (slot == 12) reason = "spam";
        else if (slot == 14) reason = "custom";

        if (reason == null) return;

        if (reason.equals("custom")) {
            player.sendMessage(ChatColor.YELLOW + plugin.getMessage("action.enter-reason", "Enter reason in chat"));
            plugin.getPlayerDataConfig().set("admin." + player.getUniqueId() + ".pending_action", "ban");
            plugin.savePlayerDataConfig();
            player.closeInventory();
            plugin.pendingActions.put(player.getUniqueId(), (message, p) -> {
                String customReason = message.trim();
                String command = "ban " + result.name + " " + customReason;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Bukkit.dispatchCommand(p, command);
                    p.sendMessage(ChatColor.YELLOW + plugin.getMessage("action.executed", "Executed: %command% for %player%")
                            .replace("%command%", "ban")
                            .replace("%player%", result.name));
                    updatePlayerBanStatus(result.uuid, true);
                    plugin.getPlayerDataConfig().set("admin." + p.getUniqueId() + ".pending_action", null);
                    plugin.getPlayerDataConfig().set("admin." + p.getUniqueId() + ".ban_type", null);
                    plugin.savePlayerDataConfig();
                    openPunishmentGUI(p, result);
                });
            });
        } else {
            String command = "ban " + result.name + " " + reason;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Bukkit.dispatchCommand(player, command);
                player.sendMessage(ChatColor.YELLOW + plugin.getMessage("action.executed", "Executed: %command% for %player%")
                        .replace("%command%", "ban")
                        .replace("%player%", result.name));
                updatePlayerBanStatus(result.uuid, true);
                plugin.getPlayerDataConfig().set("admin." + player.getUniqueId() + ".ban_type", null);
                plugin.savePlayerDataConfig();
                openPunishmentGUI(player, result);
            });
        }

        refreshOpenGUIs();
    }

    private void openMuteReasonGUI(Player player, PlayerResult result, String duration) {
        this.inventory = Bukkit.createInventory(this, 27, plugin.getMessage("gui.mute-reason-title", "Mute Reason: ") + result.name);

        ItemStack[] reasons = new ItemStack[6];
        String[] reasonKeys = {"insult", "spam_flood", "advertising", "conflict_incitement", "cyberbullying", "custom"};
        String[] materials = {"REDSTONE", "SLIME_BALL", "OAK_SIGN", "BLAZE_POWDER", "GHAST_TEAR", "PAPER"};
        int[] slots = {10, 11, 12, 14, 15, 16};

        for (int i = 0; i < reasons.length; i++) {
            reasons[i] = new ItemStack(Material.valueOf(materials[i]));
            ItemMeta meta = reasons[i].getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.YELLOW + plugin.getMessage("mute.reason." + reasonKeys[i], reasonKeys[i]));
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + plugin.getMessage("gui.actions-lmb", "LMB: Select reason"));
                meta.setLore(lore);
                reasons[i].setItemMeta(meta);
            }
            this.inventory.setItem(slots[i], reasons[i]);
        }

        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        if (backMeta != null) {
            backMeta.setDisplayName(ChatColor.translateAlternateColorCodes('&', plugin.getMessage("gui.back", "Back")));
            back.setItemMeta(backMeta);
        }
        this.inventory.setItem(22, back);

        player.openInventory(this.inventory);
        setLastOpenedMenu(player.getUniqueId(), "mute_reason");
        setPlayerMenuTarget(player.getUniqueId(), result);
        setLastTarget(player.getUniqueId(), result.name);
        plugin.getPlayerDataConfig().set("players." + result.uuid + ".pending_mute_duration", duration);
        plugin.savePlayerMenuState(player);
    }

    private void openWarnReasonGUI(Player player, PlayerResult result, String duration) {
        this.inventory = Bukkit.createInventory(this, 27, plugin.getMessage("gui.warn-reason-title", "Warn Reason: ") + result.name);

        ItemStack[] reasons = new ItemStack[5];
        String[] reasonKeys = {"griefing", "disrespect_staff", "chat_rule_violation", "private_message_advertising", "custom"};
        String[] materials = {"TNT", "IRON_SWORD", "PAPER", "BOOK", "PAPER"};
        int[] slots = {10, 11, 12, 14, 15};

        for (int i = 0; i < reasons.length; i++) {
            reasons[i] = new ItemStack(Material.valueOf(materials[i]));
            ItemMeta meta = reasons[i].getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.YELLOW + plugin.getMessage("warn.reason." + reasonKeys[i], reasonKeys[i]));
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + plugin.getMessage("gui.actions-lmb", "LMB: Select reason"));
                meta.setLore(lore);
                reasons[i].setItemMeta(meta);
            }
            this.inventory.setItem(slots[i], reasons[i]);
        }

        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        if (backMeta != null) {
            backMeta.setDisplayName(ChatColor.translateAlternateColorCodes('&', plugin.getMessage("gui.back", "Back")));
            back.setItemMeta(backMeta);
        }
        this.inventory.setItem(22, back);

        player.openInventory(this.inventory);
        setLastOpenedMenu(player.getUniqueId(), "warn_reason");
        setPlayerMenuTarget(player.getUniqueId(), result);
        setLastTarget(player.getUniqueId(), result.name);
        plugin.getPlayerDataConfig().set("players." + result.uuid + ".pending_warn_duration", duration);
        plugin.savePlayerMenuState(player);
    }

    private void openKickReasonGUI(Player player, PlayerResult result) {
        if (!result.online) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.player-offline", "Player is offline. Kick requires online player."));
            return;
        }
        Inventory kickReasonGUI = Bukkit.createInventory(this, 27, plugin.getMessage("gui.kick-reason-prefix", "Kick Reason: ") + ChatColor.YELLOW + result.name);
        ItemStack headItem = createPlayerHead(result);
        kickReasonGUI.setItem(4, headItem);

        ItemStack spamItem = new ItemStack(Material.PAPER);
        ItemMeta spamMeta = spamItem.getItemMeta();
        spamMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.reason-spam", "Spam/Advertising"));
        spamItem.setItemMeta(spamMeta);
        kickReasonGUI.setItem(10, spamItem);

        ItemStack disrespectItem = new ItemStack(Material.ROTTEN_FLESH);
        ItemMeta disrespectMeta = disrespectItem.getItemMeta();
        disrespectMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.reason-disrespect", "Disrespect Staff"));
        disrespectItem.setItemMeta(disrespectMeta);
        kickReasonGUI.setItem(11, disrespectItem);

        ItemStack griefingItem = new ItemStack(Material.TNT);
        ItemMeta griefingMeta = griefingItem.getItemMeta();
        griefingMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.reason-griefing", "Griefing"));
        griefingItem.setItemMeta(griefingMeta);
        kickReasonGUI.setItem(12, griefingItem);

        ItemStack customReasonItem = new ItemStack(Material.PAPER);
        ItemMeta customReasonMeta = customReasonItem.getItemMeta();
        customReasonMeta.setDisplayName(ChatColor.YELLOW + plugin.getMessage("gui.custom-reason", "Custom Reason"));
        List<String> customLore = new ArrayList<>();
        customLore.add(ChatColor.GRAY + plugin.getMessage("gui.enter-in-chat", "Enter in chat"));
        customReasonMeta.setLore(customLore);
        customReasonItem.setItemMeta(customReasonMeta);
        kickReasonGUI.setItem(14, customReasonItem);

        ItemStack backItem = new ItemStack(Material.ARROW);
        ItemMeta backMeta = backItem.getItemMeta();
        backMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.back", "Back"));
        backItem.setItemMeta(backMeta);
        kickReasonGUI.setItem(22, backItem);

        player.openInventory(kickReasonGUI);
        setPlayerMenuTarget(player.getUniqueId(), result);
        setLastOpenedMenu(player.getUniqueId(), "kick_reason");
        setLastTarget(player.getUniqueId(), result.name);
        plugin.savePlayerMenuState(player);
    }

    private void handleKickReasonClick(Player player, int slot, InventoryClickEvent event) {
        PlayerResult result = playerMenuTargets.get(player.getUniqueId());
        if (result == null) {
            plugin.getLogger().warning("No PlayerResult found for player " + player.getName());
            return;
        }
        if (player.getUniqueId().toString().equals(result.uuid)) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.self-punish", "You can't punish yourself."));
            return;
        }
        if (!result.online) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.player-offline", "Player is offline. Kick requires online player."));
            return;
        }

        if (slot == 22) {
            openPunishmentGUI(player, result);
            return;
        }

        String reason = null;
        if (slot == 10) reason = "spam_advertising";
        else if (slot == 11) reason = "disrespect_staff";
        else if (slot == 12) reason = "griefing";
        else if (slot == 14) reason = "custom";

        if (reason == null) return;

        if ("custom".equals(reason)) {
            player.sendMessage(ChatColor.YELLOW + plugin.getMessage("action.enter-reason", "Enter kick reason in chat"));
            plugin.getPlayerDataConfig().set("players." + result.uuid + ".pending_action", "kick_reason");
            plugin.savePlayerDataConfig();
            player.closeInventory();
            plugin.pendingActions.put(player.getUniqueId(), (message, p) -> {
                String customReason = message.trim();
                if (customReason.isEmpty()) {
                    p.sendMessage(ChatColor.RED + plugin.getMessage("error.invalid-reason", "Reason cannot be empty!"));
                    return;
                }
                executeKick(p, result, customReason);
            });
            return;
        }

        executeKick(player, result, reason);
    }

    private void executeKick(Player player, PlayerResult result, String reason) {
        String command = "kick " + result.name + " " + reason;
        Bukkit.dispatchCommand(player, command);
        player.sendMessage(ChatColor.YELLOW + plugin.getMessage("action.executed", "Executed: %command% for %player%")
                .replace("%command%", "kick").replace("%player%", result.name));
        plugin.getPlayerDataConfig().set("players." + result.uuid + ".pending_action", null);
        plugin.savePlayerDataConfig();
        openPunishmentGUI(player, result);
        refreshOpenGUIs();
    }
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        if (!(event.getInventory().getHolder() instanceof PlayerSearchGUI)) return;

        event.setCancelled(true);
        if (event.getCurrentItem() == null || event.getCurrentItem().getType() == Material.AIR) return;

        String currentMenu = lastOpenedMenu.getOrDefault(player.getUniqueId(), "search");
        int slot = event.getSlot();

        if (currentMenu.equals("search")) {
            long currentTime = System.currentTimeMillis();
            Long lastClick = lastFilterClick.getOrDefault(player.getUniqueId(), 0L);
            if (currentTime - lastClick < 500 && (slot == 47 || slot == 48 || slot == 50 || slot == 51)) {
                player.sendMessage(ChatColor.YELLOW + plugin.getMessage("action.wait", "Please wait before switching filters again!"));
                return;
            }
            lastFilterClick.put(player.getUniqueId(), currentTime);

            if (slot == 0) {
                Long lastRefresh = lastRefreshClick.getOrDefault(player.getUniqueId(), 0L);
                if (currentTime - lastRefresh < 1000) {
                    player.sendMessage(ChatColor.YELLOW + plugin.getMessage("action.wait", "Please wait before updating again!"));
                    return;
                }
                lastRefreshClick.put(player.getUniqueId(), currentTime);

                player.sendMessage(ChatColor.YELLOW + plugin.getMessage("gui.refreshing", "Updating player list..."));
                cachedResults.clear();
                unfilteredResults.clear();
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    List<PlayerResult> results = getSearchResults(player, currentSearch);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline() && playersInGUI.contains(player.getUniqueId())) {
                            setupSearchGUI(player, results);
                            player.updateInventory();
                            player.sendMessage(ChatColor.GREEN + plugin.getMessage("gui.refreshed", "List updated"));
                        }
                    });
                });
                return;
            }
            else if (slot == 1 && player.hasPermission("playermanager.ip-search")) {
                player.closeInventory();
                ipGUI.openIPSearchGUI(player);
                return;
            }
            if (slot == 4) {
                if (event.getClick() == ClickType.LEFT) {
                    player.closeInventory();
                    String cancelText = plugin.getMessage("gui.cancel", "[Cancel]");
                    TextComponent message = new TextComponent(ChatColor.YELLOW + plugin.getMessage("gui.enter-search", "Enter name or UUID: ") + " ");
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
                event.setCancelled(true);
                return;
            }
            else if (slot == 45 && currentPage > 0) {
                if (event.getClick() == ClickType.SHIFT_RIGHT) {
                    currentPage = Math.max(0, currentPage - 5);
                } else {
                    currentPage--;
                }
                lastPage.put(player.getUniqueId(), currentPage);
                setupSearchGUI(player, applyFilter(unfilteredResults));
                player.updateInventory();
            }
            else if (slot == 53 && (currentPage + 1) * 36 < unfilteredResults.size()) {
                if (event.getClick() == ClickType.SHIFT_RIGHT) {
                    currentPage = Math.min((int) Math.ceil((double) unfilteredResults.size() / 36.0) - 1, currentPage + 5);
                } else {
                    currentPage++;
                }
                lastPage.put(player.getUniqueId(), currentPage);
                setupSearchGUI(player, applyFilter(unfilteredResults));
                player.updateInventory();
            }
            else if (slot == 47) {
                currentFilter = Filter.ALL;
                setupSearchGUI(player, applyFilter(unfilteredResults));
                player.updateInventory();
            }
            else if (slot == 48) {
                currentFilter = Filter.ONLINE;
                setupSearchGUI(player, applyFilter(unfilteredResults));
                player.updateInventory();
            }
            else if (slot == 50) {
                currentFilter = Filter.OFFLINE;
                setupSearchGUI(player, applyFilter(unfilteredResults));
                player.updateInventory();
            }
            else if (slot == 51) {
                currentFilter = Filter.BANNED;
                setupSearchGUI(player, applyFilter(unfilteredResults));
                player.updateInventory();
            }
            else if (slot == 49) {
                player.closeInventory();
                playersInGUI.remove(player.getUniqueId());
            }
            else if (slot >= 9 && slot < 45) {
                PlayerResult result = slotMap.get(slot);
                if (result != null) {
                    if (event.getClick() == ClickType.LEFT) {
                        openPlayerMenu(player, result);
                    } else if (event.getClick() == ClickType.RIGHT) {
                        if (result.online && getConfigValue("features.player-teleportation")) {
                            Player target = Bukkit.getPlayer(UUID.fromString(result.uuid));
                            if (target != null) {
                                player.teleport(target.getLocation());
                                player.sendMessage(ChatColor.GREEN + plugin.getMessage("action.teleported", "Teleport to %player%").replace("%player%", result.name));
                            }
                        }
                    } else if (event.getClick() == ClickType.SHIFT_RIGHT) {
                        openPunishmentGUI(player, result);
                    }
                }
            }
        } else if (currentMenu.equals("player")) {
            handlePlayerMenuClick(player, slot, event);
        } else if (currentMenu.equals("punishment")) {
            handlePunishmentClick(player, slot, event);
        } else if (currentMenu.equals("player_info")) {
            handlePlayerInfoClick(player, slot, event);
        } else if (currentMenu.equals("gamemode")) {
            handleGamemodeClick(player, slot, event);
        } else if (currentMenu.equals("gamemode_confirm")) {
            handleGamemodeConfirmClick(player, slot, event);
        } else if (currentMenu.equals("ban_type")) {
            handleBanTypeClick(player, slot, event);
        } else if (currentMenu.equals("ban_duration")) {
            handleBanDurationClick(player, slot, event);
        } else if (currentMenu.equals("ban_reason")) {
            handleTemporaryBanReasonClick(player, slot, event);
        } else if (currentMenu.equals("mute_duration")) {
            handleMuteDurationClick(player, slot, event);
        } else if (currentMenu.equals("mute_reason")) {
            handleMuteReasonClick(player, slot, event);
        } else if (currentMenu.equals("jail")) {
            handleJailClick(player, slot, event);
        } else if (currentMenu.equals("warn_duration")) {
            handleWarnDurationClick(player, slot, event);
        } else if (currentMenu.equals("warn_reason")) {
            handleWarnReasonClick(player, slot, event);
        } else if (currentMenu.equals("kick_reason")) {
            handleKickReasonClick(player, slot, event);
        }
    }

    private final Map<UUID, Long> lastPageChangeTime = new HashMap<>();

    private void handleSearchClick(Player player, int slot, InventoryClickEvent event) {
        if (!player.hasPermission("playermanager.admin") && !player.hasPermission("playermanager.gui")) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.no-permission", "You don't have permission!"));
            return;
        }


        if (slot == 1) {
            if (!getConfigValue("features.ip-search")) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.ip-search-disabled", "IP search is disabled!"));
                return;
            }
            ipGUI.openIPSearchGUI(player);
            return;
        } else if (slot == 0) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                List<PlayerResult> results = getSearchResults(player, currentSearch);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline() && playersInGUI.contains(player.getUniqueId())) {
                        setupSearchGUI(player, results);
                        player.updateInventory();
                    }
                });
            });
        } else if (slot == 45 && currentPage > 0) {
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
        } else if (slot == 53) {
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
        } else if (slot == 47) {
            currentFilter = Filter.ALL;
            currentPage = 0;
            lastPage.put(player.getUniqueId(), 0);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                List<PlayerResult> results = getSearchResults(player, currentSearch);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline() && playersInGUI.contains(player.getUniqueId())) {
                        setupSearchGUI(player, results);
                        player.updateInventory();
                    }
                });
            });
        } else if (slot == 48) {
            currentFilter = Filter.ONLINE;
            currentPage = 0;
            lastPage.put(player.getUniqueId(), 0);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                List<PlayerResult> results = getSearchResults(player, currentSearch);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline() && playersInGUI.contains(player.getUniqueId())) {
                        setupSearchGUI(player, results);
                        player.updateInventory();
                    }
                });
            });
        } else if (slot == 50) {
            currentFilter = Filter.OFFLINE;
            currentPage = 0;
            lastPage.put(player.getUniqueId(), 0);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                List<PlayerResult> results = getSearchResults(player, currentSearch);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline() && playersInGUI.contains(player.getUniqueId())) {
                        setupSearchGUI(player, results);
                        player.updateInventory();
                    }
                });
            });
        } else if (slot == 51) {
            currentFilter = Filter.BANNED;
            currentPage = 0;
            lastPage.put(player.getUniqueId(), 0);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                List<PlayerResult> results = getSearchResults(player, currentSearch);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline() && playersInGUI.contains(player.getUniqueId())) {
                        setupSearchGUI(player, results);
                        player.updateInventory();
                    }
                });
            });
        } else if (slot == 49) {
            player.closeInventory();
            playersInGUI.remove(player.getUniqueId());
            pendingActions.remove(player.getUniqueId());
        } else if (slot >= 9 && slot <= 44) {
            PlayerResult result = slotMap.get(slot);
            if (result != null) {
                if (event.getClick() == ClickType.LEFT) {
                    openPlayerMenu(player, result);
                } else if (event.getClick() == ClickType.RIGHT) {
                    if (result.online && getConfigValue("features.player-teleportation")) {
                        Player target = Bukkit.getPlayer(UUID.fromString(result.uuid));
                        if (target != null) {
                            player.teleport(target.getLocation());
                            player.sendMessage(ChatColor.GREEN + plugin.getMessage("action.teleported", "Teleported to %player%").replace("%player%", result.name));
                        }
                    }
                } else if (event.getClick() == ClickType.SHIFT_RIGHT) {
                    openPunishmentGUI(player, result);
                }
            }
        }
    }

    private void handlePlayerMenuClick(Player player, int slot, InventoryClickEvent event) {
        PlayerResult result = playerMenuTargets.get(player.getUniqueId());
        if (result == null) {
            plugin.getLogger().warning("No PlayerResult found for player " + player.getName());
            return;
        }
        if (player.getUniqueId().toString().equals(result.uuid)) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.self-punish", "You can't punish yourself."));
            return;
        }


        if (slot == 22) {
            openSearchGUI(player);
            return;
        }
        if (slot == 10) {
            return;
        } else if (slot == 11) {
            if (!getConfigValue("features.player-teleportation")) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.teleport-disabled", "Teleportation is disabled!"));
                return;
            }
            if (!result.online) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.player-offline", "Player is offline."));
                return;
            }
            ClickType click = event.getClick();
            Player target = Bukkit.getPlayer(result.name);
            if (target == null) return;

            if (click == ClickType.LEFT) {
                Bukkit.dispatchCommand(player, "tp " + result.name);
                player.sendMessage(ChatColor.GREEN + plugin.getMessage("action.teleported-to", "Teleported to %player%").replace("%player%", result.name));
            } else if (click == ClickType.RIGHT) {
                Bukkit.dispatchCommand(player, "tp " + result.name + " " + player.getName());
                player.sendMessage(ChatColor.GREEN + plugin.getMessage("action.teleported-player-to-you", "Teleported %player% to you").replace("%player%", result.name));
                target.sendMessage(ChatColor.YELLOW + plugin.getMessage("action.you-were-teleported", "You were teleported by %admin%").replace("%admin%", player.getName()));
            }
            player.closeInventory();
        } else if (slot == 12) {
            if (!getConfigValue("features.punishment-system")) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.punishment-system-disabled", "Punishment system is disabled!"));
                return;
            }
            openPunishmentGUI(player, result);
        } else if (slot == 14) {
            if (!getConfigValue("features.inventory-inspection")) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.inventory-inspection-disabled", "Inventory inspection is disabled!"));
                return;
            }
            if (!result.online) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.player-offline", "Player is offline."));
                return;
            }
            Player target = Bukkit.getPlayer(result.name);
            if (target != null) {
                player.openInventory(target.getInventory());
            }
        } else if (slot == 15) {
            if (!getConfigValue("features.ender-chest-inspection")) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.ender-chest-inspection-disabled", "Ender chest inspection is disabled!"));
                return;
            }
            if (!result.online) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.player-offline", "Player is offline."));
                return;
            }
            Player target = Bukkit.getPlayer(result.name);
            if (target != null) {
                player.openInventory(target.getEnderChest());
            }
        } else if (slot == 16) {
            if (!getConfigValue("features.player-info-actions")) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.player-info-disabled", "Player info actions are disabled!"));
                return;
            }
            if (!result.online) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.player-offline", "Player is offline."));
                return;
            }
            openPlayerInfoGUI(player, result);
        }
        refreshOpenGUIs();
    }

    private void handlePunishmentClick(Player player, int slot, InventoryClickEvent event) {
        PlayerResult result = playerMenuTargets.get(player.getUniqueId());
        if (result == null) {
            plugin.getLogger().warning("No PlayerResult found for player " + player.getName());
            return;
        }
        if (player.getUniqueId().toString().equals(result.uuid)) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.self-punish", "You can't punish yourself."));
            return;
        }

        if (slot == 22) {
            openPlayerMenu(player, result);
            return;
        }

        int index = -1;
        if (slot == 10) index = 0; // ban
        else if (slot == 11) index = 1; // kick
        else if (slot == 12) index = 2; // jail
        else if (slot == 14) index = 3; // warn
        else if (slot == 15) index = 4; // mute
        else if (slot == 16) index = 5; // unban

        if (index == -1) {
            return;
        }

        String command = null;
        switch (index) {
            case 0:
                command = "ban";
                break;
            case 1:
                command = "kick";
                break;
            case 2:
                command = "jail";
                break;
            case 3:
                command = "warn";
                break;
            case 4:
                command = "mute";
                break;
            case 5:
                command = "unban";
                break;
        }

        boolean needsSave = false;
        String path = "players." + result.uuid + ".";

        if (event.getClick() == ClickType.LEFT) {
            if (index == 5) { // unban
                Bukkit.dispatchCommand(player, command + " " + result.name);
                player.sendMessage(ChatColor.YELLOW + plugin.getMessage("action.executed", "Executed: %command% for %player%")
                        .replace("%command%", command)
                        .replace("%player%", result.name));
                result.banned = false;
                plugin.getPlayerDataConfig().set(path + "banned", false);
                needsSave = true;
                openPunishmentGUI(player, result);
            } else if (index == 0) { // ban
                if (result.banned) {
                    player.sendMessage(ChatColor.RED + plugin.getMessage("error.already-banned", "This player is already banned!"));
                    return;
                }
                openBanTypeGUI(player, result);
            } else if (index == 1) { // kick
                if (!result.online) {
                    player.sendMessage(ChatColor.RED + plugin.getMessage("error.player-offline", "Player is offline."));
                    return;
                }
                openKickReasonGUI(player, result);
            } else if (index == 2) { // jail
                if (!result.online) {
                    player.sendMessage(ChatColor.RED + plugin.getMessage("error.player-offline", "Player is offline."));
                    return;
                }
                openJailGUI(player, result);
            } else if (index == 3) { // warn
                if (!result.online) {
                    player.sendMessage(ChatColor.RED + plugin.getMessage("error.player-offline", "Player is offline."));
                    return;
                }
                openWarnDurationGUI(player, result);
            } else if (index == 4) { // mute
                if (!result.online) {
                    player.sendMessage(ChatColor.RED + plugin.getMessage("error.player-offline", "Player is offline."));
                    return;
                }
                openMuteDurationGUI(player, result);
            }
        } else if (event.getClick() == ClickType.SHIFT_RIGHT && index != 5) {
            if (!result.online && index != 0) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.player-offline", "Player is offline."));
                return;
            }
            if (index == 0 && result.banned) { // ban
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.already-banned", "This player is already banned!"));
                return;
            }
            String reason = "Punished by admin";
            String fullCommand = command + " " + result.name + (index == 0 || index == 1 ? " " + reason : "");
            if (index == 0 && !isTempPunishPluginInstalled()) {
                fullCommand = "ban " + result.name + " " + reason;
            } else if (index == 0) {
                fullCommand = "tempban " + result.name + " 1d " + reason;
            }
            Bukkit.dispatchCommand(player, fullCommand);
            player.sendMessage(ChatColor.YELLOW + plugin.getMessage("action.executed-quick", "Quick %command% executed for %player%")
                    .replace("%command%", command)
                    .replace("%player%", result.name));
            if (index == 0) { // ban
                result.banned = true;
                plugin.getPlayerDataConfig().set(path + "banned", true);
                needsSave = true;
            }
            openPunishmentGUI(player, result);
        }

        if (needsSave) {
            plugin.savePlayerDataConfig();
        }
        refreshOpenGUIs();
    }

    private void handleJailClick(Player player, int slot, InventoryClickEvent event) {
        PlayerResult result = playerMenuTargets.get(player.getUniqueId());
        if (result == null) {
            plugin.getLogger().warning("No PlayerResult found for player " + player.getName());
            return;
        }
        if (player.getUniqueId().toString().equals(result.uuid)) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.self-punish", "You can't punish yourself."));
            return;
        }


        if (slot == 22) {
            openPunishmentGUI(player, result);
            return;
        }

        String command = null;
        if (slot == 12) {
            command = "jail";
        } else if (slot == 14) {
            command = "unjail";
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
            return;
        }

        if (slot != 14) {
            openJailGUI(player, result);
        }
        refreshOpenGUIs();
    }

    private void handlePlayerInfoClick(Player player, int slot, InventoryClickEvent event) {
        PlayerResult result = playerMenuTargets.get(player.getUniqueId());
        if (result == null) {
            plugin.getLogger().warning("No PlayerResult found for player " + player.getName());
            return;
        }
        if (player.getUniqueId().toString().equals(result.uuid)) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.self-punish", "You can't punish yourself."));
            return;
        }


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
            if (target == null) return;
            Bukkit.dispatchCommand(player, "heal " + result.name);
            player.sendMessage(ChatColor.YELLOW + plugin.getMessage("action.executed", "Executed: %command% for %player%")
                    .replace("%command%", "heal")
                    .replace("%player%", result.name));
            openPlayerInfoGUI(player, result);
            return;
        }
        if (slot == 14) {
            Player target = Bukkit.getPlayer(result.name);
            if (target == null) return;
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

    private void handleGamemodeClick(Player player, int slot, InventoryClickEvent event) {
        PlayerResult result = playerMenuTargets.get(player.getUniqueId());
        if (result == null) {
            plugin.getLogger().warning("No PlayerResult found for player " + player.getName());
            return;
        }
        if (player.getUniqueId().toString().equals(result.uuid)) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.self-punish", "You can't punish yourself."));
            return;
        }


        if (slot == 22) {
            openPlayerInfoGUI(player, result);
            return;
        }
        if (!result.online) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.player-offline", "Player is offline."));
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

    private void handleGamemodeConfirmClick(Player player, int slot, InventoryClickEvent event) {
        PlayerResult result = playerMenuTargets.get(player.getUniqueId());
        if (result == null) {
            plugin.getLogger().warning("No PlayerResult found for player " + player.getName());
            return;
        }
        if (player.getUniqueId().toString().equals(result.uuid)) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.self-punish", "You can't punish yourself."));
            return;
        }


        if (slot == 22) {
            openGamemodeGUI(player, result);
            return;
        }
        if (slot == 11) {
            if (!result.online) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.player-offline", "Player is offline."));
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

    private void handleMuteDurationClick(Player player, int slot, InventoryClickEvent event) {
        PlayerResult result = playerMenuTargets.get(player.getUniqueId());
        if (result == null) {
            plugin.getLogger().warning("No PlayerResult found for player " + player.getName());
            return;
        }
        if (player.getUniqueId().toString().equals(result.uuid)) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.self-punish", "You can't punish yourself."));
            return;
        }


        if (slot == 22) {
            openPunishmentGUI(player, result);
            return;
        }

        String duration = null;
        if (slot == 10) duration = "1h";
        else if (slot == 11) duration = "12h";
        else if (slot == 12) duration = "1d";
        else if (slot == 13) duration = "3d";
        else if (slot == 14) duration = "7d";
        else if (slot == 15) duration = "14d";
        else if (slot == 16) duration = "permanent";
        else if (slot == 17) duration = "custom";

        if (duration == null) {
            return;
        }

        if (duration.equals("custom")) {
            player.sendMessage(ChatColor.YELLOW + plugin.getMessage("action.enter-duration", "Enter duration in chat (e.g., 2d, 5h, permanent)"));
            plugin.getPlayerDataConfig().set("players." + result.uuid + ".pending_action", "mute_duration");
            plugin.savePlayerDataConfig();
            player.closeInventory();
            new BukkitRunnable() {
                @Override
                public void run() {
                    Bukkit.getPluginManager().registerEvents(new Listener() {
                        @EventHandler
                        public void onPlayerChat(AsyncPlayerChatEvent chatEvent) {
                            if (chatEvent.getPlayer().equals(player)) {
                                String customDuration = chatEvent.getMessage();
                                chatEvent.setCancelled(true);
                                if (!customDuration.matches("\\d+[smhdw]|permanent")) {
                                    player.sendMessage(ChatColor.RED + plugin.getMessage("error.invalid-duration", "Invalid duration format! Use e.g., 1h, 2d, permanent"));
                                    plugin.getPlayerDataConfig().set("players." + result.uuid + ".pending_action", null);
                                    plugin.savePlayerDataConfig();
                                    HandlerList.unregisterAll(this);
                                    openWarnDurationGUI(player, result);
                                    return;
                                }
                                Bukkit.getScheduler().runTask(plugin, () -> {
                                    plugin.getPlayerDataConfig().set("players." + result.uuid + ".pending_warn_duration", customDuration);
                                    plugin.getPlayerDataConfig().set("players." + result.uuid + ".pending_action", null);
                                    plugin.savePlayerDataConfig();
                                    openWarnReasonGUI(player, result, customDuration);
                                });
                                HandlerList.unregisterAll(this);
                            }
                        }
                    }, plugin);
                }
            }.runTaskLater(plugin, 1L);
        } else {
            plugin.getPlayerDataConfig().set("players." + result.uuid + ".pending_warn_duration", duration);
            plugin.savePlayerDataConfig();
            openWarnReasonGUI(player, result, duration);
        }

        refreshOpenGUIs();
    }


    private void handleWarnDurationClick(Player player, int slot, InventoryClickEvent event) {
        PlayerResult result = playerMenuTargets.get(player.getUniqueId());
        if (result == null) {
            plugin.getLogger().warning("No PlayerResult found for player " + player.getName());
            return;
        }
        if (player.getUniqueId().toString().equals(result.uuid)) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.self-punish", "You can't punish yourself."));
            return;
        }


        if (slot == 22) {
            openPunishmentGUI(player, result);
            return;
        }

        String duration = null;
        if (slot == 10) duration = "1h";
        else if (slot == 11) duration = "12h";
        else if (slot == 12) duration = "1d";
        else if (slot == 13) duration = "3d";
        else if (slot == 14) duration = "7d";
        else if (slot == 15) duration = "14d";
        else if (slot == 16) duration = "permanent";
        else if (slot == 17) duration = "custom";

        if (duration == null) {
            return;
        }

        if (duration.equals("custom")) {
            player.sendMessage(ChatColor.YELLOW + plugin.getMessage("action.enter-duration", "Enter duration in chat (e.g., 2d, 5h, permanent)"));
            plugin.getPlayerDataConfig().set("players." + result.uuid + ".pending_action", "warn_duration");
            plugin.savePlayerDataConfig();
            player.closeInventory();
            new BukkitRunnable() {
                @Override
                public void run() {
                    Bukkit.getPluginManager().registerEvents(new Listener() {
                        @EventHandler
                        public void onPlayerChat(AsyncPlayerChatEvent chatEvent) {
                            if (chatEvent.getPlayer().equals(player)) {
                                String customDuration = chatEvent.getMessage();
                                chatEvent.setCancelled(true);
                                if (!customDuration.matches("\\d+[smhdw]|permanent")) {
                                    player.sendMessage(ChatColor.RED + plugin.getMessage("error.invalid-duration", "Invalid duration format! Use e.g., 1h, 2d, permanent"));
                                    plugin.getPlayerDataConfig().set("players." + result.uuid + ".pending_action", null);
                                    plugin.savePlayerDataConfig();
                                    HandlerList.unregisterAll(this);
                                    openWarnDurationGUI(player, result);
                                    return;
                                }
                                Bukkit.getScheduler().runTask(plugin, () -> {
                                    plugin.getPlayerDataConfig().set("players." + result.uuid + ".pending_warn_duration", customDuration);
                                    plugin.getPlayerDataConfig().set("players." + result.uuid + ".pending_action", null);
                                    plugin.savePlayerDataConfig();
                                    openWarnReasonGUI(player, result, customDuration);
                                });
                                HandlerList.unregisterAll(this);
                            }
                        }
                    }, plugin);
                }
            }.runTaskLater(plugin, 1L);
        } else {
            plugin.getPlayerDataConfig().set("players." + result.uuid + ".pending_warn_duration", duration);
            plugin.savePlayerDataConfig();
            openWarnReasonGUI(player, result, duration);
        }

        refreshOpenGUIs();
    }

    private void handleMuteReasonClick(Player player, int slot, InventoryClickEvent event) {
        PlayerResult result = playerMenuTargets.get(player.getUniqueId());
        if (result == null) {
            plugin.getLogger().warning("No PlayerResult found for player " + player.getName());
            return;
        }
        if (player.getUniqueId().toString().equals(result.uuid)) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.self-punish", "You can't punish yourself."));
            return;
        }


        if (slot == 22) {
            String duration = plugin.getPlayerDataConfig().getString("players." + result.uuid + ".pending_mute_duration", "permanent");
            openMuteDurationGUI(player, result);
            return;
        }

        String reason = null;
        if (slot == 10) reason = "insult";
        else if (slot == 11) reason = "spam_flood";
        else if (slot == 12) reason = "advertising";
        else if (slot == 14) reason = "conflict_incitement";
        else if (slot == 15) reason = "cyberbullying";
        else if (slot == 16) reason = "custom";

        if (reason == null) {
            return;
        }

        String duration = plugin.getPlayerDataConfig().getString("players." + result.uuid + ".pending_mute_duration", "permanent");
        if (reason.equals("custom")) {
            player.sendMessage(ChatColor.YELLOW + plugin.getMessage("action.enter-reason", "Enter reason in chat"));
            plugin.getPlayerDataConfig().set("players." + result.uuid + ".pending_action", "mute");
            plugin.savePlayerDataConfig();
            player.closeInventory();
            new BukkitRunnable() {
                @Override
                public void run() {
                    Bukkit.getPluginManager().registerEvents(new Listener() {
                        @EventHandler
                        public void onPlayerChat(AsyncPlayerChatEvent chatEvent) {
                            if (chatEvent.getPlayer().equals(player)) {
                                String customReason = chatEvent.getMessage();
                                chatEvent.setCancelled(true);
                                String command = "mute " + result.name + " " + duration + " " + customReason;
                                Bukkit.getScheduler().runTask(plugin, () -> {
                                    Bukkit.dispatchCommand(player, command);
                                    player.sendMessage(ChatColor.YELLOW + plugin.getMessage("action.executed", "Executed: %command% for %player%")
                                            .replace("%command%", "mute")
                                            .replace("%player%", result.name));
                                    plugin.getPlayerDataConfig().set("players." + result.uuid + ".pending_action", null);
                                    plugin.getPlayerDataConfig().set("players." + result.uuid + ".pending_mute_duration", null);
                                    plugin.savePlayerDataConfig();
                                    openPunishmentGUI(player, result);
                                });
                                HandlerList.unregisterAll(this);
                            }
                        }
                    }, plugin);
                }
            }.runTaskLater(plugin, 1L);
        } else {
            String command = "mute " + result.name + " " + duration + " " + reason;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Bukkit.dispatchCommand(player, command);
                player.sendMessage(ChatColor.YELLOW + plugin.getMessage("action.executed", "Executed: %command% for %player%")
                        .replace("%command%", "mute")
                        .replace("%player%", result.name));
                plugin.getPlayerDataConfig().set("players." + result.uuid + ".pending_mute_duration", null);
                plugin.savePlayerDataConfig();
                openPunishmentGUI(player, result);
            });
        }

        refreshOpenGUIs();
    }

    private void handleWarnReasonClick(Player player, int slot, InventoryClickEvent event) {
        PlayerResult result = playerMenuTargets.get(player.getUniqueId());
        if (result == null) {
            plugin.getLogger().warning("No PlayerResult found for player " + player.getName());
            return;
        }
        if (player.getUniqueId().toString().equals(result.uuid)) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.self-punish", "You can't punish yourself."));
            return;
        }


        if (slot == 22) {
            String duration = plugin.getPlayerDataConfig().getString("players." + result.uuid + ".pending_warn_duration", "permanent");
            openWarnDurationGUI(player, result);
            return;
        }

        String reason = null;
        if (slot == 10) reason = "griefing";
        else if (slot == 11) reason = "disrespect_staff";
        else if (slot == 12) reason = "chat_rule_violation";
        else if (slot == 14) reason = "private_message_advertising";
        else if (slot == 15) reason = "custom";

        if (reason == null) {
            return;
        }

        String duration = plugin.getPlayerDataConfig().getString("players." + result.uuid + ".pending_warn_duration", "permanent");
        if (reason.equals("custom")) {
            player.sendMessage(ChatColor.YELLOW + plugin.getMessage("action.enter-reason", "Enter reason in chat"));
            plugin.getPlayerDataConfig().set("players." + result.uuid + ".pending_action", "warn");
            plugin.savePlayerDataConfig();
            player.closeInventory();
            new BukkitRunnable() {
                @Override
                public void run() {
                    Bukkit.getPluginManager().registerEvents(new Listener() {
                        @EventHandler
                        public void onPlayerChat(AsyncPlayerChatEvent chatEvent) {
                            if (chatEvent.getPlayer().equals(player)) {
                                String customReason = chatEvent.getMessage();
                                chatEvent.setCancelled(true);
                                String command = "warn " + result.name + " " + duration + " " + customReason;
                                Bukkit.getScheduler().runTask(plugin, () -> {
                                    Bukkit.dispatchCommand(player, command);
                                    player.sendMessage(ChatColor.YELLOW + plugin.getMessage("action.executed", "Executed: %command% for %player%")
                                            .replace("%command%", "warn")
                                            .replace("%player%", result.name));
                                    plugin.getPlayerDataConfig().set("players." + result.uuid + ".pending_action", null);
                                    plugin.getPlayerDataConfig().set("players." + result.uuid + ".pending_warn_duration", null);
                                    plugin.savePlayerDataConfig();
                                    openPunishmentGUI(player, result);
                                });
                                HandlerList.unregisterAll(this);
                            }
                        }
                    }, plugin);
                }
            }.runTaskLater(plugin, 1L);
        } else {
            String command = "warn " + result.name + " " + duration + " " + reason;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Bukkit.dispatchCommand(player, command);
                player.sendMessage(ChatColor.YELLOW + plugin.getMessage("action.executed", "Executed: %command% for %player%")
                        .replace("%command%", "warn")
                        .replace("%player%", result.name));
                plugin.getPlayerDataConfig().set("players." + result.uuid + ".pending_warn_duration", null);
                plugin.savePlayerDataConfig();
                openPunishmentGUI(player, result);
            });
        }

        refreshOpenGUIs();
    }

    public void openBanTypeGUI(Player player, PlayerResult result) {
        if (!player.hasPermission("playermanager.ban")) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.no-permission", "You don't have permission!"));
            return;
        }
        if (player.getUniqueId().toString().equals(result.uuid)) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.self-punish", "You can't punish yourself."));
            return;
        }
        Player targetPlayer = result.online ? Bukkit.getPlayer(result.uuid) : null;
        if (targetPlayer != null && targetPlayer.hasPermission("playermanager.admin")) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.cannot-target-admin", "You cannot target another admin."));
            return;
        }
        if (!plugin.getConfig().getBoolean("features.punishment-system", true)) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.punishment-system-disabled", "Punishment system is disabled!"));
            return;
        }
        inventory = Bukkit.createInventory(this, 27, plugin.getMessage("gui.ban-type-title", "Select Ban Type"));
        setLastOpenedMenu(player.getUniqueId(), "ban_type");
        setLastTarget(player.getUniqueId(), result.name);
        setPlayerMenuTarget(player.getUniqueId(), result);

        ItemStack permanentItem = new ItemStack(Material.BEDROCK);
        ItemMeta permMeta = permanentItem.getItemMeta();
        if (permMeta != null) {
            permMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.permanent-ban", "Permanent Ban"));
            List<String> permLore = new ArrayList<>();
            permLore.add(ChatColor.GRAY + plugin.getMessage("gui.click-to-select", "Click to select"));
            permMeta.setLore(permLore);
            permanentItem.setItemMeta(permMeta);
        }
        inventory.setItem(11, permanentItem);

        ItemStack tempItem = new ItemStack(Material.CLOCK);
        ItemMeta tempMeta = tempItem.getItemMeta();
        if (tempMeta != null) {
            tempMeta.setDisplayName(ChatColor.YELLOW + plugin.getMessage("gui.temporary-ban", "Temporary Ban"));
            List<String> tempLore = new ArrayList<>();
            tempLore.add(ChatColor.GRAY + plugin.getMessage("gui.click-to-select", "Click to select"));
            tempMeta.setLore(tempLore);
            tempItem.setItemMeta(tempMeta);
        }
        inventory.setItem(15, tempItem);

        ItemStack backItem = new ItemStack(Material.ARROW);
        ItemMeta backMeta = backItem.getItemMeta();
        if (backMeta != null) {
            backMeta.setDisplayName(ChatColor.YELLOW + plugin.getMessage("gui.back", "Back"));
            backItem.setItemMeta(backMeta);
        }
        inventory.setItem(22, backItem);

        playersInGUI.add(player.getUniqueId());
        player.openInventory(inventory);
        player.updateInventory();
    }

    private void handleBanTypeClick(Player player, int slot, InventoryClickEvent event) {
        PlayerResult result = playerMenuTargets.get(player.getUniqueId());
        if (result == null) return;

        if (slot == 11) {
            plugin.getPlayerDataConfig().set("admin." + player.getUniqueId() + ".ban_type", "permanent");
            plugin.savePlayerDataConfig();
            openBanReasonGUI(player, result, "permanent");
        } else if (slot == 15) {
            if (!isTempPunishPluginInstalled()) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.no-temp-plugin", "No temporary punishment plugin installed! «AdvancedBan,Litebans»"));
                return;
            }
            plugin.getPlayerDataConfig().set("admin." + player.getUniqueId() + ".ban_type", "temporary");
            plugin.savePlayerDataConfig();
            openBanDurationGUI(player, result);
        } else if (slot == 22) {
            openPunishmentGUI(player, result);
        }
    }

    public void openTemporaryBanReason(Player player, PlayerResult result, String duration) {
        plugin.getPlayerDataConfig().set("admin." + player.getUniqueId() + ".pending_ban_duration", duration);
        plugin.savePlayerDataConfig();
        if (!player.hasPermission("playermanager.ban")) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.no-permission", "You don't have permission!"));
            return;
        }
        if (player.getUniqueId().toString().equals(result.uuid)) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.self-punish", "You can't punish yourself."));
            return;
        }
        Player targetPlayer = result.online ? Bukkit.getPlayer(result.uuid) : null;
        if (targetPlayer != null && targetPlayer.hasPermission("playermanager.admin")) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.cannot-target-admin", "You cannot target another admin."));
            return;
        }
        if (!plugin.getConfig().getBoolean("features.punishment-system", true)) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.punishment-system-disabled", "Punishment system is disabled!"));
            return;
        }
        inventory = Bukkit.createInventory(this, 27, plugin.getMessage("gui.ban-reason-title", "Select Ban Reason"));
        setLastOpenedMenu(player.getUniqueId(), "ban_reason");
        setLastTarget(player.getUniqueId(), result.name);
        setPlayerMenuTarget(player.getUniqueId(), result);
        ItemStack griefingItem = new ItemStack(Material.TNT);
        ItemMeta griefingMeta = griefingItem.getItemMeta();
        if (griefingMeta != null) {
            griefingMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.reason.griefing", "Griefing"));
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + plugin.getMessage("gui.click-to-ban", "Click to ban for griefing"));
            griefingMeta.setLore(lore);
            griefingItem.setItemMeta(griefingMeta);
        }
        inventory.setItem(10, griefingItem);

        ItemStack cheatingItem = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta cheatingMeta = cheatingItem.getItemMeta();
        if (cheatingMeta != null) {
            cheatingMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.reason.cheating", "Cheating"));
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + plugin.getMessage("gui.click-to-ban", "Click to ban for cheating"));
            cheatingMeta.setLore(lore);
            cheatingItem.setItemMeta(cheatingMeta);
        }
        inventory.setItem(11, cheatingItem);

        ItemStack spamItem = new ItemStack(Material.BOOK);
        ItemMeta spamMeta = spamItem.getItemMeta();
        if (spamMeta != null) {
            spamMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.reason.spam", "Spam"));
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + plugin.getMessage("gui.click-to-ban", "Click to ban for spam"));
            spamMeta.setLore(lore);
            spamItem.setItemMeta(spamMeta);
        }
        inventory.setItem(12, spamItem);

        ItemStack customItem = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta customMeta = customItem.getItemMeta();
        if (customMeta != null) {
            customMeta.setDisplayName(ChatColor.GREEN + plugin.getMessage("gui.reason.custom", "Custom Reason"));
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + plugin.getMessage("gui.enter-custom-reason", "Enter custom reason in chat"));
            customMeta.setLore(lore);
            customItem.setItemMeta(customMeta);
        }
        inventory.setItem(14, customItem);

        ItemStack backItem = new ItemStack(Material.ARROW);
        ItemMeta backMeta = backItem.getItemMeta();
        if (backMeta != null) {
            backMeta.setDisplayName(ChatColor.YELLOW + plugin.getMessage("gui.back", "Back"));
            backItem.setItemMeta(backMeta);
        }
        inventory.setItem(22, backItem);

        playersInGUI.add(player.getUniqueId());
        player.openInventory(inventory);
        player.updateInventory();
    }

    private void handleTemporaryBanReasonClick(Player player, int slot, InventoryClickEvent event) {
        PlayerResult result = playerMenuTargets.get(player.getUniqueId());
        if (result == null) {
            plugin.getLogger().warning("No PlayerResult found for player " + player.getName());
            return;
        }
        if (player.getUniqueId().toString().equals(result.uuid)) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.self-punish", "You can't punish yourself."));
            return;
        }

        if (slot == 22) {
            openBanDurationGUI(player, result);
            return;
        }

        String reason = null;
        if (slot == 10) reason = "griefing";
        else if (slot == 11) reason = "cheating";
        else if (slot == 12) reason = "spam";
        else if (slot == 14) reason = "custom";

        if (reason == null) return;

        String duration = plugin.getPlayerDataConfig().getString("admin." + player.getUniqueId() + ".pending_ban_duration", "1d");
        String tempbanCommand = "tempban";
        if (reason.equals("custom")) {
            player.sendMessage(ChatColor.YELLOW + plugin.getMessage("action.enter-reason", "Enter reason in chat"));
            plugin.getPlayerDataConfig().set("admin." + player.getUniqueId() + ".pending_action", "tempban");
            plugin.savePlayerDataConfig();
            player.closeInventory();
            plugin.pendingActions.put(player.getUniqueId(), (message, p) -> {
                String customReason = message.trim();
                String command = tempbanCommand + " " + result.name + " " + duration + " " + customReason;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Bukkit.dispatchCommand(p, command);
                    p.sendMessage(ChatColor.YELLOW + plugin.getMessage("action.executed", "Executed: %command% for %player%")
                            .replace("%command%", tempbanCommand)
                            .replace("%player%", result.name));
                    updatePlayerBanStatus(result.uuid, true);
                    plugin.getPlayerDataConfig().set("admin." + p.getUniqueId() + ".pending_action", null);
                    plugin.getPlayerDataConfig().set("admin." + p.getUniqueId() + ".pending_ban_duration", null);
                    plugin.savePlayerDataConfig();
                    openPunishmentGUI(p, result);
                });
            });
        } else {
            String command = tempbanCommand + " " + result.name + " " + duration + " " + reason;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Bukkit.dispatchCommand(player, command);
                player.sendMessage(ChatColor.YELLOW + plugin.getMessage("action.executed", "Executed: %command% for %player%")
                        .replace("%command%", tempbanCommand)
                        .replace("%player%", result.name));
                updatePlayerBanStatus(result.uuid, true);
                plugin.getPlayerDataConfig().set("admin." + player.getUniqueId() + ".pending_action", null);
                plugin.getPlayerDataConfig().set("admin." + player.getUniqueId() + ".pending_ban_duration", null);
                plugin.savePlayerDataConfig();
                openPunishmentGUI(player, result);
            });
        }

        refreshOpenGUIs();
    }

    private void updatePlayerBanStatus(String uuid, boolean banned) {
        PlayerResult result = playerMenuTargets.values().stream()
                .filter(r -> r.uuid.equals(uuid))
                .findFirst()
                .orElse(null);
        if (result != null) {
            result.banned = banned;
        }
        plugin.getPlayerDataConfig().set("players." + uuid + ".banned", banned);
        plugin.savePlayerDataConfig();
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
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            Iterator<UUID> iterator = this.playersInGUI.iterator();
            while (iterator.hasNext()) {
                UUID playerId = iterator.next();
                Player player = Bukkit.getPlayer(playerId);
                if (player == null || !player.isOnline() || (!player.hasPermission("playermanager.admin") && !player.hasPermission("playermanager.gui"))) {
                    iterator.remove();
                } else if (player.getOpenInventory().getTopInventory().getHolder() instanceof PlayerSearchGUI) {
                    String currentMenu = lastOpenedMenu.getOrDefault(player.getUniqueId(), "search");
                    PlayerResult result = playerMenuTargets.get(player.getUniqueId());
                    if (currentMenu.equals("search")) {
                        List<PlayerResult> results = cachedResults.isEmpty() ? getSearchResults(player, currentSearch) : cachedResults;
                        setupSearchGUI(player, results);
                    } else if (currentMenu.equals("player") && result != null) {
                        openPlayerMenu(player, result);
                    } else if (currentMenu.equals("punishment") && result != null) {
                        openPunishmentGUI(player, result);
                    } else if (currentMenu.equals("jail") && result != null) {
                        openJailGUI(player, result);
                    } else if (currentMenu.equals("player_info") && result != null) {
                        openPlayerInfoGUI(player, result);
                    } else if (currentMenu.equals("gamemode") && result != null) {
                        openGamemodeGUI(player, result);
                    } else if (currentMenu.equals("gamemode_confirm") && result != null) {
                        openGamemodeConfirmGUI(player, result);
                    } else if (currentMenu.equals("mute_duration") && result != null) {
                        openMuteDurationGUI(player, result);
                    } else if (currentMenu.equals("warn_duration") && result != null) {
                        openWarnDurationGUI(player, result);
                    } else if (currentMenu.equals("mute_reason") && result != null) {
                        String duration = plugin.getPlayerDataConfig().getString("players." + result.uuid + ".pending_mute_duration", "permanent");
                        openMuteReasonGUI(player, result, duration);
                    } else if (currentMenu.equals("warn_reason") && result != null) {
                        String duration = plugin.getPlayerDataConfig().getString("players." + result.uuid + ".pending_warn_duration", "permanent");
                        openWarnReasonGUI(player, result, duration);
                    } else if (currentMenu.equals("ban_duration") && result != null) {
                        openBanDurationGUI(player, result);
                    } else if (currentMenu.equals("ban_reason") && result != null) {
                        String duration = plugin.getPlayerDataConfig().getString("players." + result.uuid + ".pending_ban_duration", "permanent");
                        openBanReasonGUI(player, result, duration);
                    } else if (currentMenu.equals("kick_reason") && result != null) {
                        openKickReasonGUI(player, result);
                    } else if (currentMenu.equals("ban_type") && result != null) {
                        openBanTypeGUI(player, result);
                    } else {
                        plugin.getLogger().warning("Invalid menu state for " + player.getName() + ": " + currentMenu + ", resetting to search");
                        List<PlayerResult> results = cachedResults.isEmpty() ? getSearchResults(player, currentSearch) : cachedResults;
                        setupSearchGUI(player, results);
                    }
                    player.updateInventory();
                } else {
                    iterator.remove();
                }
            }
        }, 1L);
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
        this.unfilteredResults.clear();
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
        UUID playerId = player.getUniqueId();
        if (!pendingActions.containsKey(playerId)) return;

        event.setCancelled(true);
        String message = event.getMessage().trim();
        if (message.isEmpty()) {
            Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(ChatColor.RED + plugin.getMessage("error.invalid-input", "Input cannot be empty!")));
            return;
        }


        Bukkit.getScheduler().runTask(plugin, () -> {
            if (pendingActions.containsKey(playerId)) {
                pendingActions.get(playerId).execute(message, player);
                pendingActions.remove(playerId);
            } else {
                plugin.getLogger().warning("No pending action found for " + player.getName() + " after chat input");
            }
        });
    }

    private boolean isTempPunishPluginInstalled() {
        String[] tempPlugins = {"AdvancedBan", "LiteBans", "TempBan", "PunishmentManager", "EssentialsX"};
        boolean installed = false;
        for (String pluginName : tempPlugins) {
            if (Bukkit.getPluginManager().getPlugin(pluginName) != null) {
                plugin.getLogger().info("Temp plugin detected: " + pluginName);
                installed = true;
            }
        }
        if (!installed) {
            plugin.getLogger().warning("No temp punishment plugin installed!");
        }
        return installed;
    }

    public void addPendingAction(UUID uuid, ChatAction action) {
        pendingActions.put(uuid, action);
    }
    public Set<UUID> getPlayersInGUI() {
        return playersInGUI;
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
