package ru.stepanyaa.playerManager.jail;

import org.bukkit.Material;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


public class JailQuickReasons {

    public static class Reason {
        private final String id;
        private final String text;
        private final Material icon;
        private final boolean custom;

        public Reason(String id, String text, Material icon, boolean custom) {
            this.id = id;
            this.text = text;
            this.icon = icon == null ? Material.PAPER : icon;
            this.custom = custom;
        }

        public String getId() {
            return id;
        }

        public String getText() {
            return text;
        }

        public Material getIcon() {
            return icon;
        }

        public boolean isCustom() {
            return custom;
        }
    }

    private final Plugin plugin;

    public JailQuickReasons(Plugin plugin) {
        this.plugin = plugin;
    }

    public List<Reason> getReasons() {
        List<Reason> reasons = new ArrayList<Reason>();
        List<Map<?, ?>> raw = plugin.getConfig().getMapList("punishments.quick-reasons");
        if (raw != null) {
            for (Map<?, ?> entry : raw) {
                if (entry == null) {
                    continue;
                }
                String text = string(entry.get("text"), "");
                if (text.isEmpty()) {
                    continue;
                }
                reasons.add(new Reason(
                        string(entry.get("id"), text.toLowerCase().replace(' ', '_')),
                        text,
                        material(string(entry.get("icon"), "PAPER")),
                        Boolean.TRUE.equals(entry.get("custom"))));
            }
        }
        if (reasons.isEmpty()) {
            reasons.add(new Reason("cheating", "Cheating", Material.DIAMOND_SWORD, false));
            reasons.add(new Reason("xray", "X-Ray", Material.DIAMOND_ORE, false));
            reasons.add(new Reason("bug_abuse", "Bug abuse", Material.REDSTONE, false));
            reasons.add(new Reason("griefing", "Griefing", Material.TNT, false));
            reasons.add(new Reason("ban_evasion", "Punishment evasion", Material.BARRIER, false));
            reasons.add(new Reason("swearing", "Offensive language", Material.BOOK, false));
            reasons.add(new Reason("spam", "Spam", Material.PAPER, false));
            reasons.add(new Reason("advertising", "Advertising", Material.OAK_SIGN, false));
            reasons.add(new Reason("other", "Other...", Material.NAME_TAG, true));
        }
        return reasons;
    }

    private static String string(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static Material material(String name) {
        try {
            return Material.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return Material.PAPER;
        }
    }
}
