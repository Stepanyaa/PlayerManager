package ru.stepanyaa.playerManager.jail;

import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

import java.util.List;

public interface JailProvider {
    String getName();
    String getPluginName();
    boolean isAvailable();
    List<JailInfo> getJails();
    boolean jailPlayer(CommandSender executor, OfflinePlayer target, String jail, long durationSeconds, String reason);
    boolean unjailPlayer(CommandSender executor, OfflinePlayer target);
    boolean supportsPermanent();
    boolean supportsTimed();
    boolean supportsOffline();
}
