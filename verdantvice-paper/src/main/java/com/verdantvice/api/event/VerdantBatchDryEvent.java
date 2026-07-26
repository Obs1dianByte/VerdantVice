package com.verdantvice.api.event;

import com.verdantvice.processing.DryingStage;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;
import java.util.UUID;

public final class VerdantBatchDryEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID batchId;
    private final DryingStage stage;

    public VerdantBatchDryEvent(UUID batchId, DryingStage stage) {
        this.batchId = Objects.requireNonNull(batchId, "batchId");
        this.stage = Objects.requireNonNull(stage, "stage");
    }

    public UUID batchId() {
        return batchId;
    }

    public DryingStage stage() {
        return stage;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
