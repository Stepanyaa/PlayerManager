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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class JailHistoryGUI implements Listener, InventoryHolder {

    private final PlayerManager plugin;
    private final Map<UUID, UUID> viewing = new HashMap<UUID, UUID>();

    public JailHistoryGUI(PlayerManager plugin) {
        this.plugin = plugin;
    }

    @Override
    public Inventory getInventory() {
        return null;
    }

    public void open(Player admin, UUID targetUuid) {
        plugin.useLanguageOf(admin);
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetUuid);
        String name = target.getName() == null ? targetUuid.toString() : target.getName();

        String titleTemplate = plugin.getMessage("jail.history-title", "Jail history: %player%");
        String title = titleTemplate.contains("%player%")
                ? titleTemplate.replace("%player%", name)
                : titleTemplate + name;
        Inventory gui = Bukkit.createInventory(this, 54, trim(title));

        List<JailHistory.Entry> entries = plugin.getJailHistory().getHistory(targetUuid);
        String pattern = plugin.getConfig().getString("date-format", "dd.MM.yyyy HH:mm");

        for (int i = 0; i < entries.size() && i < 45; i++) {
            JailHistory.Entry entry = entries.get(i);
            List<String> lore = new ArrayList<String>();
            lore.add(ChatColor.GRAY + plugin.getMessage("jail.history-date", "Date") + ": "
                    + ChatColor.WHITE + entry.formatDate(pattern));
            lore.add(ChatColor.GRAY + plugin.getMessage("jail.history-admin", "Administrator") + ": "
                    + ChatColor.WHITE + entry.getAdmin());
            lore.add(ChatColor.GRAY + plugin.getMessage("jail.selected-jail", "Jail") + ": "
                    + ChatColor.WHITE + entry.getJail());
            lore.add(ChatColor.GRAY + plugin.getMessage("jail.selected-time", "Duration") + ": "
                    + ChatColor.WHITE + (entry.isPermanent()
                    ? plugin.getMessage("jail.time.permanent", "Permanent")
                    : plugin.getPunishmentManager().humanDuration(entry.getDurationSeconds())));
            lore.add(ChatColor.GRAY + plugin.getMessage("gui.reason-label", "Reason") + ": "
                    + ChatColor.WHITE + entry.getReason());
            lore.add(ChatColor.GRAY + plugin.getMessage("jail.history-release", "Release time") + ": "
                    + ChatColor.WHITE + entry.formatRelease(pattern,
                    plugin.getMessage("jail.time.permanent", "Permanent"),
                    plugin.getMessage("jail.history-active", "Active")));
            lore.add(ChatColor.DARK_GRAY + plugin.getMessage("jail.history-provider", "Provider") + ": "
                    + entry.getProvider());
            lore.add("");
            lore.add(entry.isReleased()
                    ? ChatColor.GREEN + plugin.getMessage("jail.history-expired", "Expired / released")
                    : ChatColor.RED + plugin.getMessage("jail.history-active", "Active"));

            gui.setItem(i, item(entry.isReleased() ? Material.PAPER : Material.IRON_BARS,
                    ChatColor.GOLD + "#" + (entries.size() - i) + " " + ChatColor.YELLOW + entry.getJail(), lore));
        }

        if (entries.isEmpty()) {
            gui.setItem(22, item(Material.BARRIER, ChatColor.GRAY
                    + plugin.getMessage("jail.history-empty", "No jail history"), null));
        }

        if (plugin.getJailHistory().isCurrentlyJailed(targetUuid)) {
            List<String> lore = new ArrayList<String>();
            lore.add(ChatColor.GRAY + plugin.getMessage("jail.click-select", "Click to select"));
            gui.setItem(48, item(Material.LIME_DYE, ChatColor.GREEN
                    + plugin.getMessage("jail.unjail", "Release from jail"), lore));
        }
        gui.setItem(49, item(Material.ARROW, ChatColor.RED + plugin.getMessage("gui.back", "Back"), null));

        viewing.put(admin.getUniqueId(), targetUuid);
        admin.openInventory(gui);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof JailHistoryGUI)) {
            return;
        }
        event.setCancelled(true);

        Player admin = (Player) event.getWhoClicked();
        UUID target = viewing.get(admin.getUniqueId());
        if (target == null) {
            admin.closeInventory();
            return;
        }
        int slot = event.getSlot();
        if (slot == 49) {
            viewing.remove(admin.getUniqueId());
            admin.closeInventory();
            plugin.getPlayerSearchGUI().openPlayerMenuByUuid(admin, target.toString());
            return;
        }
        if (slot == 48) {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(target);
            if (plugin.getJailManager().unjailPlayer(admin, offline)) {
                plugin.getJailHistory().markReleased(target);
                admin.sendMessage(ChatColor.GREEN + plugin.getMessage("jail.unjail-success",
                        "Player released from jail."));
            } else {
                admin.sendMessage(ChatColor.RED + plugin.getMessage("jail.unjail-failed",
                        "Could not release the player."));
            }
            open(admin, target);
        }
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
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
