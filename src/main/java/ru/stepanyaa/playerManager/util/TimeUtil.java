package ru.stepanyaa.playerManager.util;

import java.util.Locale;
import java.util.concurrent.TimeUnit;


public final class TimeUtil {

    public static final long PERMANENT = -1L;

    private TimeUtil() {
    }

    public static long parseSeconds(String input) {
        if (input == null) {
            return Long.MIN_VALUE;
        }
        String value = input.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (value.isEmpty()) {
            return Long.MIN_VALUE;
        }
        if (value.equals("permanent") || value.equals("perm") || value.equals("forever")
                || value.equals("never") || value.equals("0") || value.equals("-1")) {
            return PERMANENT;
        }

        long total = 0L;
        long number = 0L;
        boolean sawDigit = false;
        boolean sawUnit = false;

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '0' && c <= '9') {
                sawDigit = true;
                number = number * 10L + (c - '0');
                if (number > 1000000000L) {
                    return Long.MIN_VALUE;
                }
                continue;
            }
            if (!sawDigit) {
                return Long.MIN_VALUE;
            }
            if (c == 'm' && i + 1 < value.length() && value.charAt(i + 1) == 'o') {
                total += number * 30L * 86400L;
                i++;
            } else {
                long multiplier = unitToSeconds(c);
                if (multiplier <= 0L) {
                    return Long.MIN_VALUE;
                }
                total += number * multiplier;
            }
            number = 0L;
            sawDigit = false;
            sawUnit = true;
        }

        if (sawDigit && !sawUnit) {
            total += number * 60L;
            sawUnit = true;
        } else if (sawDigit) {
            total += number * 60L;
        }

        if (!sawUnit || total <= 0L) {
            return Long.MIN_VALUE;
        }
        return total;
    }

    private static long unitToSeconds(char unit) {
        switch (unit) {
            case 's':
                return 1L;
            case 'm':
                return 60L;
            case 'h':
                return 3600L;
            case 'd':
                return 86400L;
            case 'w':
                return 604800L;
            case 'y':
                return 31536000L;
            default:
                return 0L;
        }
    }
    public static String toShortForm(long seconds) {
        if (seconds <= 0L) {
            return "permanent";
        }
        long days = seconds / 86400L;
        long hours = (seconds % 86400L) / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long secs = seconds % 60L;

        StringBuilder sb = new StringBuilder();
        if (days > 0L) {
            sb.append(days).append('d');
        }
        if (hours > 0L) {
            sb.append(hours).append('h');
        }
        if (minutes > 0L) {
            sb.append(minutes).append('m');
        }
        if (secs > 0L && sb.length() == 0) {
            sb.append(secs).append('s');
        }
        return sb.length() == 0 ? "1m" : sb.toString();
    }
    public static String toMinutes(long seconds) {
        long minutes = Math.max(1L, seconds / 60L);
        return String.valueOf(minutes);
    }
    public static String toTicks(long seconds) {
        return String.valueOf(Math.max(20L, seconds * 20L));
    }
    public static String humanize(long seconds, String dayLabel, String hourLabel, String minuteLabel, String secondLabel) {
        if (seconds <= 0L) {
            return "";
        }
        long days = TimeUnit.SECONDS.toDays(seconds);
        long hours = TimeUnit.SECONDS.toHours(seconds) % 24L;
        long minutes = TimeUnit.SECONDS.toMinutes(seconds) % 60L;
        long secs = seconds % 60L;

        StringBuilder sb = new StringBuilder();
        if (days > 0L) {
            sb.append(days).append(dayLabel).append(' ');
        }
        if (hours > 0L) {
            sb.append(hours).append(hourLabel).append(' ');
        }
        if (minutes > 0L) {
            sb.append(minutes).append(minuteLabel).append(' ');
        }
        if (sb.length() == 0) {
            sb.append(secs).append(secondLabel);
        }
        return sb.toString().trim();
    }
    public static long expiryFromNow(long seconds) {
        if (seconds <= 0L) {
            return 0L;
        }
        return System.currentTimeMillis() + (seconds * 1000L);
    }
}
