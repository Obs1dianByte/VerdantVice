package com.verdantvice.api.event;

import com.verdantvice.harvest.HarvestTiming;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;
import java.util.UUID;

public final class VerdantPlantHarvestEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final UUID plantId;
    private final UUID batchId;
    private final HarvestTiming timing;
    private boolean cancelled;

    public VerdantPlantHarvestEvent(
            Player player,
            UUID plantId,
            UUID batchId,
            HarvestTiming timing
    ) {
        this.player = Objects.requireNonNull(player, "player");
        this.plantId = Objects.requireNonNull(plantId, "plantId");
        this.batchId = Objects.requireNonNull(batchId, "batchId");
        this.timing = Objects.requireNonNull(timing, "timing");
    }

    public Player player() {
        return player;
    }

    public UUID plantId() {
        return plantId;
    }

    public UUID batchId() {
        return batchId;
    }

    public HarvestTiming timing() {
        return timing;
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
