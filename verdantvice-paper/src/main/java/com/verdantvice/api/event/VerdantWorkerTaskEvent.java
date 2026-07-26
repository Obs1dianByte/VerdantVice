package com.verdantvice.api.event;

import com.verdantvice.worker.WorkerState;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;
import java.util.UUID;

public final class VerdantWorkerTaskEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID workerId;
    private final UUID taskId;
    private final WorkerState state;

    public VerdantWorkerTaskEvent(UUID workerId, UUID taskId, WorkerState state) {
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.state = Objects.requireNonNull(state, "state");
    }

    public UUID workerId() {
        return workerId;
    }

    public UUID taskId() {
        return taskId;
    }

    public WorkerState state() {
        return state;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
