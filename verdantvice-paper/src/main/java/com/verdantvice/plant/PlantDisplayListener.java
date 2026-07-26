package com.verdantvice.plant;

import org.bukkit.entity.Interaction;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

import java.util.Objects;

public final class PlantDisplayListener implements Listener {
    private final PlantDisplayService displays;
    private final PlantInteractionHandler interactions;

    public PlantDisplayListener(
            PlantDisplayService displays,
            PlantInteractionHandler interactions
    ) {
        this.displays = Objects.requireNonNull(displays, "displays");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        displays.restoreChunk(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        displays.unloadChunk(event.getChunk());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDamage(EntityDamageEvent event) {
        if (displays.isView(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Interaction)) {
            return;
        }
        displays.plantId(event.getRightClicked()).ifPresent(plantId -> {
            event.setCancelled(true);
            interactions.interact(event.getPlayer(), plantId, event.getHand());
        });
    }
}
