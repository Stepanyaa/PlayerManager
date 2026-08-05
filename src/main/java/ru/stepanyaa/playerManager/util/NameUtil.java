package ru.stepanyaa.playerManager.util;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.UUID;

public final class NameUtil {

    private NameUtil() {
    }
    public static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String value = ChatColor.stripColor(raw);
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t' || c == '\u00A7' || c < ' ') {
                continue;
            }
            if (c == '"' || c == '\'' || c == '\\' || c == ';') {
                continue;
            }
            sb.append(c);
        }
        return sb.toString().trim();
    }

    public static boolean isPlainName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        return name.matches("[A-Za-z0-9_]{1,16}");
    }

    public static String commandIdentifier(OfflinePlayer target, boolean pluginSupportsUuid) {
        String name = sanitize(target.getName());
        if (name.isEmpty()) {
            return target.getUniqueId().toString();
        }
        if (pluginSupportsUuid && !isPlainName(name)) {
            return target.getUniqueId().toString();
        }
        return name;
    }

    public static String displayName(OfflinePlayer target) {
        String name = target.getName();
        if (name == null || name.isEmpty()) {
            return target.getUniqueId().toString();
        }
        return name;
    }
    public static OfflinePlayer resolve(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        String value = input.trim();

        UUID uuid = parseUuid(value);
        if (uuid != null) {
            return Bukkit.getOfflinePlayer(uuid);
        }

        Player online = Bukkit.getPlayerExact(value);
        if (online != null) {
            return online;
        }

        for (OfflinePlayer candidate : Bukkit.getOfflinePlayers()) {
            if (candidate.getName() != null && candidate.getName().equalsIgnoreCase(value)) {
                return candidate;
            }
        }
        return null;
    }

    public static UUID parseUuid(String value) {
        if (value == null) {
            return null;
        }
        String clean = value.trim().replace("-", "");
        if (clean.length() != 32 || !clean.matches("[0-9a-fA-F]{32}")) {
            return null;
        }
        StringBuilder sb = new StringBuilder(clean);
        sb.insert(8, '-').insert(13, '-').insert(18, '-').insert(23, '-');
        try {
            return UUID.fromString(sb.toString());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
