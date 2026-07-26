package com.verdantvice.api.event;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;
import java.util.UUID;

public final class VerdantPlantPlacedEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final UUID plantId;
    private final Location location;
    private final String strainId;
    private boolean cancelled;

    public VerdantPlantPlacedEvent(Player player, UUID plantId, Location location, String strainId) {
        this.player = Objects.requireNonNull(player, "player");
        this.plantId = Objects.requireNonNull(plantId, "plantId");
        this.location = Objects.requireNonNull(location, "location").clone();
        this.strainId = Objects.requireNonNull(strainId, "strainId");
    }

    public Player player() {
        return player;
    }

    public UUID plantId() {
        return plantId;
    }

    public Location location() {
        return location.clone();
    }

    public String strainId() {
        return strainId;
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
