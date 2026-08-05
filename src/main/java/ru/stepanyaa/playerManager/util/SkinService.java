package ru.stepanyaa.playerManager.util;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import ru.stepanyaa.playerManager.storage.DataStore;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SkinService {

    private static final String TEXTURES = "textures";
    private final Plugin plugin;
    private final DataStore store;
    private final Map<UUID, String> queue = new LinkedHashMap<UUID, String>();
    private final Map<UUID, Boolean> inFlight = new ConcurrentHashMap<UUID, Boolean>();
    private final Map<UUID, Long> lastLookup = new ConcurrentHashMap<UUID, Long>();

    private Runnable guiRefresh;
    private volatile long lastRefreshRequest;

    private boolean enabled = true;
    private boolean useSkinsRestorer = true;
    private long cooldownMillis = 30L * 60L * 1000L;
    private int batchSize = 3;
    private long intervalTicks = 20L;

    private boolean skinsRestorer;
    private String skinsRestorerVersion = "";
    private int workerTaskId = -1;

    private Method createProfileMethod;
    private Method setPlayerProfileMethod;
    private Method setPropertyMethod;
    private Class<?> profilePropertyClass;

    public SkinService(Plugin plugin, DataStore store) {
        this.plugin = plugin;
        this.store = store;
        detectSkinsRestorer();
        detectProfileApi();
        reload();
        startWorker();
    }

    public void setGuiRefresh(Runnable guiRefresh) {
        this.guiRefresh = guiRefresh;
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("skins.enabled", true);
        useSkinsRestorer = plugin.getConfig().getBoolean("skins.use-skinsrestorer", true);
        cooldownMillis = Math.max(1L, plugin.getConfig().getLong("skins.refresh-cooldown-minutes", 30L)) * 60000L;
        batchSize = Math.max(1, plugin.getConfig().getInt("skins.batch-size", 3));
        long seconds = Math.max(1L, plugin.getConfig().getLong("skins.batch-interval-seconds", 1L));
        long ticks = seconds * 20L;
        if (ticks != intervalTicks) {
            intervalTicks = ticks;
            startWorker();
        }
    }

    private void startWorker() {
        stopWorker();
        try {
            workerTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, new Runnable() {
                @Override
                public void run() {
                    processQueue();
                }
            }, intervalTicks, intervalTicks).getTaskId();
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Could not start the skin worker: " + throwable.getMessage());
        }
    }

    public void stopWorker() {
        if (workerTaskId != -1) {
            try {
                Bukkit.getScheduler().cancelTask(workerTaskId);
            } catch (Throwable ignored) {
            }
            workerTaskId = -1;
        }
    }

    private void detectSkinsRestorer() {
        if (Bukkit.getPluginManager().getPlugin("SkinsRestorer") == null) {
            return;
        }
        if (classExists("net.skinsrestorer.api.SkinsRestorerProvider")) {
            skinsRestorer = true;
            skinsRestorerVersion = "v15+";
        } else if (classExists("net.skinsrestorer.api.SkinsRestorerAPI")) {
            skinsRestorer = true;
            skinsRestorerVersion = "v14";
        }
        if (skinsRestorer) {
            plugin.getLogger().info("Found SkinsRestorer (" + skinsRestorerVersion
                    + ") - head skins are taken from it, always asynchronously.");
        }
    }

    private void detectProfileApi() {
        try {
            createProfileMethod = Bukkit.class.getMethod("createProfile", UUID.class, String.class);
            profilePropertyClass = Class.forName("com.destroystokyo.paper.profile.ProfileProperty");
            Class<?> paperProfile = Class.forName("com.destroystokyo.paper.profile.PlayerProfile");
            setPropertyMethod = paperProfile.getMethod("setProperty", profilePropertyClass);
            setPlayerProfileMethod = SkullMeta.class.getMethod("setPlayerProfile", paperProfile);
        } catch (Throwable ignored) {
            createProfileMethod = null;
            setPlayerProfileMethod = null;
            setPropertyMethod = null;
            profilePropertyClass = null;
        }
    }

    public boolean hasSkinsRestorer() {
        return skinsRestorer;
    }

    public String getSkinsRestorerVersion() {
        return skinsRestorerVersion;
    }

    public int getCachedSkinCount() {
        return store == null ? 0 : store.skinCount();
    }

    public String getCachedTexture(UUID uuid) {
        return store == null ? "" : store.getSkin(uuid);
    }

    public String resolveTexture(OfflinePlayer player) {
        if (player == null) {
            return "";
        }
        UUID uuid = player.getUniqueId();
        String cached = getCachedTexture(uuid);
        if (!cached.isEmpty()) {
            return cached;
        }
        if (!enabled) {
            return "";
        }
        if (player.isOnline()) {
            String vanilla = fetchFromOnlineProfile(player.getPlayer());
            if (!vanilla.isEmpty()) {
                store(uuid, player.getName(), vanilla);
                return vanilla;
            }
        }
        request(uuid, player.getName());
        return "";
    }

    public void request(UUID uuid, String name) {
        if (!enabled || uuid == null || store == null) {
            return;
        }
        if (inFlight.containsKey(uuid)) {
            return;
        }
        Long last = lastLookup.get(uuid);
        if (last != null && System.currentTimeMillis() - last.longValue() < cooldownMillis) {
            return;
        }
        synchronized (queue) {
            if (!queue.containsKey(uuid)) {
                queue.put(uuid, name == null ? "" : name);
            }
        }
    }

    public void refresh(Player player) {
        if (player == null || !enabled) {
            return;
        }
        String vanilla = fetchFromOnlineProfile(player);
        if (!vanilla.isEmpty()) {
            store(player.getUniqueId(), player.getName(), vanilla);
        }
        if (skinsRestorer && useSkinsRestorer) {
            lastLookup.remove(player.getUniqueId());
            request(player.getUniqueId(), player.getName());
        }
    }

    private void processQueue() {
        if (!enabled) {
            return;
        }
        Map<UUID, String> batch = new LinkedHashMap<UUID, String>();
        synchronized (queue) {
            Iterator<Map.Entry<UUID, String>> iterator = queue.entrySet().iterator();
            while (iterator.hasNext() && batch.size() < batchSize) {
                Map.Entry<UUID, String> entry = iterator.next();
                iterator.remove();
                batch.put(entry.getKey(), entry.getValue());
            }
        }
        if (batch.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (Map.Entry<UUID, String> entry : batch.entrySet()) {
            UUID uuid = entry.getKey();
            inFlight.put(uuid, Boolean.TRUE);
            try {
                lastLookup.put(uuid, Long.valueOf(System.currentTimeMillis()));
                String texture = "";
                if (skinsRestorer && useSkinsRestorer) {
                    texture = fetchFromSkinsRestorer(uuid, entry.getValue());
                }
                if (!texture.isEmpty()) {
                    store(uuid, entry.getValue(), texture);
                    changed = true;
                }
            } catch (Throwable ignored) {
            } finally {
                inFlight.remove(uuid);
            }
        }
        if (changed) {
            requestGuiRefresh();
        }
    }

    private void requestGuiRefresh() {
        if (guiRefresh == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRefreshRequest < 2000L) {
            return;
        }
        lastRefreshRequest = now;
        try {
            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override
                public void run() {
                    Runnable refresh = guiRefresh;
                    if (refresh != null) {
                        try {
                            refresh.run();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void store(UUID uuid, String name, String texture) {
        if (store != null) {
            store.setSkin(uuid, name, texture);
        }
    }

    private String fetchFromSkinsRestorer(UUID uuid, String name) {
        if (!skinsRestorer) {
            return "";
        }
        if (Bukkit.isPrimaryThread()) {
            return "";
        }
        try {
            if ("v15+".equals(skinsRestorerVersion)) {
                return fetchSkinsRestorerModern(uuid, name);
            }
            return fetchSkinsRestorerLegacy(name);
        } catch (Throwable throwable) {
            return "";
        }
    }

    private String fetchSkinsRestorerModern(UUID uuid, String name) throws Exception {
        Class<?> provider = Class.forName("net.skinsrestorer.api.SkinsRestorerProvider");
        Object api = provider.getMethod("get").invoke(null);
        Object storage = invoke(api, "getPlayerStorage");
        if (storage == null) {
            return "";
        }
        Object result = invoke(storage, "getSkinForPlayer", uuid, name);
        if (result == null) {
            result = invoke(storage, "getSkinForPlayer", uuid, name, Boolean.FALSE);
        }
        if (result instanceof Optional) {
            Optional<?> optional = (Optional<?>) result;
            if (!optional.isPresent()) {
                return "";
            }
            result = optional.get();
        }
        if (result == null) {
            return "";
        }
        Object value = invoke(result, "getValue");
        return value == null ? "" : value.toString();
    }

    private String fetchSkinsRestorerLegacy(String name) throws Exception {
        Class<?> apiClass = Class.forName("net.skinsrestorer.api.SkinsRestorerAPI");
        Object api = apiClass.getMethod("getApi").invoke(null);
        if (api == null || name == null) {
            return "";
        }
        Object skinName = invoke(api, "getSkinName", name);
        Object property = invoke(api, "getSkinData", skinName == null ? name : skinName);
        if (property == null) {
            property = invoke(api, "getProfile", name);
        }
        if (property == null) {
            return "";
        }
        Object value = invoke(property, "getValue");
        return value == null ? "" : value.toString();
    }
    private String fetchFromOnlineProfile(Player player) {
        if (player == null) {
            return "";
        }
        try {
            Object profile = invoke(player, "getPlayerProfile");
            if (profile != null) {
                Object properties = invoke(profile, "getProperties");
                if (properties instanceof Collection) {
                    for (Object property : (Collection<?>) properties) {
                        Object propertyName = invoke(property, "getName");
                        if (propertyName != null && TEXTURES.equals(propertyName.toString())) {
                            Object value = invoke(property, "getValue");
                            if (value != null) {
                                return value.toString();
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Object handle = invoke(player, "getHandle");
            if (handle != null) {
                Object profile = invoke(handle, "getProfile");
                if (profile == null) {
                    profile = invoke(handle, "getGameProfile");
                }
                if (profile != null) {
                    Object properties = invoke(profile, "getProperties");
                    if (properties != null) {
                        Object collection = invoke(properties, "get", TEXTURES);
                        if (collection instanceof Collection) {
                            for (Object property : (Collection<?>) collection) {
                                Object value = invoke(property, "getValue");
                                if (value != null) {
                                    return value.toString();
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    public void applyTexture(SkullMeta meta, UUID uuid, String name, String texture, OfflinePlayer owner) {
        if (meta == null) {
            return;
        }
        if (texture != null && !texture.isEmpty()) {
            if (applyWithPaperProfile(meta, uuid, name, texture)) {
                return;
            }
            if (applyWithGameProfile(meta, uuid, name, texture)) {
                return;
            }
        }
        if (owner != null) {
            try {
                meta.setOwningPlayer(owner);
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean applyWithPaperProfile(SkullMeta meta, UUID uuid, String name, String texture) {
        if (createProfileMethod == null || setPlayerProfileMethod == null
                || setPropertyMethod == null || profilePropertyClass == null) {
            return false;
        }
        try {
            Object profile = createProfileMethod.invoke(null, uuid, name);
            Object property = profilePropertyClass
                    .getConstructor(String.class, String.class)
                    .newInstance(TEXTURES, texture);
            setPropertyMethod.invoke(profile, property);
            setPlayerProfileMethod.invoke(meta, profile);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean applyWithGameProfile(SkullMeta meta, UUID uuid, String name, String texture) {
        try {
            Class<?> gameProfileClass = Class.forName("com.mojang.authlib.GameProfile");
            Class<?> propertyClass = Class.forName("com.mojang.authlib.properties.Property");
            Object gameProfile = gameProfileClass
                    .getConstructor(UUID.class, String.class)
                    .newInstance(uuid, name == null ? "" : name);
            Object property = propertyClass
                    .getConstructor(String.class, String.class)
                    .newInstance(TEXTURES, texture);
            Object properties = gameProfileClass.getMethod("getProperties").invoke(gameProfile);
            properties.getClass().getMethod("put", Object.class, Object.class)
                    .invoke(properties, TEXTURES, property);

            Field profileField = findField(meta.getClass(), "profile");
            if (profileField == null) {
                return false;
            }
            profileField.setAccessible(true);
            if (!profileField.getType().isInstance(gameProfile)) {
                Object resolvable = wrapResolvable(profileField.getType(), gameProfile);
                if (resolvable == null) {
                    return false;
                }
                profileField.set(meta, resolvable);
                return true;
            }
            profileField.set(meta, gameProfile);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Object wrapResolvable(Class<?> type, Object gameProfile) {
        try {
            return type.getConstructor(gameProfile.getClass()).newInstance(gameProfile);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static Object invoke(Object target, String name, Object... args) {
        if (target == null) {
            return null;
        }
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterTypes().length != args.length) {
                continue;
            }
            try {
                method.setAccessible(true);
                return method.invoke(target, args);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
