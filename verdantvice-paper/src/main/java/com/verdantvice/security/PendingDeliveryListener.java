package com.verdantvice.security;

import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

import java.util.Objects;

/** Prevents a handoff from escaping its container before its durability barrier completes. */
public final class PendingDeliveryListener implements Listener {
    private final SecureItemService items;

    public PendingDeliveryListener(SecureItemService items) {
        this.items = Objects.requireNonNull(items, "items");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (items.deliveryPending(event.getCurrentItem()) || items.deliveryPending(event.getCursor())
                || (event.getHotbarButton() >= 0 && items.deliveryPending(
                event.getWhoClicked().getInventory().getItem(event.getHotbarButton())))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (items.deliveryPending(event.getOldCursor())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent event) {
        if (items.deliveryPending(event.getItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(InventoryPickupItemEvent event) {
        if (items.deliveryPending(event.getItem().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (items.deliveryPending(event.getItemDrop().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (items.deliveryPending(event.getMainHandItem()) || items.deliveryPending(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (containsPending(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        event.blockList().removeIf(this::containsPending);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        event.blockList().removeIf(this::containsPending);
    }

    private boolean containsPending(Block block) {
        if (!(block.getState() instanceof Container container)) return false;
        for (var item : container.getInventory().getStorageContents()) {
            if (items.deliveryPending(item)) return true;
        }
        return false;
    }
}
