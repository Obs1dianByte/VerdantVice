package com.verdantvice.herbalist;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class HerbalistMenuHolder implements InventoryHolder {
    private final UUID playerUuid;
    private final UUID herbalistUuid;
    private final UUID sessionUuid = UUID.randomUUID();
    private final HerbalistMenuType type;
    private final Map<Integer, String> actions;
    private Inventory inventory;

    public HerbalistMenuHolder(
            UUID playerUuid,
            UUID herbalistUuid,
            HerbalistMenuType type,
            Map<Integer, String> actions
    ) {
        this.playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
        this.herbalistUuid = Objects.requireNonNull(herbalistUuid, "herbalistUuid");
        this.type = Objects.requireNonNull(type, "type");
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    public UUID playerUuid() {
        return playerUuid;
    }

    public UUID herbalistUuid() {
        return herbalistUuid;
    }

    public UUID sessionUuid() {
        return sessionUuid;
    }

    public HerbalistMenuType type() {
        return type;
    }

    public String action(int slot) {
        return actions.get(slot);
    }

    public void attach(Inventory inventory) {
        if (this.inventory != null) throw new IllegalStateException("Inventory already attached");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    @Override
    public @NotNull Inventory getInventory() {
        if (inventory == null) throw new IllegalStateException("Inventory is not attached");
        return inventory;
    }
}
