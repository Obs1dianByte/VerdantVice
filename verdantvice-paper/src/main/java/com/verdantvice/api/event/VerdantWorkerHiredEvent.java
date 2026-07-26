package com.verdantvice.api.event;

import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;
import java.util.UUID;

public final class VerdantWorkerHiredEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player owner;
    private final Villager worker;
    private final UUID farmId;
    private boolean cancelled;

    public VerdantWorkerHiredEvent(Player owner, Villager worker, UUID farmId) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.farmId = Objects.requireNonNull(farmId, "farmId");
    }

    public Player owner() {
        return owner;
    }

    public Villager worker() {
        return worker;
    }

    public UUID farmId() {
        return farmId;
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
