package com.verdantvice.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;
import java.util.UUID;

public final class VerdantContractCompleteEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID contractId;
    private final UUID ownerUuid;

    public VerdantContractCompleteEvent(UUID contractId, UUID ownerUuid) {
        this.contractId = Objects.requireNonNull(contractId, "contractId");
        this.ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
    }

    public UUID contractId() {
        return contractId;
    }

    public UUID ownerUuid() {
        return ownerUuid;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
