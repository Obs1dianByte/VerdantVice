package com.verdantvice.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.Inventory;

import java.util.Objects;

public final class GuiListener implements Listener {
    private final GuiManager guiManager;

    public GuiListener(GuiManager guiManager) {
        this.guiManager = Objects.requireNonNull(guiManager, "guiManager");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof VerdantGuiHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !holder.ownerUuid().equals(player.getUniqueId())
                || event.getRawSlot() < 0
                || event.getRawSlot() >= top.getSize()) {
            return;
        }
        guiManager.execute(player, holder, holder.action(event.getRawSlot()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof VerdantGuiHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrop(PlayerDropItemEvent event) {
        if (event.getPlayer().getOpenInventory().getTopInventory().getHolder(false)
                instanceof VerdantGuiHolder) {
            event.setCancelled(true);
        }
    }
}
