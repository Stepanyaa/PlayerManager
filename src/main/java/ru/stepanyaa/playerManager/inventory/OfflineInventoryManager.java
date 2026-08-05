package ru.stepanyaa.playerManager.inventory;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;
import ru.stepanyaa.playerManager.storage.DataStore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class OfflineInventoryManager implements Listener {

    public static final int STORAGE_SIZE = 41; // 36 main + 4 armor + 1 offhand
    public static final int ENDER_SIZE = 27;

    private final Plugin plugin;
    private final FileConfiguration data;
    private final Runnable saver;
    private final DataStore store;
    private final Set<UUID> pendingApply =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<UUID, Boolean>());

    public OfflineInventoryManager(Plugin plugin, FileConfiguration data, Runnable saver, DataStore store) {
        this.plugin = plugin;
        this.data = data;
        this.saver = saver;
        this.store = store;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        snapshotAsync(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        applyPendingEdits(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        HumanEntity human = event.getPlayer();
        if (!(human instanceof Player)) {
            return;
        }
        Player player = (Player) human;
        if (!pendingApply.contains(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        applyPendingEdits(player);
    }

    public void snapshot(Player player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        ItemStack[] storage = captureStorage(player);
        ItemStack[] ender = resize(player.getEnderChest().getContents(), ENDER_SIZE);
        long now = System.currentTimeMillis();
        store.setBlob(uuid, DataStore.TYPE_INVENTORY, serialize(storage), now);
        store.setBlob(uuid, DataStore.TYPE_ENDERCHEST, serialize(ender), now);
    }

    public void snapshotAsync(Player player) {
        if (player == null) {
            return;
        }
        final UUID uuid = player.getUniqueId();
        final ItemStack[] storage = captureStorage(player);
        final ItemStack[] ender = resize(player.getEnderChest().getContents(), ENDER_SIZE);
        runAsync(new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                store.setBlob(uuid, DataStore.TYPE_INVENTORY, serialize(storage), now);
                store.setBlob(uuid, DataStore.TYPE_ENDERCHEST, serialize(ender), now);
            }
        });
    }

    private ItemStack[] captureStorage(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] storage = new ItemStack[STORAGE_SIZE];
        for (int i = 0; i < 36; i++) {
            storage[i] = copyOf(inventory.getItem(i));
        }
        ItemStack[] armor = inventory.getArmorContents();
        for (int i = 0; i < 4 && i < armor.length; i++) {
            storage[36 + i] = copyOf(armor[i]);
        }
        storage[40] = copyOf(inventory.getItemInOffHand());
        return storage;
    }

    public boolean hasPendingEdits(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        return pendingApply.contains(uuid)
                || data.getBoolean("players." + uuid + ".inventory_dirty", false)
                || data.getBoolean("players." + uuid + ".enderchest_dirty", false);
    }

    public void applyPendingEdits(final Player player) {
        if (player == null) {
            return;
        }
        final UUID uuid = player.getUniqueId();
        final boolean hasInventoryEdit = data.getBoolean("players." + uuid + ".inventory_dirty", false);
        final boolean hasEnderEdit = data.getBoolean("players." + uuid + ".enderchest_dirty", false);
        if (!hasInventoryEdit && !hasEnderEdit) {
            pendingApply.remove(uuid);
            return;
        }
        data.set("players." + uuid + ".inventory_dirty", null);
        data.set("players." + uuid + ".enderchest_dirty", null);
        save();

        long delay = plugin.getConfig().getLong("offline-inventory.apply-delay-ticks", 0L);
        Runnable apply = new Runnable() {
            @Override
            public void run() {
                applyNow(player, hasInventoryEdit, hasEnderEdit);
            }
        };

        if (delay <= 0L || !plugin.isEnabled()) {
            apply.run();
            return;
        }
        pendingApply.add(uuid);
        Bukkit.getScheduler().runTaskLater(plugin, apply, delay);
    }

    private void applyNow(Player player, boolean inventoryEdit, boolean enderEdit) {
        UUID uuid = player.getUniqueId();
        try {
            if (!player.isOnline()) {
                return;
            }
            if (inventoryEdit) {
                ItemStack[] storage = loadInventory(uuid);
                if (storage != null) {
                    PlayerInventory inventory = player.getInventory();
                    for (int i = 0; i < 36; i++) {
                        inventory.setItem(i, storage[i]);
                    }
                    ItemStack[] armor = new ItemStack[4];
                    System.arraycopy(storage, 36, armor, 0, 4);
                    inventory.setArmorContents(armor);
                    inventory.setItemInOffHand(storage[40] == null ? new ItemStack(Material.AIR) : storage[40]);
                    player.updateInventory();
                }
            }
            if (enderEdit) {
                ItemStack[] ender = loadEnderChest(uuid);
                if (ender != null) {
                    player.getEnderChest().setContents(resize(ender, ENDER_SIZE));
                }
            }
            String message = plugin.getConfig().getString("offline-inventory.notify-message", "");
            if (message != null && !message.isEmpty()) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
            }
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.WARNING, "Could not apply the stored inventory edit", throwable);
        } finally {
            pendingApply.remove(uuid);
        }
    }

    public ItemStack[] loadInventory(UUID uuid) {
        ItemStack[] items = deserialize(store.getBlob(uuid, DataStore.TYPE_INVENTORY));
        return items == null ? null : resize(items, STORAGE_SIZE);
    }

    public ItemStack[] loadEnderChest(UUID uuid) {
        ItemStack[] items = deserialize(store.getBlob(uuid, DataStore.TYPE_ENDERCHEST));
        return items == null ? null : resize(items, ENDER_SIZE);
    }

    public void saveInventory(UUID uuid, ItemStack[] storage) {
        store.setBlob(uuid, DataStore.TYPE_INVENTORY, serialize(resize(storage, STORAGE_SIZE)),
                System.currentTimeMillis());
        markDirty(uuid, "inventory_dirty");
    }

    public void saveEnderChest(UUID uuid, ItemStack[] contents) {
        store.setBlob(uuid, DataStore.TYPE_ENDERCHEST, serialize(resize(contents, ENDER_SIZE)),
                System.currentTimeMillis());
        markDirty(uuid, "enderchest_dirty");
    }

    public void saveInventoryAsync(final UUID uuid, ItemStack[] storage) {
        final ItemStack[] copy = resize(storage, STORAGE_SIZE);
        markDirty(uuid, "inventory_dirty");
        runAsync(new Runnable() {
            @Override
            public void run() {
                store.setBlob(uuid, DataStore.TYPE_INVENTORY, serialize(copy), System.currentTimeMillis());
            }
        });
    }

    public void saveEnderChestAsync(final UUID uuid, ItemStack[] contents) {
        final ItemStack[] copy = resize(contents, ENDER_SIZE);
        markDirty(uuid, "enderchest_dirty");
        runAsync(new Runnable() {
            @Override
            public void run() {
                store.setBlob(uuid, DataStore.TYPE_ENDERCHEST, serialize(copy), System.currentTimeMillis());
            }
        });
    }

    private void markDirty(UUID uuid, String flag) {
        data.set("players." + uuid + "." + flag, true);
        save();
    }

    public long getSnapshotTime(UUID uuid) {
        long time = store.getBlobTime(uuid, DataStore.TYPE_INVENTORY);
        return time > 0L ? time : data.getLong("players." + uuid + ".inventory_updated", 0L);
    }

    public boolean hasSnapshot(UUID uuid) {
        return store.hasBlob(uuid, DataStore.TYPE_INVENTORY);
    }

    public static ItemStack[] resize(ItemStack[] source, int size) {
        ItemStack[] target = new ItemStack[size];
        if (source != null) {
            System.arraycopy(source, 0, target, 0, Math.min(source.length, size));
        }
        return target;
    }

    public static ItemStack[] contentsOf(Inventory inventory, int size) {
        ItemStack[] items = new ItemStack[size];
        for (int i = 0; i < size && i < inventory.getSize(); i++) {
            items[i] = inventory.getItem(i);
        }
        return items;
    }

    private static ItemStack copyOf(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || item.getAmount() <= 0) {
            return null;
        }
        return item.clone();
    }

    private void runAsync(Runnable runnable) {
        if (plugin.isEnabled()) {
            try {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable);
                return;
            } catch (Throwable ignored) {
            }
        }
        runnable.run();
    }
    public String encode(ItemStack[] items) {
        return serialize(items);
    }

    public ItemStack[] decode(String encoded) {
        return deserialize(encoded);
    }

    private String serialize(ItemStack[] items) {
        if (items == null) {
            return null;
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        BukkitObjectOutputStream output = null;
        try {
            output = new BukkitObjectOutputStream(buffer);
            output.writeInt(items.length);
            for (ItemStack item : items) {
                output.writeObject(item);
            }
            output.flush();
            return Base64Coder.encodeLines(buffer.toByteArray());
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.WARNING, "Could not serialize an inventory", throwable);
            return null;
        } finally {
            close(output);
        }
    }

    private ItemStack[] deserialize(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return null;
        }
        BukkitObjectInputStream input = null;
        try {
            ByteArrayInputStream buffer = new ByteArrayInputStream(Base64Coder.decodeLines(encoded));
            input = new BukkitObjectInputStream(buffer);
            int length = input.readInt();
            if (length < 0 || length > 128) {
                return null;
            }
            ItemStack[] items = new ItemStack[length];
            for (int i = 0; i < length; i++) {
                Object value = input.readObject();
                items[i] = (value instanceof ItemStack) ? (ItemStack) value : null;
            }
            return items;
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.WARNING, "Could not read a stored inventory", throwable);
            return null;
        } finally {
            close(input);
        }
    }

    private void close(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable ignored) {
        }
    }

    private void save() {
        if (saver != null) {
            saver.run();
        }
    }
}
