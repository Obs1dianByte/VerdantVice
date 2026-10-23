package com.verdantvice.processing;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.UUID;

public final class ProcessingMenuHolder implements InventoryHolder {
    private final UUID stationId;
    private final ProcessingStationType type;
    private Inventory inventory;

    public ProcessingMenuHolder(UUID stationId, ProcessingStationType type) {
        this.stationId = Objects.requireNonNull(stationId, "stationId");
        this.type = Objects.requireNonNull(type, "type");
    }

    public UUID stationId() {
        return stationId;
    }

    public ProcessingStationType type() {
        return type;
    }

    @Override
    public @NotNull Inventory getInventory() {
        if (inventory == null) throw new IllegalStateException("Inventory has not been attached");
        return inventory;
    }

    public void attach(Inventory inventory) {
        if (this.inventory != null) throw new IllegalStateException("Inventory already attached");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }
}
