package com.verdantvice.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;
import java.util.UUID;

public final class VerdantProductUseEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final UUID itemInstanceId;
    private final UUID batchId;
    private boolean cancelled;

    public VerdantProductUseEvent(Player player, UUID itemInstanceId, UUID batchId) {
        this.player = Objects.requireNonNull(player, "player");
        this.itemInstanceId = Objects.requireNonNull(itemInstanceId, "itemInstanceId");
        this.batchId = Objects.requireNonNull(batchId, "batchId");
    }

    public Player player() {
        return player;
    }

    public UUID itemInstanceId() {
        return itemInstanceId;
    }

    public UUID batchId() {
        return batchId;
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
