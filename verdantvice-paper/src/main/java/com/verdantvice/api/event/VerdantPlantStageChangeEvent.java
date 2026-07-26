package com.verdantvice.api.event;

import com.verdantvice.plant.GrowthStage;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;
import java.util.UUID;

public final class VerdantPlantStageChangeEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID plantId;
    private final GrowthStage previousStage;
    private final GrowthStage newStage;
    private boolean cancelled;

    public VerdantPlantStageChangeEvent(
            UUID plantId,
            GrowthStage previousStage,
            GrowthStage newStage
    ) {
        this.plantId = Objects.requireNonNull(plantId, "plantId");
        this.previousStage = Objects.requireNonNull(previousStage, "previousStage");
        this.newStage = Objects.requireNonNull(newStage, "newStage");
    }

    public UUID plantId() {
        return plantId;
    }

    public GrowthStage previousStage() {
        return previousStage;
    }

    public GrowthStage newStage() {
        return newStage;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
