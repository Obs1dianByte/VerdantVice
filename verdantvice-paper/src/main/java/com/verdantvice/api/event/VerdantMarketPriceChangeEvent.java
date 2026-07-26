package com.verdantvice.api.event;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;

public final class VerdantMarketPriceChangeEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final String strainId;
    private final double previousPrice;
    private final double newPrice;
    private boolean cancelled;

    public VerdantMarketPriceChangeEvent(String strainId, double previousPrice, double newPrice) {
        this.strainId = Objects.requireNonNull(strainId, "strainId");
        this.previousPrice = previousPrice;
        this.newPrice = newPrice;
    }

    public String strainId() {
        return strainId;
    }

    public double previousPrice() {
        return previousPrice;
    }

    public double newPrice() {
        return newPrice;
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
