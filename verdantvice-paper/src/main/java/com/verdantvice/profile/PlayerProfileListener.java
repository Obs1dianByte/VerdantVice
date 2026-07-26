package com.verdantvice.profile;

import com.verdantvice.notification.NotificationService;
import com.verdantvice.plant.PlantManager;
import com.verdantvice.processing.ProcessingManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class PlayerProfileListener implements Listener {
    private final PlayerProfileManager profiles;
    private final JavaPlugin plugin;
    private final PlantManager plants;
    private final ProcessingManager processing;
    private final NotificationService notifications;

    public PlayerProfileListener(
            JavaPlugin plugin,
            PlayerProfileManager profiles,
            PlantManager plants,
            ProcessingManager processing,
            NotificationService notifications
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.plants = Objects.requireNonNull(plants, "plants");
        this.processing = Objects.requireNonNull(processing, "processing");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        java.util.UUID playerId = event.getPlayer().getUniqueId();
        profiles.load(event.getPlayer()).thenRun(() ->
            org.bukkit.Bukkit.getScheduler().runTask(
                    plugin,
                    () -> {
                        org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(playerId);
                        if (player == null) {
                            return;
                        }
                        long ready = plants.all().stream()
                                .filter(plant -> plant.ownerUuid().equals(playerId))
                                .filter(plant -> plant.growthStage()
                                        == com.verdantvice.plant.GrowthStage.READY)
                                .count();
                        notifications.sendJoinSummary(
                                player,
                                (int) ready,
                                processing.activeProcesses(playerId)
                        );
                    }
            ));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        processing.abortForPlayer(event.getPlayer());
        profiles.unload(event.getPlayer().getUniqueId());
    }
}
