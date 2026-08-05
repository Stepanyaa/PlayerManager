package ru.stepanyaa.playerManager.util;

import org.bukkit.ChatColor;

import java.util.Locale;

public class ActivityStatus {

    private final String id;
    private final String icon;
    private final String color;
    private final String text;
    private final long maxSeconds;

    public ActivityStatus(String id, String icon, String color, String text, long maxSeconds) {
        this.id = (id == null || id.isEmpty()) ? "status" : id;
        this.icon = icon == null ? "" : icon;
        this.color = color == null ? "&7" : color;
        this.text = text == null ? "" : text;
        this.maxSeconds = maxSeconds;
    }

    public ActivityStatus withText(String newText) {
        if (newText == null || newText.equals(this.text)) {
            return this;
        }
        return new ActivityStatus(id, icon, color, newText, maxSeconds);
    }

    public String getId() {
        return id;
    }

    public String getIcon() {
        return icon;
    }

    public String getColor() {
        return color;
    }

    public String getText() {
        return text;
    }

    public long getMaxSeconds() {
        return maxSeconds;
    }

    public boolean matches(long secondsSinceSeen) {
        return maxSeconds < 0L || secondsSinceSeen < maxSeconds;
    }

    public String colorCode() {
        String translated = translateColor(color);
        return translated.isEmpty() ? ChatColor.GRAY.toString() : translated;
    }

    public String render() {
        String prefix = icon.isEmpty() ? "" : icon + " ";
        return colorCode() + ChatColor.translateAlternateColorCodes('&', prefix + text);
    }
    public String renderPlain() {
        return colorCode() + ChatColor.translateAlternateColorCodes('&', text);
    }

    public ChatColor toChatColor() {
        String translated = colorCode();
        for (ChatColor value : ChatColor.values()) {
            if (translated.contains(value.toString())) {
                return value;
            }
        }
        return ChatColor.GRAY;
    }

    public static String translateColor(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (value.indexOf('&') >= 0 || value.indexOf(ChatColor.COLOR_CHAR) >= 0) {
            return ChatColor.translateAlternateColorCodes('&', value);
        }
        if (value.length() == 1) {
            ChatColor byChar = ChatColor.getByChar(value.charAt(0));
            if (byChar != null) {
                return byChar.toString();
            }
        }
        if (value.charAt(0) == '#') {
            String hex = hexColor(value.substring(1));
            if (hex != null) {
                return hex;
            }
        }
        String name = value.toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        try {
            return ChatColor.valueOf(name).toString();
        } catch (IllegalArgumentException ignored) {
        }
        String hex = hexColor(value);
        return hex == null ? "" : hex;
    }

    private static String hexColor(String hex) {
        if (hex == null || hex.length() != 6) {
            return null;
        }
        StringBuilder builder = new StringBuilder(14);
        builder.append(ChatColor.COLOR_CHAR).append('x');
        for (int i = 0; i < 6; i++) {
            char character = Character.toLowerCase(hex.charAt(i));
            if (Character.digit(character, 16) < 0) {
                return null;
            }
            builder.append(ChatColor.COLOR_CHAR).append(character);
        }
        return builder.toString();
    }
}
