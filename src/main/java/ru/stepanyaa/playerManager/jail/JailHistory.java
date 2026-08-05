package ru.stepanyaa.playerManager.jail;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class JailHistory {

    public static class Entry {
        private final long date;
        private final String admin;
        private final String jail;
        private final long durationSeconds;
        private final String reason;
        private final long releaseTime;
        private final String provider;
        private final boolean released;

        public Entry(long date, String admin, String jail, long durationSeconds, String reason,
                     long releaseTime, String provider, boolean released) {
            this.date = date;
            this.admin = admin;
            this.jail = jail;
            this.durationSeconds = durationSeconds;
            this.reason = reason;
            this.releaseTime = releaseTime;
            this.provider = provider;
            this.released = released;
        }

        public long getDate() {
            return date;
        }

        public String getAdmin() {
            return admin;
        }

        public String getJail() {
            return jail;
        }

        public long getDurationSeconds() {
            return durationSeconds;
        }

        public String getReason() {
            return reason;
        }

        public long getReleaseTime() {
            return releaseTime;
        }

        public String getProvider() {
            return provider;
        }

        public boolean isPermanent() {
            return durationSeconds <= 0L;
        }

        public boolean isReleased() {
            if (released) {
                return true;
            }
            return !isPermanent() && releaseTime > 0L && releaseTime <= System.currentTimeMillis();
        }

        public String formatDate(String pattern) {
            return new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date(date));
        }

        public String formatRelease(String pattern, String permanentLabel, String activeLabel) {
            if (isPermanent()) {
                return permanentLabel;
            }
            if (releaseTime <= 0L) {
                return activeLabel;
            }
            return new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date(releaseTime));
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("date", date);
            map.put("admin", admin);
            map.put("jail", jail);
            map.put("duration", durationSeconds);
            map.put("reason", reason);
            map.put("release_time", releaseTime);
            map.put("provider", provider);
            map.put("released", released);
            return map;
        }

        static Entry fromSection(ConfigurationSection section) {
            if (section == null) {
                return null;
            }
            return new Entry(
                    section.getLong("date", 0L),
                    section.getString("admin", "?"),
                    section.getString("jail", "?"),
                    section.getLong("duration", -1L),
                    section.getString("reason", ""),
                    section.getLong("release_time", 0L),
                    section.getString("provider", "?"),
                    section.getBoolean("released", false));
        }
    }

    private final FileConfiguration data;
    private final Runnable saver;
    private final int maxEntries;

    public JailHistory(FileConfiguration data, Runnable saver, int maxEntries) {
        this.data = data;
        this.saver = saver;
        this.maxEntries = Math.max(1, maxEntries);
    }

    private String path(UUID uuid) {
        return "players." + uuid + ".jail_history";
    }

    public void record(UUID uuid, String admin, String jail, long durationSeconds, String reason, String provider) {
        long now = System.currentTimeMillis();
        long release = durationSeconds > 0L ? now + (durationSeconds * 1000L) : 0L;
        Entry entry = new Entry(now, admin, jail, durationSeconds, reason, release, provider, false);

        List<Map<?, ?>> raw = new ArrayList<Map<?, ?>>(data.getMapList(path(uuid)));
        raw.add(entry.toMap());
        while (raw.size() > maxEntries) {
            raw.remove(0);
        }
        data.set(path(uuid), raw);
        save();
    }

    public void markReleased(UUID uuid) {
        List<Map<?, ?>> raw = new ArrayList<Map<?, ?>>(data.getMapList(path(uuid)));
        for (int i = raw.size() - 1; i >= 0; i--) {
            Map<Object, Object> entry = castMap(raw.get(i));
            Object released = entry.get("released");
            if (released instanceof Boolean && (Boolean) released) {
                continue;
            }
            entry.put("released", Boolean.TRUE);
            entry.put("release_time", System.currentTimeMillis());
            raw.set(i, entry);
            data.set(path(uuid), raw);
            save();
            return;
        }
    }

    public List<Entry> getHistory(UUID uuid) {
        List<Entry> entries = new ArrayList<Entry>();
        for (Map<?, ?> raw : data.getMapList(path(uuid))) {
            Entry entry = fromMap(raw);
            if (entry != null) {
                entries.add(entry);
            }
        }
        Collections.reverse(entries);
        return entries;
    }

    public boolean isCurrentlyJailed(UUID uuid) {
        for (Entry entry : getHistory(uuid)) {
            if (!entry.isReleased()) {
                return true;
            }
        }
        return false;
    }

    public void clear(UUID uuid) {
        data.set(path(uuid), null);
        save();
    }

    private void save() {
        if (saver != null) {
            saver.run();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> castMap(Map<?, ?> raw) {
        return new LinkedHashMap<Object, Object>((Map<Object, Object>) raw);
    }

    private static Entry fromMap(Map<?, ?> raw) {
        if (raw == null) {
            return null;
        }
        return new Entry(
                asLong(raw.get("date"), 0L),
                asString(raw.get("admin"), "?"),
                asString(raw.get("jail"), "?"),
                asLong(raw.get("duration"), -1L),
                asString(raw.get("reason"), ""),
                asLong(raw.get("release_time"), 0L),
                asString(raw.get("provider"), "?"),
                Boolean.TRUE.equals(raw.get("released")));
    }

    private static long asLong(Object value, long fallback) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String asString(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }
}
