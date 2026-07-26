package com.verdantvice.herbalist;

import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.VillagerCareerChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

/**
 * Keeps Bukkit event handling deliberately thin. All state transitions and
 * user feedback are owned by {@link HerbalistMarketManager}.
 */
public final class HerbalistListener implements Listener {
    private final JavaPlugin plugin;
    private final HerbalistMarketManager manager;

    public HerbalistListener(JavaPlugin plugin, HerbalistMarketManager manager) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.manager = Objects.requireNonNull(manager, "manager");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || !(event.getRightClicked() instanceof Villager villager)) {
            return;
        }
        if (!manager.isHerbalist(villager)
                && (villager.getProfession() != Villager.Profession.FARMER
                || manager.isEvaluated(villager))) {
            return;
        }
        event.setCancelled(true);
        manager.handleInteraction(event.getPlayer(), villager);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCareerChange(VillagerCareerChangeEvent event) {
        if (event.getProfession() != Villager.Profession.FARMER) {
            return;
        }
        Villager villager = event.getEntity();
        plugin.getServer().getScheduler().runTask(plugin, () ->
                manager.evaluateSilently(villager));
    }
}
