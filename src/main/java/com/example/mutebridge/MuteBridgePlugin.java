package com.example.mutebridge;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MuteBridgePlugin extends JavaPlugin implements Listener {

    private Object essentialsPlugin;
    private Object voicechatApi;
    private Method getPlayerMethod;
    private Method setMutedMethod;
    private Method isMutedMethod;
    private Method getMuteTimeoutMethod;

    private final Map<UUID, BukkitTask> unmuteTasks = new HashMap<>();
    private boolean apiReady = false;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);

        essentialsPlugin = Bukkit.getPluginManager().getPlugin("Essentials");
        if (essentialsPlugin == null) {
            getLogger().severe("EssentialsX not found! Disabling MuteBridge.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (!initEssentialsReflection()) {
            getLogger().warning("Could not hook EssentialsX mute-timeout method. Time-based mutes may not auto-unmute.");
        }

        // Try SVC API immediately; if not ready, retry with delay
        if (initVoiceChat()) {
            onApiReady();
        } else {
            getLogger().info("SimpleVoiceChat API not available yet, retrying in 5 seconds...");
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (initVoiceChat()) {
                    onApiReady();
                } else {
                    getLogger().severe("SimpleVoiceChat API not found after retry! Disabling MuteBridge.");
                    getServer().getPluginManager().disablePlugin(this);
                }
            }, 100L); // 5 seconds
        }
    }

    private void onApiReady() {
        apiReady = true;

        // Sync all currently online players
        syncAllMutes();

        // Periodic fallback sync every 30 seconds
        Bukkit.getScheduler().runTaskTimer(this, this::syncAllMutes, 20L, 600L);

        getLogger().info("MuteBridge enabled! Time-based mutes will sync to SimpleVoiceChat.");
    }

    /* ─────────────────── VoiceChat reflection ─────────────────── */

    private boolean initVoiceChat() {
        try {
            Plugin svcPlugin = Bukkit.getPluginManager().getPlugin("voicechat");
            if (svcPlugin == null) {
                getLogger().warning("SimpleVoiceChat plugin not found.");
                return false;
            }

            // Load the class using SVC's classloader to avoid classloader mismatch
            Class<?> serviceClass;
            try {
                serviceClass = Class.forName(
                    "de.maxhenkel.voicechat.api.BukkitVoicechatService",
                    true,
                    svcPlugin.getClass().getClassLoader()
                );
            } catch (ClassNotFoundException e) {
                getLogger().warning("BukkitVoicechatService class not found.");
                return false;
            }

            // Try ServicesManager.load() first
            Object service = Bukkit.getServicesManager().load(serviceClass);

            // Fallback: try getRegistration()
            if (service == null) {
                try {
                    Object provider = Bukkit.getServicesManager().getRegistration(serviceClass);
                    if (provider != null) {
                        Method getProvider = provider.getClass().getMethod("getProvider");
                        service = getProvider.invoke(provider);
                    }
                } catch (Exception ignored) {}
            }

            if (service == null) {
                return false; // Not ready yet, will retry
            }

            voicechatApi = tryInvoke(service, "getServerApi", "getVoicechatServerApi");
            if (voicechatApi == null) {
                getLogger().warning("Could not retrieve VoicechatServerApi from the service.");
                return false;
            }

            getPlayerMethod = findMethod(voicechatApi.getClass(), "getPlayer", UUID.class);
            if (getPlayerMethod == null) {
                getLogger().warning("Could not find getPlayer(UUID) method on VoicechatServerApi.");
                return false;
            }

            Class<?> vcPlayerClass = getPlayerMethod.getReturnType();
            setMutedMethod = findMethod(vcPlayerClass, "setMuted", boolean.class);
            isMutedMethod  = findMethod(vcPlayerClass, "isMuted");

            if (setMutedMethod == null) {
                getLogger().warning("Could not find setMuted(boolean) method on voicechat player class.");
                return false;
            }

            getLogger().info("SimpleVoiceChat API hooked successfully.");
            return true;
        } catch (Exception e) {
            getLogger().severe("Failed to initialize SimpleVoiceChat API: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private Object tryInvoke(Object target, String... names) {
        if (target == null) return null;
        for (String n : names) {
            try { return target.getClass().getMethod(n).invoke(target); }
            catch (Exception ignored) {}
        }
        return null;
    }

    private Method findMethod(Class<?> clazz, String name, Class<?>... params) {
        try { return clazz.getMethod(name, params); }
        catch (NoSuchMethodException e) { return null; }
    }

    /* ─────────────────── Essentials reflection ─────────────────── */

    private boolean initEssentialsReflection() {
        try {
            Plugin essPlugin = Bukkit.getPluginManager().getPlugin("Essentials");
            if (essPlugin == null) return false;
            Class<?> userClass = Class.forName("com.earth2me.essentials.User", true, essPlugin.getClass().getClassLoader());
            getMuteTimeoutMethod = findMethod(userClass, "getMuteTimeout");
            return getMuteTimeoutMethod != null;
        } catch (Exception e) {
            return false;
        }
    }

    private Object getEssentialsUser(Player player) {
        try {
            Method getUser = essentialsPlugin.getClass().getMethod("getUser", UUID.class);
            return getUser.invoke(essentialsPlugin, player.getUniqueId());
        } catch (Exception e) { return null; }
    }

    private boolean isEssentialsMuted(Player player) {
        try {
            Object user = getEssentialsUser(player);
            if (user == null) return false;
            Method isMuted = user.getClass().getMethod("isMuted");
            return Boolean.TRUE.equals(isMuted.invoke(user));
        } catch (Exception e) { return false; }
    }

    /**
     * Returns the timestamp (ms) when the EssentialsX mute expires,
     * or 0 if the mute is permanent / no timeout.
     */
    private long getMuteTimeout(Player player) {
        if (getMuteTimeoutMethod == null) return 0;
        try {
            Object user = getEssentialsUser(player);
            if (user == null) return 0;
            Object result = getMuteTimeoutMethod.invoke(user);
            if (result instanceof Long) return (Long) result;
            if (result instanceof Number) return ((Number) result).longValue();
            return 0;
        } catch (Exception e) { return 0; }
    }

    /* ─────────────────── SVC helpers ─────────────────── */

    private boolean isSVCMuted(Player player) {
        try {
            Object vcPlayer = getPlayerMethod.invoke(voicechatApi, player.getUniqueId());
            if (vcPlayer == null || isMutedMethod == null) return false;
            return (Boolean) isMutedMethod.invoke(vcPlayer);
        } catch (Exception e) { return false; }
    }

    private void setSVCMuted(Player player, boolean muted) {
        try {
            Object vcPlayer = getPlayerMethod.invoke(voicechatApi, player.getUniqueId());
            if (vcPlayer == null) return;
            setMutedMethod.invoke(vcPlayer, muted);
        } catch (Exception ignored) {}
    }

    /* ─────────────────── Scheduling logic ─────────────────── */

    private void scheduleUnmuteIfNeeded(Player player) {
        long timeout = getMuteTimeout(player);
        if (timeout <= 0) return; // permanent mute

        long now = System.currentTimeMillis();
        if (timeout <= now) return; // already expired

        long delayTicks = Math.max(1L, (timeout - now) / 50L);

        BukkitTask old = unmuteTasks.remove(player.getUniqueId());
        if (old != null) old.cancel();

        BukkitTask task = Bukkit.getScheduler().runTaskLater(this, () -> {
            unmuteTasks.remove(player.getUniqueId());
            Player online = Bukkit.getPlayer(player.getUniqueId());
            if (online != null && !isEssentialsMuted(online)) {
                setSVCMuted(online, false);
                getLogger().info("Auto-unmuted " + online.getName() + " in voicechat (EssentialsX mute expired).");
            }
        }, delayTicks);

        unmuteTasks.put(player.getUniqueId(), task);
        getLogger().info("Scheduled voicechat unmute for " + player.getName() + " in " + delayTicks + " ticks.");
    }

    private void cancelUnmuteTask(UUID uuid) {
        BukkitTask task = unmuteTasks.remove(uuid);
        if (task != null) task.cancel();
    }

    /* ─────────────────── Events ─────────────────── */

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!apiReady) return; // SVC API not ready yet
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            syncPlayerMute(p);
            if (isEssentialsMuted(p)) {
                scheduleUnmuteIfNeeded(p);
            }
        }, 10L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        cancelUnmuteTask(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!apiReady) return;
        String msg = event.getMessage().toLowerCase();
        if (msg.startsWith("/mute ") || msg.startsWith("/unmute ") ||
            msg.startsWith("/essentials:mute ") || msg.startsWith("/essentials:unmute ")) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    syncPlayerMute(p);
                }
            }, 5L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerCommand(ServerCommandEvent event) {
        if (!apiReady) return;
        String cmd = event.getCommand().toLowerCase();
        if (cmd.startsWith("mute ") || cmd.startsWith("unmute ") ||
            cmd.startsWith("essentials:mute ") || cmd.startsWith("essentials:unmute ")) {
            Bukkit.getScheduler().runTaskLater(this, this::syncAllMutes, 5L);
        }
    }

    /* ─────────────────── Core sync ─────────────────── */

    private void syncPlayerMute(Player player) {
        boolean essentialsMuted = isEssentialsMuted(player);
        boolean svcMuted = isSVCMuted(player);

        if (essentialsMuted && !svcMuted) {
            setSVCMuted(player, true);
            scheduleUnmuteIfNeeded(player);
            getLogger().info("Muted " + player.getName() + " in voicechat (EssentialsX mute).");
        } else if (!essentialsMuted && svcMuted) {
            setSVCMuted(player, false);
            cancelUnmuteTask(player.getUniqueId());
            getLogger().info("Unmuted " + player.getName() + " in voicechat (EssentialsX mute lifted).");
        }
    }

    private void syncAllMutes() {
        if (!apiReady) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            syncPlayerMute(player);
        }
    }

    @Override
    public void onDisable() {
        for (BukkitTask task : unmuteTasks.values()) {
            task.cancel();
        }
        unmuteTasks.clear();
        getLogger().info("MuteBridge disabled.");
    }
}
