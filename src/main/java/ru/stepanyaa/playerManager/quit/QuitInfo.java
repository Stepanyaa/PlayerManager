package ru.stepanyaa.playerManager.quit;

public class QuitInfo {

    public enum Category {
        UNDER_MINUTE("under_minute", 60L),
        UNDER_5_MINUTES("under_5_minutes", 300L),
        UNDER_10_MINUTES("under_10_minutes", 600L),
        UNDER_30_MINUTES("under_30_minutes", 1800L),
        UNDER_HOUR("under_hour", 3600L),
        TODAY("today", 86400L),
        YESTERDAY("yesterday", 172800L);

        private final String id;
        private final long maxSeconds;

        Category(String id, long maxSeconds) {
            this.id = id;
            this.maxSeconds = maxSeconds;
        }

        public String getId() {
            return id;
        }

        public long getMaxSeconds() {
            return maxSeconds;
        }

        public static Category of(long secondsAgo) {
            for (Category category : values()) {
                if (secondsAgo < category.maxSeconds) {
                    return category;
                }
            }
            return null;
        }
    }
    public static final String REASON_DISCONNECTED = "disconnected";
    public static final String REASON_KICKED = "kicked";
    public static final String REASON_BANNED = "banned";

    private final long time;
    private final String server;
    private final String world;
    private final double x;
    private final double y;
    private final double z;
    private final String reason;
    private final String reasonKey;
    private final boolean inCombat;

    public QuitInfo(long time, String server, String world, double x, double y, double z,
                    String reason, boolean inCombat) {
        this(time, server, world, x, y, z, reason, null, inCombat);
    }

    public QuitInfo(long time, String server, String world, double x, double y, double z,
                    String reason, String reasonKey, boolean inCombat) {
        this.time = time;
        this.server = server;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.reason = reason;
        this.reasonKey = reasonKey;
        this.inCombat = inCombat;
    }

    public long getTime() {
        return time;
    }

    public String getServer() {
        return server == null ? "?" : server;
    }

    public String getWorld() {
        return world == null ? "?" : world;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public String getReason() {
        return reason == null ? "" : reason;
    }
    public String getReasonKey() {
        return reasonKey == null ? "" : reasonKey;
    }
    public boolean isInCombat() {
        return inCombat;
    }

    public long secondsAgo() {
        if (time <= 0L) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, (System.currentTimeMillis() - time) / 1000L);
    }

    public Category category() {
        return Category.of(secondsAgo());
    }

    public String formatCoordinates() {
        return String.format("%s: %.1f, %.1f, %.1f", getWorld(), x, y, z);
    }
}
