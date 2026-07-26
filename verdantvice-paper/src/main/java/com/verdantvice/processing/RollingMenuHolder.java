package com.verdantvice.processing;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public record RollingMenuHolder(UUID playerId, UUID sessionId) implements InventoryHolder {
    @Override
    public @NotNull Inventory getInventory() {
        throw new UnsupportedOperationException("Holder is an inventory identity only");
    }
}
