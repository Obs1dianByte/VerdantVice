package com.verdantvice.genetics;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.logging.Level;

/** Replays durable child-seed receipts when the player's saved inventory is loaded. */
public final class GeneticsDeliveryListener implements Listener {
    private final JavaPlugin plugin;
    private final GeneticsManager genetics;

    public GeneticsDeliveryListener(JavaPlugin plugin, GeneticsManager genetics) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.genetics = Objects.requireNonNull(genetics, "genetics");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Let all join inventory restorers finish before looking for an existing ID.
        replay(event.getPlayer());
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player
                && genetics.hasPendingDelivery(player.getUniqueId())) {
            replay(player);
        }
    }

    private void replay(Player player) {
        plugin.getServer().getScheduler().runTask(plugin, () ->
                genetics.deliverPending(player).whenComplete((count, failure) -> {
                    if (failure != null) {
                        plugin.getLogger().log(Level.WARNING,
                                "Could not replay genetics deliveries for " + player.getUniqueId()
                                        + "; durable receipts remain pending", failure);
                    }
                }));
    }
}
