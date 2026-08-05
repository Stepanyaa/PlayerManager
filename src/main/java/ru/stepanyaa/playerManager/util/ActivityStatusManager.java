package ru.stepanyaa.playerManager.util;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import ru.stepanyaa.playerManager.PlayerManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ActivityStatusManager {

    private final Plugin plugin;
    private final List<ActivityStatus> statuses = new ArrayList<ActivityStatus>();
    private ActivityStatus onlineStatus;
    private boolean enabled = true;
    private boolean hideWhenQuitKnown = true;

    public ActivityStatusManager(Plugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        statuses.clear();
        FileConfiguration config = plugin.getConfig();
        enabled = config.getBoolean("activity-status.enabled", true);
        hideWhenQuitKnown = config.getBoolean("activity-status.hide-when-quit-known", true);

        ConfigurationSection onlineSection = config.getConfigurationSection("activity-status.online");
        if (onlineSection != null) {
            onlineStatus = new ActivityStatus(
                    "online",
                    onlineSection.getString("icon", "\uD83D\uDFE2"),
                    onlineSection.getString("color", "&a"),
                    onlineSection.getString("text", "Currently online"),
                    0L);
        } else {
            onlineStatus = new ActivityStatus("online", "\uD83D\uDFE2", "&a", "Currently online", 0L);
        }

        List<Map<?, ?>> raw = config.getMapList("activity-status.statuses");
        if (raw != null) {
            for (Map<?, ?> entry : raw) {
                ActivityStatus status = fromMap(entry);
                if (status != null) {
                    statuses.add(status);
                }
            }
        }

        if (statuses.isEmpty()) {
            applyDefaults();
        }
    }

    private ActivityStatus fromMap(Map<?, ?> entry) {
        if (entry == null) {
            return null;
        }
        String id = string(entry.get("id"), "status");
        String icon = string(entry.get("icon"), "");
        String color = string(entry.get("color"), "&7");
        String text = string(entry.get("text"), "");
        long maxSeconds = seconds(entry);
        return new ActivityStatus(id, icon, color, text, maxSeconds);
    }

    private long seconds(Map<?, ?> entry) {
        Object value = entry.get("max-seconds");
        if (value != null) {
            return asLong(value);
        }
        value = entry.get("max-minutes");
        if (value != null) {
            long minutes = asLong(value);
            return minutes < 0L ? -1L : minutes * 60L;
        }
        value = entry.get("max-hours");
        if (value != null) {
            long hours = asLong(value);
            return hours < 0L ? -1L : hours * 3600L;
        }
        value = entry.get("max-days");
        if (value != null) {
            long days = asLong(value);
            return days < 0L ? -1L : days * 86400L;
        }
        return -1L;
    }

    private static long asLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private static String string(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private void applyDefaults() {
        statuses.add(new ActivityStatus("just_now", "\uD83D\uDFE2", "&a", "Was here just now", 300L));
        statuses.add(new ActivityStatus("within_hour", "\uD83D\uDFE1", "&e", "Was here less than an hour ago", 3600L));
        statuses.add(new ActivityStatus("today", "\uD83D\uDFE0", "&6", "Was here today", 86400L));
        statuses.add(new ActivityStatus("yesterday", "\uD83D\uDD35", "&b", "Was here yesterday", 172800L));
        statuses.add(new ActivityStatus("week", "\uD83D\uDFE3", "&d", "Away for a week", 604800L));
        statuses.add(new ActivityStatus("month", "\uD83D\uDD34", "&c", "Away for a month", 2592000L));
        statuses.add(new ActivityStatus("over_month", "⚫", "&8", "Away for over a month", 7776000L));
        statuses.add(new ActivityStatus("over_3_months", "⚫", "&8", "Away for over 3 months", 15552000L));
        statuses.add(new ActivityStatus("over_6_months", "⚫", "&8", "Away for over half a year", 31536000L));
        statuses.add(new ActivityStatus("over_year", "⚫", "&8", "Away for over a year", -1L));
    }

    public boolean isEnabled() {
        return enabled;
    }
    public boolean isHiddenWhenQuitKnown() {
        return hideWhenQuitKnown;
    }
    public ActivityStatus getOnlineStatus() {
        return localize(onlineStatus);
    }
    public List<ActivityStatus> getStatuses() {
        return new ArrayList<ActivityStatus>(statuses);
    }

    public ActivityStatus resolve(boolean online, long lastSeenMillis) {
        if (online) {
            return localize(onlineStatus);
        }
        long secondsSinceSeen;
        if (lastSeenMillis <= 0L) {
            secondsSinceSeen = Long.MAX_VALUE;
        } else {
            secondsSinceSeen = Math.max(0L, (System.currentTimeMillis() - lastSeenMillis) / 1000L);
        }
        for (ActivityStatus status : statuses) {
            if (status.matches(secondsSinceSeen)) {
                return localize(status);
            }
        }
        if (!statuses.isEmpty()) {
            return localize(statuses.get(statuses.size() - 1));
        }
        return localize(new ActivityStatus("unknown", "⚫", "&8", "Unknown", -1L));
    }

    private ActivityStatus localize(ActivityStatus status) {
        if (status == null) {
            return null;
        }
        String fallback = status.getText();
        if (fallback == null || fallback.isEmpty()) {
            fallback = status.getId().replace('_', ' ');
        }
        return status.withText(message("activity." + status.getId(), fallback));
    }

    private String message(String key, String fallback) {
        if (plugin instanceof PlayerManager) {
            String value = ((PlayerManager) plugin).getMessage(key, fallback);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return fallback;
    }

    public String render(boolean online, long lastSeenMillis) {
        return resolve(online, lastSeenMillis).render();
    }
}
