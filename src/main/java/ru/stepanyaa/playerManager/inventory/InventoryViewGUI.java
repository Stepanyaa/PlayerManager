package ru.stepanyaa.playerManager.inventory;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;
import ru.stepanyaa.playerManager.PlayerManager;
import ru.stepanyaa.playerManager.quit.QuitInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class InventoryViewGUI implements Listener, InventoryHolder {
    public static final int SLOT_BACK = 45;
    public static final int SLOT_PLAYER_MENU = 46;
    public static final int SLOT_ENDER = 47;
    public static final int SLOT_TELEPORT = 48;
    public static final int SLOT_INFO = 49;
    public static final int SLOT_COPY_UUID = 50;
    public static final int SLOT_STATS = 51;
    public static final int SLOT_EFFECTS = 52;
    public static final int SLOT_REFRESH = 53;
    private static final int CONTROL_ROW = 45;
    private static final int TOP_SIZE = 54;
    private static final int ARMOR_OFFSET = 36;
    private static final int OFFHAND_SLOT = 40;
    private static final int MAX_TITLE_LENGTH = 32;
    private static class Session {
        final UUID targetUuid;
        final boolean enderMode;
        Inventory top;
        ItemStack[] mirror;
        ItemStack[] offlineLive;
        boolean offlineDirty;
        long lastPersist;
        boolean loading;

        Session(UUID targetUuid, boolean enderMode) {
            this.targetUuid = targetUuid;
            this.enderMode = enderMode;
            this.mirror = new ItemStack[enderMode
                    ? OfflineInventoryManager.ENDER_SIZE
                    : OfflineInventoryManager.STORAGE_SIZE];
        }
    }

    private final PlayerManager plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<UUID, Session>();
    private final Map<UUID, Boolean> reopening = new ConcurrentHashMap<UUID, Boolean>();
    private BukkitTask syncTask;

    public InventoryViewGUI(PlayerManager plugin) {
        this.plugin = plugin;
    }

    @Override
    public Inventory getInventory() {
        return null;
    }

    public void openInventory(Player admin, UUID targetUuid) {
        open(admin, targetUuid, false);
    }

    public void openEnderChest(Player admin, UUID targetUuid) {
        open(admin, targetUuid, true);
    }

    public void open(final Player admin, final UUID targetUuid, final boolean enderMode) {
        if (admin == null || targetUuid == null) {
            return;
        }
        plugin.useLanguageOf(admin);
        if (!admin.hasPermission("playermanager.admin") && !admin.hasPermission("playermanager.inventory")) {
            admin.sendMessage(ChatColor.RED + plugin.getMessage("error.no-permission", "You don't have permission!"));
            return;
        }

        if (Bukkit.getPlayer(targetUuid) != null) {
            openWith(admin, targetUuid, enderMode, null);
            return;
        }

        runAsync(new Runnable() {
            @Override
            public void run() {
                ItemStack[] loaded = null;
                try {
                    OfflineInventoryManager manager = plugin.getOfflineInventoryManager();
                    if (manager != null) {
                        loaded = enderMode ? manager.loadEnderChest(targetUuid) : manager.loadInventory(targetUuid);
                    }
                } catch (Throwable throwable) {
                    plugin.getLogger().log(Level.WARNING, "Could not read the stored inventory", throwable);
                }
                final ItemStack[] snapshot = loaded;
                runSync(new Runnable() {
                    @Override
                    public void run() {
                        if (!admin.isOnline()) {
                            return;
                        }
                        openWith(admin, targetUuid, enderMode, snapshot);
                    }
                });
            }
        });
    }

    private void openWith(Player admin, UUID targetUuid, boolean enderMode, ItemStack[] preloaded) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetUuid);
        String name = target.getName() == null ? targetUuid.toString() : target.getName();

        Session session = new Session(targetUuid, enderMode);
        int size = editableSize(session);
        Player online = Bukkit.getPlayer(targetUuid);
        if (online == null) {
            session.offlineLive = OfflineInventoryManager.resize(preloaded, size);
        }

        session.top = Bukkit.createInventory(this, TOP_SIZE, buildTitle(enderMode, name));
        fillTopPanel(session, target, online);
        sessions.put(admin.getUniqueId(), session);

        reopening.put(admin.getUniqueId(), Boolean.TRUE);
        admin.openInventory(session.top);
        reopening.remove(admin.getUniqueId());
        admin.updateInventory();
        ensureSyncTask();
    }

    private String buildTitle(boolean enderMode, String name) {
        String message = enderMode
                ? plugin.getMessage("gui.ender-chest-title", "&5Ender chest: %player%")
                : plugin.getMessage("gui.inventory-title", "&bInventory: %player%");
        String title;
        if (message.contains("%player%")) {
            title = message.replace("%player%", name);
        } else if (message.contains("{player}")) {
            title = message.replace("{player}", name);
        } else {
            title = message + ChatColor.YELLOW + name;
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            title = title.substring(0, MAX_TITLE_LENGTH);
            if (!title.isEmpty() && title.charAt(title.length() - 1) == ChatColor.COLOR_CHAR) {
                title = title.substring(0, title.length() - 1);
            }
        }
        return title;
    }

    public void refresh(final Player admin) {
        final Session session = sessions.get(admin.getUniqueId());
        if (session == null) {
            return;
        }
        syncSession(admin, session, true);

        final Player online = Bukkit.getPlayer(session.targetUuid);
        if (online != null) {
            fillTopPanel(session, Bukkit.getOfflinePlayer(session.targetUuid), online);
            admin.updateInventory();
            return;
        }

        session.loading = true;
        runAsync(new Runnable() {
            @Override
            public void run() {
                ItemStack[] loaded = null;
                try {
                    OfflineInventoryManager manager = plugin.getOfflineInventoryManager();
                    if (manager != null) {
                        loaded = session.enderMode
                                ? manager.loadEnderChest(session.targetUuid)
                                : manager.loadInventory(session.targetUuid);
                    }
                } catch (Throwable throwable) {
                    plugin.getLogger().log(Level.WARNING, "Could not read the stored inventory", throwable);
                }
                final ItemStack[] snapshot = loaded;
                runSync(new Runnable() {
                    @Override
                    public void run() {
                        session.loading = false;
                        if (!admin.isOnline() || sessions.get(admin.getUniqueId()) != session) {
                            return;
                        }
                        Player nowOnline = Bukkit.getPlayer(session.targetUuid);
                        if (nowOnline == null) {
                            session.offlineLive = OfflineInventoryManager.resize(snapshot, editableSize(session));
                        }
                        fillTopPanel(session, Bukkit.getOfflinePlayer(session.targetUuid), nowOnline);
                        admin.updateInventory();
                    }
                });
            }
        });
    }

    private void fillTopPanel(Session session, OfflinePlayer target, Player online) {
        Inventory top = session.top;
        if (top == null) {
            return;
        }
        top.clear();

        int size = editableSize(session);
        if (session.mirror == null || session.mirror.length != size) {
            session.mirror = new ItemStack[size];
        }
        ItemStack[] contents = readLive(session, online, size);
        for (int i = 0; i < size; i++) {
            ItemStack copy = copyOf(contents[i]);
            top.setItem(i, copy);
            session.mirror[i] = copyOf(copy);
        }

        ItemStack filler = pane(session.enderMode
                ? Material.BLACK_STAINED_GLASS_PANE
                : Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = size; i < CONTROL_ROW; i++) {
            top.setItem(i, filler);
        }

        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta meta = info.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + (target.getName() == null ? "?" : target.getName()));
            List<String> lore = new ArrayList<String>();
            boolean isOnline = online != null;
            lore.add(ChatColor.GRAY + plugin.getMessage("gui.status", "Status") + ": "
                    + plugin.getActivityStatusManager().render(isOnline,
                    isOnline ? System.currentTimeMillis() : lastSeen(session.targetUuid)));
            if (!isOnline) {
                long snapshot = plugin.getOfflineInventoryManager().getSnapshotTime(session.targetUuid);
                lore.add(ChatColor.GRAY + plugin.getMessage("gui.snapshot-time", "Snapshot") + ": "
                        + ChatColor.WHITE + (snapshot > 0L ? plugin.formatDatePublic(snapshot) : "-"));
                lore.add(ChatColor.YELLOW + plugin.getMessage("gui.offline-edit-hint",
                        "Changes are saved and applied on the next join"));
            }
            meta.setLore(lore);
            info.setItemMeta(meta);
        }
        buildControlRow(session, info);
    }

    private int editableSize(Session session) {
        return session.enderMode ? OfflineInventoryManager.ENDER_SIZE : OfflineInventoryManager.STORAGE_SIZE;
    }

    private void ensureSyncTask() {
        if (syncTask != null || !plugin.isEnabled()) {
            return;
        }
        long period = Math.max(1L, plugin.getConfig().getLong("inventory-viewer.live-sync-ticks", 10L));
        syncTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override
            public void run() {
                syncAll();
            }
        }, period, period);
    }

    private void stopSyncTaskIfIdle() {
        if (syncTask != null && sessions.isEmpty()) {
            syncTask.cancel();
            syncTask = null;
        }
    }

    private void syncAll() {
        if (sessions.isEmpty()) {
            stopSyncTaskIfIdle();
            return;
        }
        for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            Player admin = Bukkit.getPlayer(entry.getKey());
            if (admin == null) {
                continue;
            }
            try {
                syncSession(admin, entry.getValue(), false);
            } catch (Throwable throwable) {
                plugin.getLogger().log(Level.WARNING, "Live inventory synchronisation failed", throwable);
            }
        }
    }

    private void syncSession(Player admin, Session session, boolean persistNow) {
        if (session == null || session.top == null || session.loading) {
            return;
        }
        int size = editableSize(session);
        if (session.mirror == null || session.mirror.length != size) {
            session.mirror = new ItemStack[size];
        }

        Player online = Bukkit.getPlayer(session.targetUuid);
        boolean editable = isEditingAllowed(admin);
        ItemStack[] live = readLive(session, online, size);

        boolean guiChanged = false;
        boolean liveChanged = false;

        for (int slot = 0; slot < size; slot++) {
            ItemStack shown = session.top.getItem(slot);
            ItemStack mirrored = session.mirror[slot];
            ItemStack actual = slot < live.length ? live[slot] : null;

            if (editable && !sameItem(shown, mirrored)) {
                ItemStack copy = copyOf(shown);
                live[slot] = copy;
                session.mirror[slot] = copyOf(shown);
                if (online != null) {
                    applyLiveSlot(online, session.enderMode, slot, copy);
                }
                liveChanged = true;
            } else if (!sameItem(actual, mirrored)) {
                ItemStack copy = copyOf(actual);
                session.top.setItem(slot, copy);
                session.mirror[slot] = copyOf(actual);
                guiChanged = true;
            }
        }

        if (guiChanged) {
            admin.updateInventory();
        }
        if (liveChanged) {
            if (online != null) {
                online.updateInventory();
            } else {
                session.offlineLive = live;
                session.offlineDirty = true;
            }
        }
        if (liveChanged || persistNow) {
            persist(session, online, persistNow);
        }
    }

    private ItemStack[] readLive(Session session, Player online, int size) {
        if (online != null) {
            ItemStack[] live = new ItemStack[size];
            if (session.enderMode) {
                Inventory ender = online.getEnderChest();
                for (int i = 0; i < size && i < ender.getSize(); i++) {
                    live[i] = ender.getItem(i);
                }
            } else {
                PlayerInventory inventory = online.getInventory();
                for (int i = 0; i < 36 && i < size; i++) {
                    live[i] = inventory.getItem(i);
                }
                ItemStack[] armor = inventory.getArmorContents();
                for (int i = 0; i < 4 && i < armor.length && ARMOR_OFFSET + i < size; i++) {
                    live[ARMOR_OFFSET + i] = armor[i];
                }
                if (OFFHAND_SLOT < size) {
                    live[OFFHAND_SLOT] = inventory.getItemInOffHand();
                }
            }
            session.offlineLive = null;
            return live;
        }
        if (session.offlineLive == null || session.offlineLive.length != size) {
            session.offlineLive = OfflineInventoryManager.resize(session.offlineLive, size);
        }
        return session.offlineLive;
    }

    private void applyLiveSlot(Player online, boolean enderMode, int slot, ItemStack item) {
        if (enderMode) {
            online.getEnderChest().setItem(slot, item);
            return;
        }
        PlayerInventory inventory = online.getInventory();
        if (slot < 36) {
            inventory.setItem(slot, item);
        } else if (slot < ARMOR_OFFSET + 4) {
            ItemStack[] armor = inventory.getArmorContents();
            if (armor.length >= 4) {
                armor[slot - ARMOR_OFFSET] = item;
                inventory.setArmorContents(armor);
            }
        } else if (slot == OFFHAND_SLOT) {
            inventory.setItemInOffHand(item == null ? new ItemStack(Material.AIR) : item);
        }
    }

    private void persist(Session session, Player online, boolean force) {
        OfflineInventoryManager manager = plugin.getOfflineInventoryManager();
        if (manager == null || !plugin.getConfig().getBoolean("inventory-viewer.allow-editing", true)) {
            return;
        }
        long now = System.currentTimeMillis();
        long interval = Math.max(0L, plugin.getConfig().getLong("inventory-viewer.save-interval-ms", 1500L));
        if (!force && now - session.lastPersist < interval) {
            return;
        }
        session.lastPersist = now;

        if (online != null) {
            manager.snapshotAsync(online);
            session.offlineDirty = false;
            return;
        }
        if (!session.offlineDirty && !force) {
            return;
        }
        session.offlineDirty = false;
        ItemStack[] copy = OfflineInventoryManager.resize(session.offlineLive, editableSize(session));
        if (session.enderMode) {
            manager.saveEnderChestAsync(session.targetUuid, copy);
        } else {
            manager.saveInventoryAsync(session.targetUuid, copy);
        }
    }

    private boolean isEditingAllowed(Player admin) {
        return plugin.getConfig().getBoolean("inventory-viewer.allow-editing", true)
                && admin.hasPermission("playermanager.inventory.edit");
    }

    private void scheduleSync(final Player admin) {
        runSync(new Runnable() {
            @Override
            public void run() {
                Session session = sessions.get(admin.getUniqueId());
                if (session != null && admin.isOnline()) {
                    syncSession(admin, session, false);
                }
            }
        });
    }

    private static boolean sameItem(ItemStack first, ItemStack second) {
        boolean firstEmpty = first == null || first.getType() == Material.AIR || first.getAmount() <= 0;
        boolean secondEmpty = second == null || second.getType() == Material.AIR || second.getAmount() <= 0;
        if (firstEmpty || secondEmpty) {
            return firstEmpty && secondEmpty;
        }
        return first.getAmount() == second.getAmount() && first.isSimilar(second);
    }

    private static ItemStack copyOf(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || item.getAmount() <= 0) {
            return null;
        }
        return item.clone();
    }

    private void runAsync(Runnable runnable) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable);
        } else {
            runnable.run();
        }
    }

    private void runSync(Runnable runnable) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, runnable);
        } else {
            runnable.run();
        }
    }

    private void buildControlRow(Session session, ItemStack info) {
        Inventory top = session.top;
        for (int i = CONTROL_ROW; i < TOP_SIZE; i++) {
            top.setItem(i, pane(Material.BLACK_STAINED_GLASS_PANE, " "));
        }

        top.setItem(SLOT_BACK, button(Material.ARROW,
                plugin.getMessage("gui.button-back", "&cGo back"), null));
        top.setItem(SLOT_PLAYER_MENU, button(Material.PLAYER_HEAD,
                plugin.getMessage("gui.button-player-menu", "&eOpen player menu"), null));
        top.setItem(SLOT_ENDER, button(Material.ENDER_CHEST,
                session.enderMode
                        ? plugin.getMessage("gui.button-inventory", "&dOpen inventory")
                        : plugin.getMessage("gui.button-ender-chest", "&dOpen ender chest"), null));
        top.setItem(SLOT_TELEPORT, button(Material.ENDER_PEARL,
                plugin.getMessage("gui.button-teleport", "&bTeleport"), null));
        top.setItem(SLOT_INFO, info);
        top.setItem(SLOT_COPY_UUID, button(Material.NAME_TAG,
                plugin.getMessage("gui.button-copy-uuid", "&fCopy UUID"),
                ChatColor.GRAY + session.targetUuid.toString()));
        top.setItem(SLOT_STATS, button(Material.PAPER,
                plugin.getMessage("gui.button-stats", "&aStatistics"), null));
        top.setItem(SLOT_EFFECTS, button(Material.POTION,
                plugin.getMessage("gui.button-effects", "&5Active effects"), null));
        top.setItem(SLOT_REFRESH, button(Material.SUNFLOWER,
                plugin.getMessage("gui.button-refresh", "&eRefresh"), null));
    }

    private long lastSeen(UUID uuid) {
        long logout = plugin.getPlayerDataConfig().getLong("players." + uuid + ".last_logout", 0L);
        if (logout > 0L) {
            return logout;
        }
        return plugin.getPlayerDataConfig().getLong("players." + uuid + ".last_login", 0L);
    }

    private ItemStack button(Material material, String name, String loreLine) {
        ItemStack item = new ItemStack(material == null ? Material.STONE : material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            if (loreLine != null) {
                List<String> lore = new ArrayList<String>();
                lore.add(ChatColor.translateAlternateColorCodes('&', loreLine));
                meta.setLore(lore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack pane(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }

    public void restoreAdminInventory(Player admin) {
        final String path = "admin." + admin.getUniqueId() + ".saved_inventory";
        final String encoded = plugin.getPlayerDataConfig().getString(path);
        if (encoded == null) {
            return;
        }
        plugin.getPlayerDataConfig().set(path, null);
        final UUID adminUuid = admin.getUniqueId();
        runAsync(new Runnable() {
            @Override
            public void run() {
                final ItemStack[] contents = plugin.getOfflineInventoryManager().decode(encoded);
                runSync(new Runnable() {
                    @Override
                    public void run() {
                        Player online = Bukkit.getPlayer(adminUuid);
                        if (online == null) {
                            return;
                        }
                        online.getInventory().clear();
                        if (contents != null) {
                            int size = online.getInventory().getSize();
                            for (int i = 0; i < contents.length && i < size; i++) {
                                online.getInventory().setItem(i, contents[i]);
                            }
                        }
                        plugin.savePlayerDataConfig();
                        online.updateInventory();
                    }
                });
            }
        });
    }

    public void closeAll() {
        for (UUID uuid : new ArrayList<UUID>(sessions.keySet())) {
            Session session = sessions.remove(uuid);
            Player admin = Bukkit.getPlayer(uuid);
            if (admin != null && session != null) {
                syncSession(admin, session, true);
                admin.closeInventory();
            }
        }
        sessions.clear();
        stopSyncTaskIfIdle();
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player admin = (Player) event.getWhoClicked();
        Session session = sessions.get(admin.getUniqueId());
        if (session == null) {
            return;
        }
        int size = editableSize(session);
        boolean touchesTop = false;
        for (int raw : event.getRawSlots()) {
            if (raw >= TOP_SIZE) {
                continue;
            }
            if (raw >= size || !isEditingAllowed(admin)) {
                event.setCancelled(true);
                return;
            }
            touchesTop = true;
        }
        if (touchesTop) {
            scheduleSync(admin);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player admin = (Player) event.getWhoClicked();
        Session session = sessions.get(admin.getUniqueId());
        if (session == null) {
            return;
        }
        Inventory clicked = event.getClickedInventory();
        if (clicked == null) {
            event.setCancelled(true);
            return;
        }

        if (clicked.equals(admin.getInventory())) {
            if (!isEditingAllowed(admin) && event.isShiftClick()) {
                event.setCancelled(true);
                return;
            }
            scheduleSync(admin);
            return;
        }

        if (!(clicked.getHolder() instanceof InventoryViewGUI)) {
            return;
        }
        int slot = event.getSlot();
        if (slot >= CONTROL_ROW) {
            event.setCancelled(true);
            handleButton(admin, session, slot);
            return;
        }
        boolean decorative = slot >= editableSize(session);
        if (decorative || !isEditingAllowed(admin)) {
            event.setCancelled(true);
            return;
        }
        scheduleSync(admin);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }
        Player admin = (Player) event.getPlayer();
        if (Boolean.TRUE.equals(reopening.get(admin.getUniqueId()))) {
            return;
        }
        Session session = sessions.remove(admin.getUniqueId());
        if (session == null) {
            return;
        }
        syncSession(admin, session, true);
        restoreAdminInventory(admin);
        stopSyncTaskIfIdle();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        Session own = sessions.remove(player.getUniqueId());
        if (own != null) {
            syncSession(player, own, true);
            restoreAdminInventory(player);
        }

        for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            Session session = entry.getValue();
            if (!session.targetUuid.equals(player.getUniqueId())) {
                continue;
            }
            int size = editableSize(session);
            ItemStack[] live = readLive(session, player, size);
            ItemStack[] cached = new ItemStack[size];
            for (int i = 0; i < size; i++) {
                cached[i] = copyOf(live[i]);
            }
            session.offlineLive = cached;
        }
        stopSyncTaskIfIdle();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();

        String path = "admin." + player.getUniqueId() + ".saved_inventory";
        if (plugin.getPlayerDataConfig().getString(path) != null) {
            restoreAdminInventory(player);
        }

        for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            final Session session = entry.getValue();
            if (!session.targetUuid.equals(player.getUniqueId())) {
                continue;
            }
            final Player admin = Bukkit.getPlayer(entry.getKey());
            if (admin == null) {
                continue;
            }
            session.offlineLive = null;
            fillTopPanel(session, Bukkit.getOfflinePlayer(session.targetUuid), player);
            admin.updateInventory();
        }
    }

    private void handleButton(final Player admin, final Session session, int slot) {
        final UUID targetUuid = session.targetUuid;
        final OfflinePlayer target = Bukkit.getOfflinePlayer(targetUuid);

        switch (slot) {
            case SLOT_BACK: {
                syncSession(admin, session, true);
                sessions.remove(admin.getUniqueId());
                stopSyncTaskIfIdle();
                admin.closeInventory();
                plugin.getPlayerSearchGUI().openLastGUIMenu(admin);
                break;
            }
            case SLOT_PLAYER_MENU: {
                syncSession(admin, session, true);
                sessions.remove(admin.getUniqueId());
                stopSyncTaskIfIdle();
                admin.closeInventory();
                plugin.getPlayerSearchGUI().openPlayerMenuByUuid(admin, targetUuid.toString());
                break;
            }
            case SLOT_ENDER: {
                syncSession(admin, session, true);
                sessions.remove(admin.getUniqueId());
                open(admin, targetUuid, !session.enderMode);
                break;
            }
            case SLOT_TELEPORT: {
                teleport(admin, target, targetUuid);
                break;
            }
            case SLOT_COPY_UUID: {
                sendCopyableUuid(admin, targetUuid);
                break;
            }
            case SLOT_STATS: {
                sendStatistics(admin, target);
                break;
            }
            case SLOT_EFFECTS: {
                sendEffects(admin, target);
                break;
            }
            case SLOT_REFRESH: {
                refresh(admin);
                admin.sendMessage(ChatColor.GREEN + plugin.getMessage("action.refreshed", "Contents refreshed"));
                break;
            }
            default:
                break;
        }
    }

    private static String applyPlayer(String message, String name) {
        String safeName = name == null ? "" : name;
        if (message == null || message.isEmpty()) {
            return safeName;
        }
        if (message.contains("%player%")) {
            return message.replace("%player%", safeName);
        }
        return message + safeName;
    }

    private void teleport(Player admin, OfflinePlayer target, UUID targetUuid) {
        Player online = target.getPlayer();
        if (online != null) {
            Session session = sessions.remove(admin.getUniqueId());
            if (session != null) {
                syncSession(admin, session, true);
            }
            admin.closeInventory();
            restoreAdminInventory(admin);
            stopSyncTaskIfIdle();
            admin.teleport(online.getLocation());
            admin.sendMessage(ChatColor.GREEN + applyPlayer(
                    plugin.getMessage("action.teleported", "Teleported to %player%"), online.getName()));
            return;
        }

        QuitInfo info = plugin.getQuitTracker().getQuitInfo(targetUuid);
        if (info == null) {
            admin.sendMessage(ChatColor.RED + plugin.getMessage("error.no-last-location",
                    "No last known location for this player."));
            return;
        }
        World world = Bukkit.getWorld(info.getWorld());
        if (world == null) {
            admin.sendMessage(ChatColor.RED + plugin.getMessage("error.world-not-found", "World not found: ")
                    + info.getWorld());
            return;
        }
        Session session = sessions.remove(admin.getUniqueId());
        if (session != null) {
            syncSession(admin, session, true);
        }
        admin.closeInventory();
        restoreAdminInventory(admin);
        stopSyncTaskIfIdle();
        admin.teleport(new Location(world, info.getX(), info.getY(), info.getZ()));
        admin.sendMessage(ChatColor.GREEN + plugin.getMessage("action.teleported-last-location",
                "Teleported to the last known location."));
    }

    private void sendCopyableUuid(Player admin, UUID uuid) {
        String label = plugin.getMessage("action.uuid-copy", "Click to copy the UUID");
        TextComponent component = new TextComponent(ChatColor.GRAY + "UUID: " + ChatColor.WHITE + uuid);
        component.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, uuid.toString()));
        component.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder(label).create()));
        admin.spigot().sendMessage(component);
    }

    private void sendStatistics(final Player admin, final OfflinePlayer target) {
        admin.sendMessage(ChatColor.GOLD + "\u2500\u2500 " + plugin.getMessage("gui.button-stats", "Statistics")
                + ": " + ChatColor.YELLOW + (target.getName() == null ? "?" : target.getName()) + ChatColor.GOLD + " \u2500\u2500");
        try {
            int playTicks = target.getStatistic(Statistic.PLAY_ONE_MINUTE);
            admin.sendMessage(ChatColor.GRAY + plugin.getMessage("stats.playtime", "Playtime") + ": "
                    + ChatColor.WHITE + (playTicks / 20 / 3600) + "h " + ((playTicks / 20 / 60) % 60) + "m");
            admin.sendMessage(ChatColor.GRAY + plugin.getMessage("stats.deaths", "Deaths") + ": "
                    + ChatColor.WHITE + target.getStatistic(Statistic.DEATHS));
            admin.sendMessage(ChatColor.GRAY + plugin.getMessage("stats.player-kills", "Player kills") + ": "
                    + ChatColor.WHITE + target.getStatistic(Statistic.PLAYER_KILLS));
            admin.sendMessage(ChatColor.GRAY + plugin.getMessage("stats.mob-kills", "Mob kills") + ": "
                    + ChatColor.WHITE + target.getStatistic(Statistic.MOB_KILLS));
        } catch (Throwable throwable) {
            admin.sendMessage(ChatColor.RED + plugin.getMessage("error.no-statistics",
                    "No statistics available for this player."));
        }
    }

    private void sendEffects(Player admin, OfflinePlayer target) {
        Player online = target.getPlayer();
        if (online == null) {
            admin.sendMessage(ChatColor.RED + plugin.getMessage("error.player-offline", "Player is offline!"));
            return;
        }
        admin.sendMessage(ChatColor.GOLD + "\u2500\u2500 " + plugin.getMessage("gui.button-effects", "Active effects")
                + ": " + ChatColor.YELLOW + online.getName() + ChatColor.GOLD + " \u2500\u2500");
        if (online.getActivePotionEffects().isEmpty()) {
            admin.sendMessage(ChatColor.GRAY + plugin.getMessage("gui.no-effects", "No active effects"));
            return;
        }
        for (PotionEffect effect : online.getActivePotionEffects()) {
            int seconds = effect.getDuration() / 20;
            admin.sendMessage(ChatColor.GRAY + "\u2022 " + ChatColor.WHITE + effect.getType().getName()
                    + ChatColor.GRAY + " " + (effect.getAmplifier() + 1)
                    + ChatColor.DARK_GRAY + " (" + seconds + "s)");
        }
    }

    public boolean hasSession(Player admin) {
        return sessions.containsKey(admin.getUniqueId());
    }
}
