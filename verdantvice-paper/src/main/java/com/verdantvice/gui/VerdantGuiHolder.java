package com.verdantvice.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class VerdantGuiHolder implements InventoryHolder {
    private final UUID ownerUuid;
    private final UUID sessionId;
    private final MenuType type;
    private final int page;
    private final Map<Integer, String> actions;
    private Inventory inventory;

    public VerdantGuiHolder(
            UUID ownerUuid,
            MenuType type,
            int page,
            Map<Integer, String> actions
    ) {
        this.ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        this.sessionId = UUID.randomUUID();
        this.type = Objects.requireNonNull(type, "type");
        this.page = Math.max(0, page);
        // GuiManager populates slot actions while constructing the inventory.
        // The map never leaves this holder and is accessed only on the main thread.
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    public UUID ownerUuid() {
        return ownerUuid;
    }

    public UUID sessionId() {
        return sessionId;
    }

    public MenuType type() {
        return type;
    }

    public int page() {
        return page;
    }

    public String action(int slot) {
        return actions.get(slot);
    }

    public void attach(Inventory inventory) {
        if (this.inventory != null) {
            throw new IllegalStateException("Inventory already attached");
        }
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    @Override
    public @NotNull Inventory getInventory() {
        if (inventory == null) {
            throw new IllegalStateException("Inventory has not been attached");
        }
        return inventory;
    }
}
