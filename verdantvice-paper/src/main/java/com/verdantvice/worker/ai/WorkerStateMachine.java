package com.verdantvice.worker.ai;

import com.verdantvice.worker.WorkerSchedule;
import com.verdantvice.worker.WorkerState;
import com.verdantvice.worker.WorkerTaskType;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic transition policy for every persisted worker state.
 */
public final class WorkerStateMachine {
    private static final Map<WorkerState, Set<WorkerState>> TRANSITIONS =
            transitions();

    public WorkerState transition(WorkerState current, WorkerState requested) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(requested, "requested");
        if (current == requested) {
            return current;
        }
        if (!TRANSITIONS.getOrDefault(current, Set.of()).contains(requested)) {
            throw new IllegalStateException(
                    "Illegal worker transition " + current + " -> " + requested
            );
        }
        return requested;
    }

    public WorkerState afterReservation(WorkerTaskType type) {
        return needsStorage(type)
                ? WorkerState.WALKING_TO_STORAGE
                : WorkerState.WALKING_TO_TARGET;
    }

    public WorkerState actionState(WorkerTaskType type) {
        return switch (type) {
            case INSPECT -> WorkerState.INSPECTING_PLANT;
            case WATER -> WorkerState.WATERING;
            case TREAT_DISEASE, SAVE_DYING_PLANTS, CLEAN -> WorkerState.TREATING;
            case PLANT -> WorkerState.PLANTING;
            case HARVEST -> WorkerState.HARVESTING;
            case MOVE_BATCH -> WorkerState.MOVING_BATCH;
            case LOAD_DRYING_RACK -> WorkerState.LOADING_DRYER;
            case UNLOAD_DRYING_RACK -> WorkerState.UNLOADING_DRYER;
            case PACKAGE -> WorkerState.PACKAGING;
        };
    }

    public boolean needsStorage(WorkerTaskType type) {
        return switch (type) {
            case INSPECT, HARVEST -> false;
            case WATER, TREAT_DISEASE, SAVE_DYING_PLANTS, PLANT, PACKAGE,
                    CLEAN, MOVE_BATCH, LOAD_DRYING_RACK, UNLOAD_DRYING_RACK -> true;
        };
    }

    public ScheduleDirective schedule(
            WorkerSchedule schedule,
            long worldTime,
            boolean hasActiveTask,
            boolean atHome
    ) {
        Objects.requireNonNull(schedule, "schedule");
        return switch (schedule.phase(worldTime)) {
            case WORK -> ScheduleDirective.WORK;
            case BREAK -> atHome
                    ? ScheduleDirective.REST
                    : ScheduleDirective.RETURN_HOME;
            case OFF_DUTY -> hasActiveTask
                    ? ScheduleDirective.FINISH_CURRENT
                    : atHome
                    ? ScheduleDirective.REST
                    : ScheduleDirective.RETURN_HOME;
            case SLEEP -> atHome
                    ? ScheduleDirective.SLEEP
                    : ScheduleDirective.RETURN_HOME;
        };
    }

    public boolean isAction(WorkerState state) {
        return switch (state) {
            case INSPECTING_PLANT, WATERING, TREATING, PLANTING, HARVESTING,
                    MOVING_BATCH, LOADING_DRYER, UNLOADING_DRYER, PACKAGING -> true;
            default -> false;
        };
    }

    private static Map<WorkerState, Set<WorkerState>> transitions() {
        EnumMap<WorkerState, Set<WorkerState>> values =
                new EnumMap<>(WorkerState.class);
        allow(values, WorkerState.IDLE,
                WorkerState.SEARCHING_TASK,
                WorkerState.RESTING,
                WorkerState.RETURNING_HOME,
                WorkerState.SLEEPING,
                WorkerState.WAITING_FOR_PAYMENT,
                WorkerState.BLOCKED);
        allow(values, WorkerState.RESTING,
                WorkerState.IDLE,
                WorkerState.SEARCHING_TASK,
                WorkerState.RETURNING_HOME,
                WorkerState.SLEEPING,
                WorkerState.WAITING_FOR_PAYMENT,
                WorkerState.BLOCKED);
        allow(values, WorkerState.SEARCHING_TASK,
                WorkerState.RESERVING_TASK,
                WorkerState.IDLE,
                WorkerState.RESTING,
                WorkerState.RETURNING_HOME,
                WorkerState.WAITING_FOR_PAYMENT,
                WorkerState.BLOCKED);
        allow(values, WorkerState.RESERVING_TASK,
                WorkerState.WALKING_TO_STORAGE,
                WorkerState.WALKING_TO_TARGET,
                WorkerState.SEARCHING_TASK,
                WorkerState.RETURNING_HOME,
                WorkerState.BLOCKED);
        allow(values, WorkerState.WALKING_TO_STORAGE,
                WorkerState.COLLECTING_RESOURCE,
                WorkerState.RETURNING_HOME,
                WorkerState.BLOCKED);
        allow(values, WorkerState.COLLECTING_RESOURCE,
                WorkerState.WALKING_TO_TARGET,
                WorkerState.RETURNING_HOME,
                WorkerState.BLOCKED);
        allow(values, WorkerState.WALKING_TO_TARGET,
                WorkerState.INSPECTING_PLANT,
                WorkerState.WATERING,
                WorkerState.TREATING,
                WorkerState.PLANTING,
                WorkerState.HARVESTING,
                WorkerState.MOVING_BATCH,
                WorkerState.LOADING_DRYER,
                WorkerState.UNLOADING_DRYER,
                WorkerState.PACKAGING,
                WorkerState.RETURNING_HOME,
                WorkerState.BLOCKED);
        for (WorkerState action : EnumSet.of(
                WorkerState.INSPECTING_PLANT,
                WorkerState.WATERING,
                WorkerState.TREATING,
                WorkerState.PLANTING,
                WorkerState.HARVESTING,
                WorkerState.MOVING_BATCH,
                WorkerState.LOADING_DRYER,
                WorkerState.UNLOADING_DRYER,
                WorkerState.PACKAGING)) {
            allow(values, action,
                    WorkerState.SEARCHING_TASK,
                    WorkerState.RETURNING_HOME,
                    WorkerState.BLOCKED,
                    WorkerState.WAITING_FOR_PAYMENT);
        }
        allow(values, WorkerState.RETURNING_HOME,
                WorkerState.IDLE,
                WorkerState.RESTING,
                WorkerState.SLEEPING,
                WorkerState.SEARCHING_TASK,
                WorkerState.WAITING_FOR_PAYMENT,
                WorkerState.BLOCKED);
        allow(values, WorkerState.SLEEPING,
                WorkerState.IDLE,
                WorkerState.RESTING,
                WorkerState.SEARCHING_TASK,
                WorkerState.RETURNING_HOME,
                WorkerState.WAITING_FOR_PAYMENT,
                WorkerState.BLOCKED);
        allow(values, WorkerState.WAITING_FOR_PAYMENT,
                WorkerState.IDLE,
                WorkerState.SEARCHING_TASK,
                WorkerState.RETURNING_HOME,
                WorkerState.SLEEPING,
                WorkerState.BLOCKED);
        allow(values, WorkerState.BLOCKED,
                WorkerState.IDLE,
                WorkerState.SEARCHING_TASK,
                WorkerState.RETURNING_HOME,
                WorkerState.WAITING_FOR_PAYMENT);
        return Map.copyOf(values);
    }

    private static void allow(
            EnumMap<WorkerState, Set<WorkerState>> values,
            WorkerState state,
            WorkerState... targets
    ) {
        values.put(state, Set.copyOf(EnumSet.of(targets[0], targets)));
    }

    public enum ScheduleDirective {
        WORK,
        FINISH_CURRENT,
        REST,
        RETURN_HOME,
        SLEEP
    }
}
