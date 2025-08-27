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
        }
        openSearchGUI(admin);
    }

    public void openSearchGUI(Player admin) {
        if (!admin.hasPermission("playermanager.admin") && !admin.hasPermission("playermanager.gui")) {
            admin.sendMessage(ChatColor.RED + plugin.getMessage("error.no-permission", "You don't have permission!"));
            return;
        }
        this.inventory = Bukkit.createInventory(this, 54, plugin.getMessage("gui.title", "Player Management"));
        this.setupSearchGUI(admin);
        admin.openInventory(this.inventory);
        this.playersInGUI.add(admin.getUniqueId());
        setLastOpenedMenu(admin.getUniqueId(), "search");
        plugin.savePlayerMenuState(admin);
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
        try {
            ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) skull.getItemMeta();
            meta.setOwningPlayer(player);
            skull.setItemMeta(meta);
            Field profileField = meta.getClass().getDeclaredField("profile");
            profileField.setAccessible(true);
            GameProfile gameProfile = (GameProfile) profileField.get(meta);
            if (gameProfile != null && gameProfile.getProperties().containsKey("textures")) {
                Property texture = gameProfile.getProperties().get("textures").iterator().next();
                return texture.getValue();
            }
        } catch (Exception e) {
        }
        return "";
    }

    private void setupSearchGUI(Player admin) {
        this.inventory.clear();
        this.slotMap.clear();
        this.inventory.setItem(47, this.createFilterItem(Filter.ALL));
        this.inventory.setItem(48, this.createFilterItem(Filter.ONLINE));
        this.inventory.setItem(50, this.createFilterItem(Filter.OFFLINE));
        this.inventory.setItem(51, this.createFilterItem(Filter.BANNED));
        String searchText = plugin.getMessage("gui.search", "Search: %query%")
                .replace("%query%", currentSearch.isEmpty() ? plugin.getMessage("gui.search-all", "all") : currentSearch);
        ItemStack searchItem = new ItemStack(Material.COMPASS);
        ItemMeta searchMeta = searchItem.getItemMeta();
        searchMeta.setDisplayName(ChatColor.YELLOW + searchText);
        List<String> searchLore = new ArrayList<>();
        searchLore.add(plugin.getMessage("gui.search-hint", "&7Left click: Enter query | Right click: Reset search"));
        searchMeta.setLore(searchLore);
        searchItem.setItemMeta(searchMeta);
        this.inventory.setItem(4, searchItem);
        ItemStack closeItem = new ItemStack(Material.BARRIER);
        ItemMeta closeMeta = closeItem.getItemMeta();
        closeMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.close", "Close"));
        closeItem.setItemMeta(closeMeta);
        this.inventory.setItem(49, closeItem);
        List<PlayerResult> results = getSearchResults(admin, currentSearch);
        int start = currentPage * 36;
        int end = Math.min(start + 36, results.size());
        for (int i = start; i < end; i++) {
            PlayerResult result = results.get(i);
            ItemStack head = createPlayerHead(result);
            this.inventory.setItem(i - start + 9, head);
            this.slotMap.put(i - start + 9, result);
        }
        if (currentPage > 0) {
            ItemStack prevPage = new ItemStack(Material.ARROW);
            ItemMeta prevMeta = prevPage.getItemMeta();
            prevMeta.setDisplayName(ChatColor.YELLOW + plugin.getMessage("gui.previous-page", "Previous page"));
            prevPage.setItemMeta(prevMeta);
            this.inventory.setItem(45, prevPage);
        } else {
            ItemStack noPrev = new ItemStack(Material.RED_STAINED_GLASS_PANE);
            ItemMeta noPrevMeta = noPrev.getItemMeta();
            noPrevMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.no-page", "No page"));
            noPrev.setItemMeta(noPrevMeta);
            this.inventory.setItem(45, noPrev);
        }
        if ((currentPage + 1) * 36 < results.size()) {
            ItemStack nextPage = new ItemStack(Material.ARROW);
            ItemMeta nextMeta = nextPage.getItemMeta();
            nextMeta.setDisplayName(ChatColor.YELLOW + plugin.getMessage("gui.next-page", "Next page"));
            nextPage.setItemMeta(nextMeta);
            this.inventory.setItem(53, nextPage);
        } else {
            ItemStack noNext = new ItemStack(Material.RED_STAINED_GLASS_PANE);
            ItemMeta noNextMeta = noNext.getItemMeta();
            noNextMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.no-page", "No page"));
            noNext.setItemMeta(noNextMeta);
            this.inventory.setItem(53, noNext);
        }
    }

    private List<PlayerResult> getSearchResults(Player admin, String search) {
        List<PlayerResult> results = new ArrayList<>();
        for (OfflinePlayer offlinePlayer : Bukkit.getOfflinePlayers()) {
            if (offlinePlayer.getName() == null) continue;
            String name = offlinePlayer.getName();
            String uuidStr = offlinePlayer.getUniqueId().toString();
            if (!search.isEmpty() && !name.toLowerCase().contains(search.toLowerCase())) continue;
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
        return results;
    }

    private ItemStack createPlayerHead(PlayerResult result) {
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
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(UUID.fromString(result.uuid));
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
            playerMenu.setItem(12, teleportItem);
        }
        if (getConfigValue("features.inventory-inspection")) {
            ItemStack inventoryItem = new ItemStack(Material.CHEST);
            ItemMeta inventoryMeta = inventoryItem.getItemMeta();
            inventoryMeta.setDisplayName(ChatColor.AQUA + plugin.getMessage("gui.inspect-inventory", "Inspect Inventory"));
            inventoryItem.setItemMeta(inventoryMeta);
            playerMenu.setItem(14, inventoryItem);
        }
        if (getConfigValue("features.punishment-system")) {
            ItemStack punishmentsItem = new ItemStack(Material.IRON_SWORD);
            ItemMeta punishmentsMeta = punishmentsItem.getItemMeta();
            punishmentsMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.punishments", "Punishments"));
            punishmentsItem.setItemMeta(punishmentsMeta);
            playerMenu.setItem(16, punishmentsItem);
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

    private boolean getConfigValue(String key) {
        return plugin.getConfig().getBoolean(key, true);
    }

    public void openPunishmentGUI(Player player, PlayerResult result) {
        if (!player.hasPermission("playermanager.admin") && !player.hasPermission("playermanager.gui")) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.no-permission", "You don't have permission!"));
            return;
        }
        if (!getConfigValue("features.punishment-system")) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.punishment-disabled", "Punishment system is disabled in config!"));
            return;
        }
        Inventory punishmentGUI = Bukkit.createInventory(this, 27, plugin.getMessage("gui.punishment-prefix", "Punishments: ") + ChatColor.YELLOW + result.name);
        ItemStack headItem = createPlayerHead(result);
        punishmentGUI.setItem(4, headItem);

        Material[] materials = {Material.NETHERITE_SWORD, Material.BLAZE_ROD, Material.BOOK, Material.PAPER, Material.GREEN_DYE};
        String[] displayKeys = {"ban", "kick", "warn", "mute", "unban"};
        ChatColor[] colors = {ChatColor.RED, ChatColor.GOLD, ChatColor.YELLOW, ChatColor.GREEN, ChatColor.DARK_GREEN};
        int[] slots = {10, 11, 13, 15, 16};

        for (int i = 0; i < 5; i++) {
            ItemStack item = new ItemStack(materials[i]);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(colors[i] + plugin.getMessage("gui.punishment." + displayKeys[i], displayKeys[i].substring(0, 1).toUpperCase() + displayKeys[i].substring(1)));
            item.setItemMeta(meta);
            punishmentGUI.setItem(slots[i], item);
        }

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

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
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
        }
    }

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
                        setupSearchGUI(p);
                        p.openInventory(inventory);
                        pendingActions.remove(p.getUniqueId());
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
            currentPage--;
            lastPage.put(player.getUniqueId(), currentPage);
            setupSearchGUI(player);
            plugin.savePlayerMenuState(player);
            return;
        }
        if (slot == 53 && (currentPage + 1) * 36 < getSearchResults(player, currentSearch).size()) {
            currentPage++;
            lastPage.put(player.getUniqueId(), currentPage);
            setupSearchGUI(player);
            plugin.savePlayerMenuState(player);
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
                setupSearchGUI(player);
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
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.punishment-disabled", "Punishment system is disabled in config!"));
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
        if (slot == 12 && getConfigValue("features.player-teleportation")) {
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
        if (slot == 16 && getConfigValue("features.punishment-system")) {
            openPunishmentGUI(player, result);
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
        String path = "players." + result.uuid + ".";
        String[] commands = {"ban", "kick", "warn", "mute", "pardon"};
        int[] slots = {10, 11, 13, 15, 16};
        int index = -1;
        for (int i = 0; i < slots.length; i++) {
            if (slot == slots[i]) {
                index = i;
                break;
            }
        }
        if (index == -1) return;
        if ((index == 1 || index == 2 || index == 3) && !result.online) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.player-offline", "Player is offline."));
            return;
        }
        if (index == 0 && result.banned) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.already-banned", "This player is already banned!"));
            return;
        }
        if (index == 4 && !result.banned) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.not-banned", "This player is not banned!"));
            return;
        }
        if (player.getUniqueId().toString().equals(result.uuid)) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.self-punish", "You can't punish yourself."));
            return;
        }
        Bukkit.dispatchCommand(player, commands[index] + " " + result.name);
        player.sendMessage(ChatColor.YELLOW + plugin.getMessage("action.executed", "Executed: %command% for %player%")
                .replace("%command%", commands[index].equals("pardon") ? "unban" : commands[index])
                .replace("%player%", result.name));
        boolean needsSave = false;
        if (index == 0) {
            result.banned = true;
            if (!plugin.getPlayerDataConfig().getBoolean(path + "banned", false)) {
                plugin.getPlayerDataConfig().set(path + "banned", true);
                needsSave = true;
            }
        } else if (index == 4) {
            result.banned = false;
            if (plugin.getPlayerDataConfig().getBoolean(path + "banned", false)) {
                plugin.getPlayerDataConfig().set(path + "banned", false);
                needsSave = true;
            }
        }
        if (needsSave) {
            plugin.savePlayerDataConfig();
        }
        openPunishmentGUI(player, result);
        refreshOpenGUIs();
        event.setCancelled(true);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("playermanager.admin") && !player.hasPermission("playermanager.gui")) {
            return;
        }
        boolean punishmentEnabled = getConfigValue("features.punishment-system");
        boolean teleportEnabled = getConfigValue("features.player-teleportation");
        boolean inventoryEnabled = getConfigValue("features.inventory-inspection");
        boolean allFeaturesDisabled = !punishmentEnabled && !teleportEnabled && !inventoryEnabled;

        if (event.getAction().toString().contains("RIGHT_CLICK") && player.isSneaking()) {
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
                        setupSearchGUI(player);
                        player.updateInventory();
                    } else if (currentMenu.equals("player")) {
                        PlayerResult result = playerMenuTargets.get(player.getUniqueId());
                        if (result != null) {
                            openPlayerMenu(player, result);
                        } else {
                            openSearchGUI(player);
                        }
                    } else if (currentMenu.equals("punishment")) {
                        PlayerResult result = playerMenuTargets.get(player.getUniqueId());
                        if (result != null) {
                            openPunishmentGUI(player, result);
                        } else {
                            openSearchGUI(player);
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
        this.lastPage.put(player.getUniqueId(), 0);
        this.lastTarget.remove(player.getUniqueId());
        this.setLastOpenedMenu(player.getUniqueId(), "search");
        this.setupSearchGUI(player);
        player.openInventory(this.inventory);
        player.updateInventory();
        player.sendMessage(ChatColor.GREEN + plugin.getMessage("action.search-cancelled", "Search cancelled"));
        this.pendingActions.remove(player.getUniqueId());
        plugin.savePlayerMenuState(player);
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