package com.verdantvice.api.event;

import org.bukkit.entity.Villager;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;

public final class VerdantHerbalistCreatedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Villager herbalist;

    public VerdantHerbalistCreatedEvent(Villager herbalist) {
        this.herbalist = Objects.requireNonNull(herbalist, "herbalist");
    }

    public Villager herbalist() {
        return herbalist;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
