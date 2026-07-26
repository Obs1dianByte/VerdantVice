package com.verdantvice.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;
import java.util.UUID;

public final class VerdantPlantDiseaseEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID plantId;
    private final String diseaseId;
    private final double severity;

    public VerdantPlantDiseaseEvent(UUID plantId, String diseaseId, double severity) {
        this.plantId = Objects.requireNonNull(plantId, "plantId");
        this.diseaseId = Objects.requireNonNull(diseaseId, "diseaseId");
        this.severity = severity;
    }

    public UUID plantId() {
        return plantId;
    }

    public String diseaseId() {
        return diseaseId;
    }

    public double severity() {
        return severity;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
