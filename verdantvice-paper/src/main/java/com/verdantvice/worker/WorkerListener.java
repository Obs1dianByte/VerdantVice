package com.verdantvice.worker;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.VillagerCareerChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.logging.Level;

/**
 * Thin Bukkit event adapter; all decisions and persistence remain in the
 * worker facade.
 */
public final class WorkerListener implements Listener {
    private final WorkerManager workers;

    public WorkerListener(WorkerManager workers) {
        this.workers = Objects.requireNonNull(workers, "workers");
    }

    // The HIGH-priority herbalist menu cancels ordinary villager interactions.
    // Claim authentic hiring contracts first, while still respecting an event
    // already cancelled by another protection listener.
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onContractUse(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || !(event.getRightClicked() instanceof Villager villager)) {
            return;
        }
        ItemStack held = event.getPlayer().getInventory().getItemInMainHand();
        if (!workers.isHiringContract(held)) {
            return;
        }
        event.setCancelled(true);
        workers.hire(event.getPlayer(), villager)
                .exceptionally(failure -> {
                    event.getPlayer().getServer().getLogger().log(
                            Level.WARNING,
                            "VerdantVice worker hire listener failed",
                            failure
                    );
                    return null;
                });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorkerDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Villager villager
                && workers.isWorker(villager)) {
            workers.handleDeath(villager);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCareerChange(VillagerCareerChangeEvent event) {
        if (workers.protectCareerChange(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            if (entity instanceof Villager villager) {
                workers.bind(villager);
            }
        }
    }
}
