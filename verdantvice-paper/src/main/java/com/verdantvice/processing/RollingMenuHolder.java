package com.verdantvice.processing;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.Objects;

public final class RollingMenuHolder implements InventoryHolder {
    private final UUID playerId;
    private final UUID sessionId;
    private Inventory inventory;

    public RollingMenuHolder(UUID playerId, UUID sessionId) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
    }

    public UUID playerId() { return playerId; }
    public UUID sessionId() { return sessionId; }

    public void attach(Inventory inventory) {
        if (this.inventory != null) throw new IllegalStateException("Inventory already attached");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    @Override
    public @NotNull Inventory getInventory() {
        if (inventory == null) throw new IllegalStateException("Inventory has not been attached");
        return inventory;
    }
}
