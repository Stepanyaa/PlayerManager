package ru.stepanyaa.playerManager;

import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public class PlayerManagerIpGUI implements InventoryHolder, Listener {
    private final PlayerManager plugin;
    private final Inventory inventory;
    private final Map<Integer, IPResult> slotMap = new HashMap<>();
    private final Set<UUID> playersInGUI = new HashSet<>();
    private final Map<UUID, String> lastOpenedMenu = new HashMap<>();
    private final Map<UUID, String> lastTarget = new HashMap<>();
    private final PlayerSearchGUI searchGUI;
    private List<IPResult> cachedIPResults = new ArrayList<>();
    private final Map<String, List<PlayerSearchGUI.PlayerResult>> cachedSearchByIPResults = new HashMap<>();
    private int currentPage = 0;
    private final Map<UUID, Integer> lastPage = new HashMap<>();

    public PlayerManagerIpGUI(PlayerManager plugin, PlayerSearchGUI searchGUI) {
        this.plugin = plugin;
        this.searchGUI = searchGUI;
        this.inventory = Bukkit.createInventory(this, 54, ChatColor.DARK_GRAY + plugin.getMessage("gui.ip-search-title", "IP Search"));
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public static class IPResult {
        public String ip;
        public int accountCount;
        public int bannedPlayers;
        public boolean ipBanned;
        public long lastActivity;
    }

    public void openIPSearchGUI(Player player) {
        if (!player.hasPermission("playermanager.ip-search")) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.no-permission", "You don't have permission!"));
            return;
        }
        if (!player.isOnline()) {
            plugin.getLogger().warning("Attempted to open IP search GUI for offline player: " + player.getName());
            return;
        }
        if (!plugin.getConfig().getBoolean("features.ip-search", true)) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.ip-search-disabled", "IP search is disabled in config!"));
            return;
        }

        ItemStack loadingItem = new ItemStack(Material.REDSTONE);
        ItemMeta loadingMeta = loadingItem.getItemMeta();
        if (loadingMeta != null) {
            loadingMeta.setDisplayName(ChatColor.YELLOW + plugin.getMessage("gui.loading", "Loading..."));
            loadingItem.setItemMeta(loadingMeta);
        }
        inventory.clear();
        inventory.setItem(4, loadingItem);
        player.openInventory(inventory);
        playersInGUI.add(player.getUniqueId());
        setLastOpenedMenu(player.getUniqueId(), "ip_search");
        plugin.savePlayerMenuState(player);

        if (!cachedIPResults.isEmpty()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && playersInGUI.contains(player.getUniqueId())) {
                    setupIPSearchGUI(player, cachedIPResults);
                    player.updateInventory();
                }
            });
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                long startTime = System.currentTimeMillis();
                List<IPResult> ipResults = getIPResults();
                cachedIPResults = ipResults;
                if (ipResults.isEmpty()) {
                    plugin.getLogger().warning("No IP results found for IP search GUI");
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline() && playersInGUI.contains(player.getUniqueId())) {
                        setupIPSearchGUI(player, ipResults);
                        player.updateInventory();
                    } else {
                        plugin.getLogger().warning("Player " + player.getName() + " went offline before IP search GUI setup");
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to load IP results for " + player.getName() + ": " + e.getMessage());
                e.printStackTrace();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        player.sendMessage(ChatColor.RED + plugin.getMessage("error.loading-failed", "Failed to load IP search data."));
                        player.closeInventory();
                    }
                });
            }
        });
    }

    private List<IPResult> getIPResults() {
        List<IPResult> ipResults = new ArrayList<>();
        ConfigurationSection playersSection = plugin.getPlayerDataConfig().getConfigurationSection("players");
        if (playersSection == null) {
            plugin.getLogger().warning("No players section found in player_data.yml");
            return ipResults;
        }

        Map<String, Integer> ipCount = new HashMap<>();
        Map<String, Integer> bannedCount = new HashMap<>();
        Map<String, Long> lastActivityMap = new HashMap<>();
        int totalPlayers = 0;
        long currentTime = System.currentTimeMillis();
        long maxInactivityDays = plugin.getConfig().getLong("max-inactivity-days", 30);
        long maxInactivityMillis = maxInactivityDays * 24 * 60 * 60 * 1000L;

        for (String uuid : playersSection.getKeys(false)) {
            try {
                String ip = plugin.getPlayerDataConfig().getString("players." + uuid + ".ip", "");
                if (ip == null || ip.trim().isEmpty() || ip.equals("Unknown")) {
                    plugin.getLogger().warning("Invalid or missing IP for UUID " + uuid);
                    continue;
                }
                String name = plugin.getPlayerDataConfig().getString("players." + uuid + ".name", "");
                long lastActivity = Math.max(
                        plugin.getPlayerDataConfig().getLong("players." + uuid + ".last_login", 0L),
                        plugin.getPlayerDataConfig().getLong("players." + uuid + ".last_logout", 0L)
                );
                if (lastActivity > 0 && (currentTime - lastActivity) > maxInactivityMillis) {
                    continue;
                }
                totalPlayers++;
                boolean banned = Bukkit.getBanList(BanList.Type.NAME).isBanned(name);
                plugin.updatePlayerBanStatus(uuid, banned);
                ipCount.put(ip, ipCount.getOrDefault(ip, 0) + 1);
                if (banned) {
                    bannedCount.put(ip, bannedCount.getOrDefault(ip, 0) + 1);
                }
                lastActivityMap.put(ip, Math.max(lastActivityMap.getOrDefault(ip, 0L), lastActivity));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid UUID " + uuid + " in player_data.yml");
            }
        }

        for (Map.Entry<String, Integer> entry : ipCount.entrySet()) {
            String ip = entry.getKey();
            IPResult ipResult = new IPResult();
            ipResult.ip = ip;
            ipResult.accountCount = entry.getValue();
            ipResult.bannedPlayers = bannedCount.getOrDefault(ip, 0);
            ipResult.ipBanned = Bukkit.getBanList(BanList.Type.IP).isBanned(ip);
            ipResult.lastActivity = lastActivityMap.getOrDefault(ip, 0L);
            ipResults.add(ipResult);
        }

        ipResults.sort(Comparator.comparing(r -> r.ip));
        plugin.getLogger().info("Processed " + totalPlayers + " players, found " + ipResults.size() + " unique IPs");
        return ipResults;
    }

    public void setupIPSearchGUI(Player player, List<IPResult> ipResults) {
        inventory.clear();
        slotMap.clear();

        ItemStack searchItem = new ItemStack(Material.COMPASS);
        ItemMeta searchMeta = searchItem.getItemMeta();
        if (searchMeta != null) {
            searchMeta.setDisplayName(ChatColor.YELLOW + plugin.getMessage("gui.ip-search", "IP Search"));
            List<String> searchLore = new ArrayList<>();
            searchLore.add(ChatColor.GRAY + plugin.getMessage("gui.enter-ip", "Click to enter IP"));
            searchMeta.setLore(searchLore);
            searchItem.setItemMeta(searchMeta);
        }
        inventory.setItem(4, searchItem);

        int totalPages = (int) Math.ceil((double) ipResults.size() / 36.0);
        String pageInfo = plugin.getMessage("gui.page-info", "Page %current%/%total%")
                .replace("%current%", String.valueOf(currentPage + 1))
                .replace("%total%", String.valueOf(totalPages));
        int start = currentPage * 36;
        int end = Math.min(start + 36, ipResults.size());

        for (int i = start; i < end; i++) {
            IPResult result = ipResults.get(i);
            ItemStack item = new ItemStack(Material.PAPER);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.YELLOW + result.ip);
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + plugin.getMessage("gui.accounts", "Accounts: %count%").replace("%count%", String.valueOf(result.accountCount)));
                lore.add(ChatColor.GRAY + plugin.getMessage("gui.banned-accounts", "Banned Accounts: %count%").replace("%count%", String.valueOf(result.bannedPlayers)));
                lore.add(ChatColor.GRAY + plugin.getMessage("gui.ip-banned", "IP Banned: %status%").replace("%status%", result.ipBanned ? "Yes" : "No"));
                lore.add(ChatColor.YELLOW + plugin.getMessage("gui.click-for-details", "Click for details"));
                meta.setLore(lore);
                item.setItemMeta(meta);
            }
            inventory.setItem(i - start + 9, item);
            slotMap.put(i - start + 9, result);
        }

        ItemStack closeItem = new ItemStack(Material.BARRIER);
        ItemMeta closeMeta = closeItem.getItemMeta();
        if (closeMeta != null) {
            closeMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.close", "Close"));
            closeItem.setItemMeta(closeMeta);
        }
        inventory.setItem(49, closeItem);

        if (currentPage > 0) {
            ItemStack prevPage = new ItemStack(Material.ARROW);
            ItemMeta prevMeta = prevPage.getItemMeta();
            if (prevMeta != null) {
                prevMeta.setDisplayName(ChatColor.YELLOW + plugin.getMessage("gui.previous-page", "Previous page"));
                List<String> prevLore = new ArrayList<>();
                prevLore.add(ChatColor.GRAY + pageInfo);
                prevMeta.setLore(prevLore);
                prevPage.setItemMeta(prevMeta);
            }
            inventory.setItem(45, prevPage);
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
            inventory.setItem(45, noPrev);
        }

        if ((currentPage + 1) * 36 < ipResults.size()) {
            ItemStack nextPage = new ItemStack(Material.ARROW);
            ItemMeta nextMeta = nextPage.getItemMeta();
            if (nextMeta != null) {
                nextMeta.setDisplayName(ChatColor.YELLOW + plugin.getMessage("gui.next-page", "Next page"));
                List<String> nextLore = new ArrayList<>();
                nextLore.add(ChatColor.GRAY + pageInfo);
                nextMeta.setLore(nextLore);
                nextPage.setItemMeta(nextMeta);
            }
            inventory.setItem(53, nextPage);
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
            inventory.setItem(53, noNext);
        }

        player.updateInventory();
    }

    public void openSearchGUIByIP(Player player, String ip) {
        if (!player.hasPermission("playermanager.ip-search")) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.no-permission", "You don't have permission!"));
            return;
        }

        ItemStack loadingItem = new ItemStack(Material.REDSTONE);
        ItemMeta loadingMeta = loadingItem.getItemMeta();
        if (loadingMeta != null) {
            loadingMeta.setDisplayName(ChatColor.YELLOW + plugin.getMessage("gui.loading", "Loading..."));
            loadingItem.setItemMeta(loadingMeta);
        }
        inventory.clear();
        inventory.setItem(4, loadingItem);
        player.openInventory(inventory);
        setLastOpenedMenu(player.getUniqueId(), "ip_search");

        List<PlayerSearchGUI.PlayerResult> cachedResults = cachedSearchByIPResults.get(ip);
        if (cachedResults != null && !cachedResults.isEmpty()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && playersInGUI.contains(player.getUniqueId())) {
                    searchGUI.setCurrentSearch("IP: " + ip);
                    searchGUI.setCurrentPage(0);
                    searchGUI.setLastPage(player.getUniqueId(), 0);
                    searchGUI.setCachedResults(cachedResults);
                    searchGUI.setupSearchGUI(player, cachedResults);
                    player.closeInventory();
                    player.openInventory(searchGUI.getInventory());
                    searchGUI.getPlayersInGUI().add(player.getUniqueId());
                    searchGUI.setLastOpenedMenu(player.getUniqueId(), "search");
                    player.updateInventory();
                }
            });
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                long startTime = System.currentTimeMillis();
                List<PlayerSearchGUI.PlayerResult> results = getSearchResultsByIP(ip);
                cachedSearchByIPResults.put(ip, results);
                plugin.getLogger().info("Loaded " + results.size() + " accounts for IP " + ip + " in " + (System.currentTimeMillis() - startTime) + "ms");
                if (results.isEmpty()) {
                    plugin.getLogger().warning("No accounts found for IP: " + ip);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            player.sendMessage(ChatColor.RED + plugin.getMessage("error.no-accounts-for-ip", "No accounts found for IP: %ip%").replace("%ip%", ip));
                            openIPSearchGUI(player);
                        }
                    });
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline() && playersInGUI.contains(player.getUniqueId())) {
                        searchGUI.setCurrentSearch("IP: " + ip);
                        searchGUI.setCurrentPage(0);
                        searchGUI.setLastPage(player.getUniqueId(), 0);
                        searchGUI.setCachedResults(results);
                        searchGUI.setupSearchGUI(player, results);
                        player.closeInventory();
                        player.openInventory(searchGUI.getInventory());
                        searchGUI.getPlayersInGUI().add(player.getUniqueId());
                        searchGUI.setLastOpenedMenu(player.getUniqueId(), "search");
                        player.updateInventory();
                    } else {
                        plugin.getLogger().warning("Player " + player.getName() + " went offline before IP search results display");
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to load search results for IP " + ip + ": " + e.getMessage());
                e.printStackTrace();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        player.sendMessage(ChatColor.RED + plugin.getMessage("error.loading-failed", "Failed to load IP search data."));
                        openIPSearchGUI(player);
                    }
                });
            }
        });
    }

    private List<PlayerSearchGUI.PlayerResult> getSearchResultsByIP(String ip) {
        List<PlayerSearchGUI.PlayerResult> results = new ArrayList<>();
        ConfigurationSection playersSection = plugin.getPlayerDataConfig().getConfigurationSection("players");
        if (playersSection == null) {
            plugin.getLogger().warning("No players section found in player_data.yml for IP search: " + ip);
            return results;
        }

        long currentTime = System.currentTimeMillis();
        long maxInactivityDays = plugin.getConfig().getLong("max-inactivity-days", 30);
        long maxInactivityMillis = maxInactivityDays * 24 * 60 * 60 * 1000L;

        for (String uuid : playersSection.getKeys(false)) {
            try {
                if (ip.equals(plugin.getPlayerDataConfig().getString("players." + uuid + ".ip", ""))) {
                    String name = plugin.getPlayerDataConfig().getString("players." + uuid + ".name", "");
                    long lastActivity = Math.max(
                            plugin.getPlayerDataConfig().getLong("players." + uuid + ".last_login", 0L),
                            plugin.getPlayerDataConfig().getLong("players." + uuid + ".last_logout", 0L)
                    );
                    if (lastActivity > 0 && (currentTime - lastActivity) > maxInactivityMillis) {
                        continue;
                    }
                    if (name.isEmpty()) {
                        plugin.getLogger().warning("Skipping player with UUID " + uuid + " due to missing name");
                        continue;
                    }
                    OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(UUID.fromString(uuid));
                    String path = "players." + uuid + ".";
                    PlayerSearchGUI.PlayerResult result = new PlayerSearchGUI.PlayerResult();
                    result.uuid = uuid;
                    result.name = name;
                    result.firstPlayed = plugin.getPlayerDataConfig().getLong(path + "first_played", offlinePlayer.getFirstPlayed());
                    result.lastLogin = plugin.getPlayerDataConfig().getLong(path + "last_login", offlinePlayer.getLastPlayed());
                    result.lastLogout = plugin.getPlayerDataConfig().getLong(path + "last_logout", offlinePlayer.getLastPlayed());
                    result.online = offlinePlayer.isOnline();
                    result.banned = Bukkit.getBanList(BanList.Type.NAME).isBanned(name);
                    plugin.updatePlayerBanStatus(uuid, result.banned);
                    result.headTexture = plugin.getPlayerDataConfig().getString(path + "head_texture", "");
                    results.add(result);
                }
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid UUID " + uuid + " in player_data.yml for IP search: " + ip);
            }
        }

        results.sort(Comparator.comparing(r -> r.name.toLowerCase(), String.CASE_INSENSITIVE_ORDER));
        return results;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player) || !(event.getInventory().getHolder() instanceof PlayerManagerIpGUI)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getInventory().getSize()) {
            return;
        }
        event.setCancelled(true);

        String currentMenu = lastOpenedMenu.getOrDefault(player.getUniqueId(), "ip_search");
        if (currentMenu.equals("ip_search")) {
            if (slot == 4) {
                player.closeInventory();
                searchGUI.addPendingAction(player.getUniqueId(), (message, p) -> {
                    lastTarget.put(p.getUniqueId(), message);
                    openSearchGUIByIP(p, message);
                });
                player.sendMessage(ChatColor.YELLOW + plugin.getMessage("gui.enter-ip", "Enter IP address:"));
            } else if (slot == 49) {
                player.closeInventory();
            } else if (slot >= 9 && slot < 45) {
                IPResult result = slotMap.get(slot);
                if (result != null) {
                    lastTarget.put(player.getUniqueId(), result.ip);
                    openIPDetails(player, result);
                }
            } else if (slot == 45 && currentPage > 0) {
                currentPage--;
                setLastPage(player.getUniqueId(), currentPage);
                setupIPSearchGUI(player, cachedIPResults);
            } else if (slot == 53 && (currentPage + 1) * 36 < cachedIPResults.size()) {
                currentPage++;
                setLastPage(player.getUniqueId(), currentPage);
                setupIPSearchGUI(player, cachedIPResults);
            }
        } else if (currentMenu.equals("ip_details")) {
            handleIPDetailsClick(player, slot, event);
        }
    }

    public void openIPDetails(Player player, IPResult ipResult) {
        inventory.clear();
        slotMap.clear();
        setLastOpenedMenu(player.getUniqueId(), "ip_details");

        ItemStack banIpItem = new ItemStack(Material.REDSTONE_BLOCK);
        ItemMeta banIpMeta = banIpItem.getItemMeta();
        if (banIpMeta != null) {
            banIpMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.ban-ip", "Ban IP"));
            List<String> banIpLore = new ArrayList<>();
            banIpLore.add(ChatColor.GRAY + plugin.getMessage("gui.ban-ip-hint", "Click to ban this IP"));
            banIpMeta.setLore(banIpLore);
            banIpItem.setItemMeta(banIpMeta);
        }
        inventory.setItem(10, banIpItem);

        ItemStack banUsersItem = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta banUsersMeta = banUsersItem.getItemMeta();
        if (banUsersMeta != null) {
            banUsersMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.ban-users", "Ban All Users"));
            List<String> banUsersLore = new ArrayList<>();
            banUsersLore.add(ChatColor.GRAY + plugin.getMessage("gui.ban-users-hint", "Click to ban all non-admin users on this IP"));
            banUsersMeta.setLore(banUsersLore);
            banUsersItem.setItemMeta(banUsersMeta);
        }
        inventory.setItem(12, banUsersItem);

        ItemStack unbanUsersItem = new ItemStack(Material.EMERALD);
        ItemMeta unbanUsersMeta = unbanUsersItem.getItemMeta();
        if (unbanUsersMeta != null) {
            unbanUsersMeta.setDisplayName(ChatColor.GREEN + plugin.getMessage("gui.unban-users", "Unban All Users"));
            List<String> unbanUsersLore = new ArrayList<>();
            unbanUsersLore.add(ChatColor.GRAY + plugin.getMessage("gui.unban-users-hint", "Click to unban all users on this IP"));
            unbanUsersMeta.setLore(unbanUsersLore);
            unbanUsersItem.setItemMeta(unbanUsersMeta);
        }
        inventory.setItem(16, unbanUsersItem);

        ItemStack showUsersItem = new ItemStack(Material.BOOK);
        ItemMeta showUsersMeta = showUsersItem.getItemMeta();
        if (showUsersMeta != null) {
            showUsersMeta.setDisplayName(ChatColor.YELLOW + plugin.getMessage("gui.show-users", "Show All Users"));
            List<String> showUsersLore = new ArrayList<>();
            showUsersLore.add(ChatColor.GRAY + plugin.getMessage("gui.show-users-hint", "Click to view all users on this IP"));
            showUsersMeta.setLore(showUsersLore);
            showUsersItem.setItemMeta(showUsersMeta);
        }
        inventory.setItem(14, showUsersItem);

        ItemStack backItem = new ItemStack(Material.ARROW);
        ItemMeta backMeta = backItem.getItemMeta();
        if (backMeta != null) {
            backMeta.setDisplayName(ChatColor.YELLOW + plugin.getMessage("gui.back", "Back"));
            backItem.setItemMeta(backMeta);
        }
        inventory.setItem(22, backItem);

        ItemStack infoItem = new ItemStack(Material.PAPER);
        ItemMeta infoMeta = infoItem.getItemMeta();
        if (infoMeta != null) {
            infoMeta.setDisplayName(ChatColor.YELLOW + ipResult.ip);
            List<String> infoLore = new ArrayList<>();
            infoLore.add(ChatColor.GRAY + plugin.getMessage("gui.accounts", "Accounts: %count%").replace("%count%", String.valueOf(ipResult.accountCount)));
            infoLore.add(ChatColor.GRAY + plugin.getMessage("gui.banned-accounts", "Banned Accounts: %count%").replace("%count%", String.valueOf(ipResult.bannedPlayers)));
            infoLore.add(ChatColor.GRAY + plugin.getMessage("gui.ip-banned", "IP Banned: %status%").replace("%status%", ipResult.ipBanned ? "Yes" : "No"));
            infoMeta.setLore(infoLore);
            infoItem.setItemMeta(infoMeta);
        }
        inventory.setItem(4, infoItem);

        player.updateInventory();
    }

    public void handleIPDetailsClick(Player player, int slot, InventoryClickEvent event) {
        if (!player.hasPermission("playermanager.ip-search")) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.no-permission", "You don't have permission!"));
            event.setCancelled(true);
            return;
        }
        String ip = lastTarget.get(player.getUniqueId());
        if (ip == null) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.invalid-ip", "No IP selected!"));
            event.setCancelled(true);
            return;
        }
        if (slot == 22) {
            openIPSearchGUI(player);
            event.setCancelled(true);
            return;
        }
        if (!plugin.getConfig().getBoolean("features.punishment-system", true)) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.punishment-system-disabled", "Punishment system is disabled!"));
            event.setCancelled(true);
            return;
        }
        try {
            if (slot == 10) {
                if (!player.hasPermission("playermanager.ban")) {
                    player.sendMessage(ChatColor.RED + plugin.getMessage("error.no-permission", "You don't have permission!"));
                    return;
                }
                Bukkit.dispatchCommand(player, "ban-ip " + ip);
                player.sendMessage(ChatColor.YELLOW + plugin.getMessage("action.ban-ip", "Banned IP: %ip%").replace("%ip%", ip));
                cachedSearchByIPResults.remove(ip);
                openIPSearchGUI(player);
                event.setCancelled(true);
            } else if (slot == 12) {
                if (!player.hasPermission("playermanager.ban")) {
                    player.sendMessage(ChatColor.RED + plugin.getMessage("error.no-permission", "You don't have permission!"));
                    return;
                }
                List<String> users = getUsersByIP(ip);
                int bannedCount = 0;
                for (String user : users) {
                    OfflinePlayer offline = Bukkit.getOfflinePlayer(user);
                    if (offline != null && !plugin.getPlayerDataConfig().getBoolean("players." + offline.getUniqueId() + ".banned", false)) {
                        if (plugin.isAdminPlayer(offline)) {
                            plugin.getLogger().info("Skipping admin " + user + " during ban all users for IP " + ip);
                            continue;
                        }
                        Bukkit.dispatchCommand(player, "ban " + user);
                        plugin.updatePlayerBanStatus(offline.getUniqueId().toString(), true);
                        bannedCount++;
                    }
                }
                player.sendMessage(ChatColor.YELLOW + plugin.getMessage("action.ban-users", "Banned %count% users on IP: %ip%")
                        .replace("%count%", String.valueOf(bannedCount)).replace("%ip%", ip));
                cachedSearchByIPResults.remove(ip);
                openIPSearchGUI(player);
                event.setCancelled(true);
            } else if (slot == 16) {
                if (!player.hasPermission("playermanager.ban")) {
                    player.sendMessage(ChatColor.RED + plugin.getMessage("error.no-permission", "You don't have permission!"));
                    return;
                }
                List<String> users = getUsersByIP(ip);
                int unbannedCount = 0;
                for (String user : users) {
                    OfflinePlayer offline = Bukkit.getOfflinePlayer(user);
                    if (offline != null && plugin.getPlayerDataConfig().getBoolean("players." + offline.getUniqueId() + ".banned", false)) {
                        Bukkit.dispatchCommand(player, "unban " + user);
                        plugin.updatePlayerBanStatus(offline.getUniqueId().toString(), false);
                        unbannedCount++;
                    }
                }
                player.sendMessage(ChatColor.YELLOW + plugin.getMessage("action.unban-users", "Unbanned %count% users on IP: %ip%")
                        .replace("%count%", String.valueOf(unbannedCount)).replace("%ip%", ip));
                cachedSearchByIPResults.remove(ip);
                openIPSearchGUI(player);
                event.setCancelled(true);
            } else if (slot == 14) {
                openSearchGUIByIP(player, ip);
                event.setCancelled(true);
            }
        } catch (Exception e) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.command-failed", "Failed to execute command: %error%").replace("%error%", e.getMessage()));
            plugin.getLogger().warning("Error in handleIPDetailsClick for IP " + ip + ": " + e.getMessage());
            event.setCancelled(true);
        }
    }

    private List<String> getUsersByIP(String ip) {
        List<String> users = new ArrayList<>();
        ConfigurationSection playersSection = plugin.getPlayerDataConfig().getConfigurationSection("players");
        if (playersSection == null) {
            return users;
        }
        for (String uuid : playersSection.getKeys(false)) {
            if (ip.equals(plugin.getPlayerDataConfig().getString("players." + uuid + ".ip", ""))) {
                String name = plugin.getPlayerDataConfig().getString("players." + uuid + ".name", "");
                if (!name.isEmpty()) {
                    users.add(name);
                }
            }
        }
        return users;
    }

    private void setLastOpenedMenu(UUID playerUUID, String menu) {
        lastOpenedMenu.put(playerUUID, menu);
    }

    public void setLastTarget(UUID playerUUID, String target) {
        lastTarget.put(playerUUID, target);
    }

    private String getLastTarget(UUID playerUUID) {
        return lastTarget.get(playerUUID);
    }

    public void setLastPage(UUID playerUUID, int page) {
        lastPage.put(playerUUID, page);
    }

    public Set<UUID> getPlayersInGUI() {
        return playersInGUI;
    }

    public void clearCache() {
        cachedIPResults.clear();
        cachedSearchByIPResults.clear();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}