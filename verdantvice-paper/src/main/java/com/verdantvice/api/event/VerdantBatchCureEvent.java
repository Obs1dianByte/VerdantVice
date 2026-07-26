package com.verdantvice.api.event;

import com.verdantvice.processing.CuringStage;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;
import java.util.UUID;

public final class VerdantBatchCureEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID batchId;
    private final CuringStage stage;

    public VerdantBatchCureEvent(UUID batchId, CuringStage stage) {
        this.batchId = Objects.requireNonNull(batchId, "batchId");
        this.stage = Objects.requireNonNull(stage, "stage");
    }

    public UUID batchId() {
        return batchId;
    }

    public CuringStage stage() {
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
