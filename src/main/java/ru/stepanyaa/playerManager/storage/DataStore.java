package ru.stepanyaa.playerManager.storage;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class DataStore {

    public static final String TYPE_INVENTORY = "inventory";
    public static final String TYPE_ENDERCHEST = "enderchest";

    private final Plugin plugin;
    private final FileConfiguration legacyData;
    private final Runnable legacySaver;
    private final Map<UUID, String> skins = new ConcurrentHashMap<UUID, String>();
    private final Map<UUID, String> names = new ConcurrentHashMap<UUID, String>();
    private final Map<String, String> blobs = new ConcurrentHashMap<String, String>();
    private final Map<String, Long> blobTimes = new ConcurrentHashMap<String, Long>();

    private final Set<UUID> dirtySkins = ConcurrentHashMap.newKeySet();
    private final Set<String> dirtyBlobs = ConcurrentHashMap.newKeySet();

    private final Object ioLock = new Object();

    private Connection connection;
    private boolean database;
    private String backend = "yaml";

    private File skinFile;
    private YamlConfiguration skinYaml;
    private File blobFile;
    private YamlConfiguration blobYaml;

    private int flushTaskId = -1;
    private volatile boolean closed;

    public DataStore(Plugin plugin, FileConfiguration legacyData, Runnable legacySaver) {
        this.plugin = plugin;
        this.legacyData = legacyData;
        this.legacySaver = legacySaver;
    }

    public void start() {
        String requested = plugin.getConfig().getString("storage.type", "sqlite");
        if (requested == null) {
            requested = "sqlite";
        }
        if (!"yaml".equalsIgnoreCase(requested)) {
            openDatabase();
        }
        if (!database) {
            openYaml();
        }
        loadAll();
        migrateLegacyData();

        long interval = Math.max(1L, plugin.getConfig().getLong("storage.flush-interval-seconds", 5L)) * 20L;
        try {
            flushTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, new Runnable() {
                @Override
                public void run() {
                    flush();
                }
            }, interval, interval).getTaskId();
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Could not schedule the storage flush task: " + throwable.getMessage());
        }
        plugin.getLogger().info("Storage backend: " + backend + " (" + skins.size()
                + " cached skins, " + blobTimes.size() + " inventory snapshots).");
    }

    private void openDatabase() {
        try {
            try {
                Class.forName("org.sqlite.JDBC");
            } catch (Throwable ignored) {
            }
            File file = new File(plugin.getDataFolder(), "playermanager.db");
            if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
                plugin.getLogger().warning("Could not create the plugin folder for the database.");
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            Statement statement = connection.createStatement();
            try {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=NORMAL");
                statement.execute("CREATE TABLE IF NOT EXISTS skins ("
                        + "uuid TEXT PRIMARY KEY, name TEXT, texture TEXT, updated INTEGER)");
                statement.execute("CREATE TABLE IF NOT EXISTS blobs ("
                        + "uuid TEXT, type TEXT, data TEXT, updated INTEGER, PRIMARY KEY (uuid, type))");
            } finally {
                close(statement);
            }
            connection.setAutoCommit(true);
            database = true;
            backend = "SQLite (playermanager.db)";
        } catch (Throwable throwable) {
            database = false;
            connection = null;
            plugin.getLogger().warning("SQLite is not available (" + throwable.getMessage()
                    + "), falling back to asynchronous YAML storage.");
        }
    }

    private void openYaml() {
        skinFile = new File(plugin.getDataFolder(), "skins.yml");
        blobFile = new File(plugin.getDataFolder(), "inventories.yml");
        skinYaml = YamlConfiguration.loadConfiguration(skinFile);
        blobYaml = YamlConfiguration.loadConfiguration(blobFile);
        backend = "YAML (skins.yml + inventories.yml)";
    }

    private void loadAll() {
        if (database) {
            loadAllFromDatabase();
        } else {
            loadAllFromYaml();
        }
    }

    private void loadAllFromDatabase() {
        synchronized (ioLock) {
            Statement statement = null;
            ResultSet rows = null;
            try {
                statement = connection.createStatement();
                rows = statement.executeQuery("SELECT uuid, name, texture FROM skins");
                while (rows.next()) {
                    UUID uuid = parseUuid(rows.getString(1));
                    String texture = rows.getString(3);
                    if (uuid == null || texture == null || texture.isEmpty()) {
                        continue;
                    }
                    skins.put(uuid, texture);
                    String name = rows.getString(2);
                    if (name != null && !name.isEmpty()) {
                        names.put(uuid, name);
                    }
                }
                close(rows);
                rows = statement.executeQuery("SELECT uuid, type, updated FROM blobs");
                while (rows.next()) {
                    String key = rows.getString(1) + ":" + rows.getString(2);
                    blobTimes.put(key, Long.valueOf(rows.getLong(3)));
                }
            } catch (Throwable throwable) {
                plugin.getLogger().log(Level.WARNING, "Could not read the plugin database", throwable);
            } finally {
                close(rows);
                close(statement);
            }
        }
    }

    private void loadAllFromYaml() {
        ConfigurationSection section = skinYaml.getConfigurationSection("skins");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                UUID uuid = parseUuid(key);
                String texture = section.getString(key + ".texture", "");
                if (uuid == null || texture == null || texture.isEmpty()) {
                    continue;
                }
                skins.put(uuid, texture);
                String name = section.getString(key + ".name", "");
                if (name != null && !name.isEmpty()) {
                    names.put(uuid, name);
                }
            }
        }
        ConfigurationSection blobSection = blobYaml.getConfigurationSection("blobs");
        if (blobSection != null) {
            for (String key : blobSection.getKeys(false)) {
                for (String type : blobSection.getConfigurationSection(key).getKeys(false)) {
                    String data = blobSection.getString(key + "." + type + ".data", "");
                    if (data == null || data.isEmpty()) {
                        continue;
                    }
                    String composite = key + ":" + type;
                    blobs.put(composite, data);
                    blobTimes.put(composite, Long.valueOf(blobSection.getLong(key + "." + type + ".updated", 0L)));
                }
            }
        }
    }

    private void migrateLegacyData() {
        if (legacyData == null) {
            return;
        }
        ConfigurationSection players = legacyData.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        int moved = 0;
        for (String key : players.getKeys(false)) {
            UUID uuid = parseUuid(key);
            if (uuid == null) {
                continue;
            }
            String texture = players.getString(key + ".head_texture", "");
            if (texture != null && !texture.isEmpty()) {
                if (!skins.containsKey(uuid)) {
                    setSkin(uuid, players.getString(key + ".name", null), texture);
                }
                players.set(key + ".head_texture", null);
                moved++;
            }
            long updated = players.getLong(key + ".inventory_updated", 0L);
            String inventory = players.getString(key + "." + TYPE_INVENTORY, "");
            if (inventory != null && !inventory.isEmpty()) {
                if (getBlob(uuid, TYPE_INVENTORY) == null) {
                    setBlob(uuid, TYPE_INVENTORY, inventory, updated);
                }
                players.set(key + "." + TYPE_INVENTORY, null);
                moved++;
            }
            String ender = players.getString(key + "." + TYPE_ENDERCHEST, "");
            if (ender != null && !ender.isEmpty()) {
                if (getBlob(uuid, TYPE_ENDERCHEST) == null) {
                    setBlob(uuid, TYPE_ENDERCHEST, ender, updated);
                }
                players.set(key + "." + TYPE_ENDERCHEST, null);
                moved++;
            }
        }
        if (moved > 0) {
            plugin.getLogger().info("Moved " + moved + " entries out of player_data.yml into " + backend + ".");
            if (legacySaver != null) {
                legacySaver.run();
            }
        }
    }

    public void shutdown() {
        closed = true;
        if (flushTaskId != -1) {
            try {
                Bukkit.getScheduler().cancelTask(flushTaskId);
            } catch (Throwable ignored) {
            }
            flushTaskId = -1;
        }
        flush();
        synchronized (ioLock) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (Throwable ignored) {
                }
                connection = null;
            }
        }
    }

    public String getSkin(UUID uuid) {
        if (uuid == null) {
            return "";
        }
        String texture = skins.get(uuid);
        return texture == null ? "" : texture;
    }

    public boolean hasSkin(UUID uuid) {
        return uuid != null && skins.containsKey(uuid);
    }

    public int skinCount() {
        return skins.size();
    }

    public void setSkin(UUID uuid, String name, String texture) {
        if (uuid == null || texture == null || texture.isEmpty()) {
            return;
        }
        if (texture.equals(skins.get(uuid))) {
            return;
        }
        skins.put(uuid, texture);
        if (name != null && !name.isEmpty()) {
            names.put(uuid, name);
        }
        dirtySkins.add(uuid);
    }

    public String getBlob(UUID uuid, String type) {
        if (uuid == null) {
            return null;
        }
        String key = uuid + ":" + type;
        String cached = blobs.get(key);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        if (!database) {
            return null;
        }
        if (!blobTimes.containsKey(key)) {
            return null;
        }
        String loaded = readBlobFromDatabase(uuid, type);
        blobs.put(key, loaded == null ? "" : loaded);
        return loaded;
    }

    public long getBlobTime(UUID uuid, String type) {
        Long value = blobTimes.get(uuid + ":" + type);
        return value == null ? 0L : value.longValue();
    }

    public boolean hasBlob(UUID uuid, String type) {
        return uuid != null && blobTimes.containsKey(uuid + ":" + type);
    }

    public void setBlob(UUID uuid, String type, String data, long updated) {
        if (uuid == null || type == null) {
            return;
        }
        String key = uuid + ":" + type;
        blobs.put(key, data == null ? "" : data);
        blobTimes.put(key, Long.valueOf(updated <= 0L ? System.currentTimeMillis() : updated));
        dirtyBlobs.add(key);
    }

    private String readBlobFromDatabase(UUID uuid, String type) {
        synchronized (ioLock) {
            if (connection == null) {
                return null;
            }
            PreparedStatement statement = null;
            ResultSet rows = null;
            try {
                statement = connection.prepareStatement("SELECT data FROM blobs WHERE uuid = ? AND type = ?");
                statement.setString(1, uuid.toString());
                statement.setString(2, type);
                rows = statement.executeQuery();
                if (rows.next()) {
                    String data = rows.getString(1);
                    return data == null || data.isEmpty() ? null : data;
                }
            } catch (Throwable throwable) {
                plugin.getLogger().log(Level.WARNING, "Could not read a stored inventory", throwable);
            } finally {
                close(rows);
                close(statement);
            }
            return null;
        }
    }

    public void flush() {
        if (dirtySkins.isEmpty() && dirtyBlobs.isEmpty()) {
            return;
        }
        Map<UUID, String[]> skinBatch = new HashMap<UUID, String[]>();
        for (UUID uuid : dirtySkins.toArray(new UUID[0])) {
            dirtySkins.remove(uuid);
            skinBatch.put(uuid, new String[]{names.get(uuid), skins.get(uuid)});
        }
        Map<String, String> blobBatch = new HashMap<String, String>();
        for (String key : dirtyBlobs.toArray(new String[0])) {
            dirtyBlobs.remove(key);
            blobBatch.put(key, blobs.get(key));
        }
        try {
            if (database) {
                writeDatabaseBatch(skinBatch, blobBatch);
            } else {
                writeYamlBatch(skinBatch, blobBatch);
            }
        } catch (Throwable throwable) {
            dirtySkins.addAll(skinBatch.keySet());
            dirtyBlobs.addAll(blobBatch.keySet());
            plugin.getLogger().log(Level.WARNING, "Could not save plugin data, retrying later", throwable);
        }
    }

    private void writeDatabaseBatch(Map<UUID, String[]> skinBatch, Map<String, String> blobBatch) throws Exception {
        synchronized (ioLock) {
            if (connection == null) {
                return;
            }
            long now = System.currentTimeMillis();
            connection.setAutoCommit(false);
            PreparedStatement skinStatement = null;
            PreparedStatement blobStatement = null;
            try {
                if (!skinBatch.isEmpty()) {
                    skinStatement = connection.prepareStatement(
                            "INSERT OR REPLACE INTO skins (uuid, name, texture, updated) VALUES (?, ?, ?, ?)");
                    for (Map.Entry<UUID, String[]> entry : skinBatch.entrySet()) {
                        String texture = entry.getValue()[1];
                        if (texture == null || texture.isEmpty()) {
                            continue;
                        }
                        skinStatement.setString(1, entry.getKey().toString());
                        skinStatement.setString(2, entry.getValue()[0]);
                        skinStatement.setString(3, texture);
                        skinStatement.setLong(4, now);
                        skinStatement.addBatch();
                    }
                    skinStatement.executeBatch();
                }
                if (!blobBatch.isEmpty()) {
                    blobStatement = connection.prepareStatement(
                            "INSERT OR REPLACE INTO blobs (uuid, type, data, updated) VALUES (?, ?, ?, ?)");
                    for (Map.Entry<String, String> entry : blobBatch.entrySet()) {
                        int separator = entry.getKey().lastIndexOf(':');
                        if (separator <= 0) {
                            continue;
                        }
                        blobStatement.setString(1, entry.getKey().substring(0, separator));
                        blobStatement.setString(2, entry.getKey().substring(separator + 1));
                        blobStatement.setString(3, entry.getValue() == null ? "" : entry.getValue());
                        blobStatement.setLong(4, getBlobTimeByKey(entry.getKey()));
                        blobStatement.addBatch();
                    }
                    blobStatement.executeBatch();
                }
                connection.commit();
            } catch (Throwable throwable) {
                try {
                    connection.rollback();
                } catch (Throwable ignored) {
                }
                throw new Exception(throwable);
            } finally {
                close(skinStatement);
                close(blobStatement);
                try {
                    connection.setAutoCommit(true);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void writeYamlBatch(Map<UUID, String[]> skinBatch, Map<String, String> blobBatch) throws Exception {
        synchronized (ioLock) {
            for (Map.Entry<UUID, String[]> entry : skinBatch.entrySet()) {
                String path = "skins." + entry.getKey();
                skinYaml.set(path + ".name", entry.getValue()[0]);
                skinYaml.set(path + ".texture", entry.getValue()[1]);
            }
            for (Map.Entry<String, String> entry : blobBatch.entrySet()) {
                int separator = entry.getKey().lastIndexOf(':');
                if (separator <= 0) {
                    continue;
                }
                String path = "blobs." + entry.getKey().substring(0, separator)
                        + "." + entry.getKey().substring(separator + 1);
                blobYaml.set(path + ".data", entry.getValue());
                blobYaml.set(path + ".updated", Long.valueOf(getBlobTimeByKey(entry.getKey())));
            }
            if (!skinBatch.isEmpty()) {
                skinYaml.save(skinFile);
            }
            if (!blobBatch.isEmpty()) {
                blobYaml.save(blobFile);
            }
        }
    }

    private long getBlobTimeByKey(String key) {
        Long value = blobTimes.get(key);
        return value == null ? System.currentTimeMillis() : value.longValue();
    }

    public boolean isDatabase() {
        return database;
    }

    public String getBackend() {
        return backend;
    }

    public boolean isClosed() {
        return closed;
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void close(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable ignored) {
        }
    }
}
