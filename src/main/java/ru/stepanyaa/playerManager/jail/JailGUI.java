package ru.stepanyaa.playerManager.jail;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import ru.stepanyaa.playerManager.PlayerManager;
import ru.stepanyaa.playerManager.util.TimeUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class JailGUI implements Listener, InventoryHolder {

    private static final long[] DURATIONS = {
            60L, 300L, 600L, 900L, 1800L, 3600L, 10800L, 21600L, 43200L,
            86400L, 259200L, 604800L, TimeUtil.PERMANENT
    };
    private static final String[] DURATION_KEYS = {
            "1m", "5m", "10m", "15m", "30m", "1h", "3h", "6h", "12h",
            "1d", "3d", "7d", "permanent"
    };

    private enum Stage {
        JAIL, TIME, REASON
    }

    private static class Session {
        UUID target;
        Stage stage = Stage.JAIL;
        String jail;
        long duration = TimeUtil.PERMANENT;
        int page;
        List<JailInfo> jails = new ArrayList<JailInfo>();
    }

    private final PlayerManager plugin;
    private final JailQuickReasons quickReasons;
    private final Map<UUID, Session> sessions = new HashMap<UUID, Session>();

    public JailGUI(PlayerManager plugin) {
        this.plugin = plugin;
        this.quickReasons = new JailQuickReasons(plugin);
    }

    @Override
    public Inventory getInventory() {
        return null;
    }

    public void open(Player admin, UUID targetUuid) {
        plugin.useLanguageOf(admin);
        if (!admin.hasPermission("playermanager.admin") && !admin.hasPermission("playermanager.jail")) {
            admin.sendMessage(ChatColor.RED + plugin.getMessage("error.no-permission", "You don't have permission!"));
            return;
        }
        JailManager manager = plugin.getJailManager();
        if (manager == null || !manager.hasProvider()) {
            admin.sendMessage(ChatColor.RED + plugin.getMessage("jail.no-provider",
                    "No supported jail plugin was found."));
            return;
        }

        Session session = new Session();
        session.target = targetUuid;
        session.stage = Stage.JAIL;
        session.jails = manager.getJails();
        sessions.put(admin.getUniqueId(), session);

        if (session.jails.isEmpty()) {
            admin.sendMessage(ChatColor.RED + plugin.getMessage("jail.no-jails",
                    "The jail plugin did not report any jails."));
            sessions.remove(admin.getUniqueId());
            return;
        }

        openJailSelection(admin, session);
    }

    private void openJailSelection(Player admin, Session session) {
        Inventory gui = Bukkit.createInventory(this, 54, trim(plugin.getMessage("jail.gui-select-title",
                "Select a jail") + " (" + plugin.getJailManager().getActiveProviderName() + ")"));

        int perPage = 45;
        int start = session.page * perPage;
        for (int i = 0; i < perPage; i++) {
            int index = start + i;
            if (index >= session.jails.size()) {
                break;
            }
            JailInfo jail = session.jails.get(index);
            List<String> lore = new ArrayList<String>();
            lore.add(ChatColor.GRAY + plugin.getMessage("jail.internal-name", "Internal name") + ": "
                    + ChatColor.WHITE + jail.getName());
            lore.add("");
            lore.add(ChatColor.YELLOW + plugin.getMessage("jail.click-select", "Click to select"));
            gui.setItem(i, item(Material.IRON_BARS, ChatColor.GOLD + jail.getDisplayName(), lore));
        }

        if (session.page > 0) {
            gui.setItem(45, item(Material.ARROW, ChatColor.YELLOW
                    + plugin.getMessage("gui.previous-page", "Previous page"), null));
        }
        if ((session.page + 1) * perPage < session.jails.size()) {
            gui.setItem(53, item(Material.ARROW, ChatColor.YELLOW
                    + plugin.getMessage("gui.next-page", "Next page"), null));
        }
        gui.setItem(49, item(Material.BARRIER, ChatColor.RED
                + plugin.getMessage("gui.back", "Back"), null));

        session.stage = Stage.JAIL;
        admin.openInventory(gui);
    }

    private void openTimeSelection(Player admin, Session session) {
        Inventory gui = Bukkit.createInventory(this, 27,
                trim(plugin.getMessage("jail.gui-time-title", "Jail duration") + ": " + session.jail));

        JailProvider provider = plugin.getJailManager().getActiveProvider();
        int[] slots = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 13};

        for (int i = 0; i < DURATIONS.length && i < slots.length; i++) {
            long seconds = DURATIONS[i];
            boolean permanent = seconds <= 0L;
            if (permanent && provider != null && !provider.supportsPermanent()) {
                continue;
            }
            if (!permanent && provider != null && !provider.supportsTimed()) {
                continue;
            }
            String label = plugin.getMessage("jail.time." + DURATION_KEYS[i], defaultLabel(i));
            List<String> lore = new ArrayList<String>();
            lore.add(ChatColor.GRAY + plugin.getMessage("jail.selected-jail", "Jail") + ": "
                    + ChatColor.WHITE + session.jail);
            lore.add("");
            lore.add(ChatColor.YELLOW + plugin.getMessage("jail.click-select", "Click to select"));
            gui.setItem(slots[i], item(permanent ? Material.BEDROCK : Material.CLOCK,
                    (permanent ? ChatColor.DARK_RED : ChatColor.GREEN) + label, lore));
        }

        gui.setItem(22, item(Material.BARRIER, ChatColor.RED
                + plugin.getMessage("gui.back", "Back"), null));

        session.stage = Stage.TIME;
        admin.openInventory(gui);
    }

    private String defaultLabel(int index) {
        switch (index) {
            case 0: return "1 minute";
            case 1: return "5 minutes";
            case 2: return "10 minutes";
            case 3: return "15 minutes";
            case 4: return "30 minutes";
            case 5: return "1 hour";
            case 6: return "3 hours";
            case 7: return "6 hours";
            case 8: return "12 hours";
            case 9: return "1 day";
            case 10: return "3 days";
            case 11: return "7 days";
            default: return "Permanent";
        }
    }

    private void openReasonSelection(Player admin, Session session) {
        List<JailQuickReasons.Reason> reasons = quickReasons.getReasons();
        int rows = Math.max(3, ((reasons.size() + 8) / 9) + 1);
        if (rows > 6) {
            rows = 6;
        }
        Inventory gui = Bukkit.createInventory(this, rows * 9,
                trim(plugin.getMessage("jail.gui-reason-title", "Select a reason")));

        for (int i = 0; i < reasons.size() && i < (rows - 1) * 9; i++) {
            JailQuickReasons.Reason reason = reasons.get(i);
            List<String> lore = new ArrayList<String>();
            lore.add(ChatColor.GRAY + plugin.getMessage("jail.selected-jail", "Jail") + ": "
                    + ChatColor.WHITE + session.jail);
            lore.add(ChatColor.GRAY + plugin.getMessage("jail.selected-time", "Duration") + ": "
                    + ChatColor.WHITE + humanDuration(session.duration));
            lore.add("");
            lore.add(reason.isCustom()
                    ? ChatColor.YELLOW + plugin.getMessage("jail.click-custom-reason", "Click and type the reason in chat")
                    : ChatColor.YELLOW + plugin.getMessage("jail.click-select", "Click to select"));
            gui.setItem(i, item(reason.getIcon(), ChatColor.AQUA + reason.getText(), lore));
        }

        gui.setItem(gui.getSize() - 5, item(Material.BARRIER, ChatColor.RED
                + plugin.getMessage("gui.back", "Back"), null));

        session.stage = Stage.REASON;
        admin.openInventory(gui);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Inventory clicked = event.getClickedInventory();
        if (clicked == null || !(clicked.getHolder() instanceof JailGUI)) {
            if (event.getInventory().getHolder() instanceof JailGUI) {
                event.setCancelled(true);
            }
            return;
        }
        event.setCancelled(true);

        Player admin = (Player) event.getWhoClicked();
        Session session = sessions.get(admin.getUniqueId());
        if (session == null) {
            admin.closeInventory();
            return;
        }

        int slot = event.getSlot();
        switch (session.stage) {
            case JAIL:
                handleJailStage(admin, session, slot);
                break;
            case TIME:
                handleTimeStage(admin, session, slot);
                break;
            case REASON:
                handleReasonStage(admin, session, slot);
                break;
            default:
                break;
        }
    }

    private void handleJailStage(Player admin, Session session, int slot) {
        if (slot == 49) {
            sessions.remove(admin.getUniqueId());
            admin.closeInventory();
            plugin.getPlayerSearchGUI().openPunishmentGUIByUuid(admin, session.target.toString());
            return;
        }
        if (slot == 45 && session.page > 0) {
            session.page--;
            openJailSelection(admin, session);
            return;
        }
        if (slot == 53 && (session.page + 1) * 45 < session.jails.size()) {
            session.page++;
            openJailSelection(admin, session);
            return;
        }
        int index = (session.page * 45) + slot;
        if (slot < 45 && index < session.jails.size()) {
            session.jail = session.jails.get(index).getName();
            openTimeSelection(admin, session);
        }
    }

    private void handleTimeStage(Player admin, Session session, int slot) {
        if (slot == 22) {
            openJailSelection(admin, session);
            return;
        }
        int[] slots = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 13};
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == slot) {
                session.duration = DURATIONS[i];
                if (plugin.getConfig().getBoolean("punishments.quick-reasons-enabled", true)) {
                    openReasonSelection(admin, session);
                } else {
                    askForReason(admin, session);
                }
                return;
            }
        }
    }

    private void handleReasonStage(Player admin, final Session session, int slot) {
        List<JailQuickReasons.Reason> reasons = quickReasons.getReasons();
        if (slot >= reasons.size()) {
            if (slot % 9 == 4 && slot >= reasons.size()) {
                openTimeSelection(admin, session);
            }
            return;
        }
        JailQuickReasons.Reason reason = reasons.get(slot);
        if (reason.isCustom()) {
            askForReason(admin, session);
            return;
        }
        execute(admin, session, reason.getText());
    }

    private void askForReason(final Player admin, final Session session) {
        admin.closeInventory();
        admin.sendMessage(ChatColor.YELLOW + plugin.getMessage("jail.enter-reason",
                "Type the jail reason in chat, or 'cancel' to abort."));
        plugin.getPlayerSearchGUI().addPendingAction(admin.getUniqueId(),
                new ru.stepanyaa.playerManager.PlayerSearchGUI.ChatAction() {
                    @Override
                    public void execute(String message, Player player) {
                        if (message.equalsIgnoreCase("cancel")) {
                            player.sendMessage(ChatColor.RED + plugin.getMessage("action.cancelled", "Cancelled"));
                            sessions.remove(player.getUniqueId());
                            return;
                        }
                        JailGUI.this.execute(player, session, message);
                    }
                });
    }

    private void execute(Player admin, Session session, String reason) {
        sessions.remove(admin.getUniqueId());
        admin.closeInventory();

        OfflinePlayer target = Bukkit.getOfflinePlayer(session.target);
        JailManager manager = plugin.getJailManager();
        boolean success = manager.jailPlayer(admin, target, session.jail, session.duration, reason);

        if (!success) {
            admin.sendMessage(ChatColor.RED + plugin.getMessage("jail.failed",
                    "The jail punishment could not be applied."));
            return;
        }

        plugin.getJailHistory().record(session.target, admin.getName(), session.jail,
                session.duration, reason, manager.getActiveProviderName());

        String name = target.getName() == null ? session.target.toString() : target.getName();
        String message = plugin.getMessage("jail.success", "Player successfully sent to jail.")
                .replace("%player%", name)
                .replace("%jail%", session.jail == null ? "-" : session.jail)
                .replace("%time%", humanDuration(session.duration))
                .replace("%reason%", reason);
        admin.sendMessage(ChatColor.GREEN + message);

        admin.sendMessage(ChatColor.GRAY + plugin.getMessage("jail.selected-jail", "Jail") + ": "
                + ChatColor.WHITE + session.jail + ChatColor.GRAY + " | "
                + plugin.getMessage("jail.selected-time", "Duration") + ": "
                + ChatColor.WHITE + humanDuration(session.duration) + ChatColor.GRAY + " | "
                + plugin.getMessage("gui.reason-label", "Reason") + ": " + ChatColor.WHITE + reason);
    }

    private String humanDuration(long seconds) {
        if (seconds <= 0L) {
            return plugin.getMessage("jail.time.permanent", "Permanent");
        }
        return plugin.getPunishmentManager().humanDuration(seconds);
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material == null ? Material.STONE : material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null) {
                meta.setLore(lore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private static String trim(String title) {
        return title.length() > 32 ? title.substring(0, 32) : title;
    }
}
