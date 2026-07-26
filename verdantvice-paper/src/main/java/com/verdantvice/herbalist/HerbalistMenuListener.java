package com.verdantvice.herbalist;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

import java.util.Objects;

/**
 * Protects every plugin-owned inventory from item injection and forwards only
 * the immutable action mapped by the inventory holder.
 */
public final class HerbalistMenuListener implements Listener {
    private final HerbalistMarketManager manager;

    public HerbalistMenuListener(HerbalistMarketManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof HerbalistMenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getClickedInventory() != top) {
            return;
        }
        String action = holder.action(event.getRawSlot());
        if (action != null) {
            manager.handleMenuAction(player, holder, action);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false)
                instanceof HerbalistMenuHolder) {
            event.setCancelled(true);
        }
    }
}
