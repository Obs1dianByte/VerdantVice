package com.verdantvice.worker;

import com.verdantvice.common.DomainChecks;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record WorkerTask(
        UUID taskId,
        WorkerTaskType type,
        int priority,
        TaskTarget target,
        Optional<TaskReservation> reservation,
        Instant timeoutAt,
        int attempts,
        int maximumAttempts,
        WorkerTaskStatus status,
        Optional<UUID> workerUuid,
        Instant createdAt
) {
    public WorkerTask {
        DomainChecks.required(taskId, "taskId");
        DomainChecks.required(type, "type");
        priority = DomainChecks.range(priority, 0, 100, "priority");
        DomainChecks.required(target, "target");
        reservation = DomainChecks.required(reservation, "reservation");
        DomainChecks.required(timeoutAt, "timeoutAt");
        attempts = DomainChecks.range(attempts, 0, 1_000, "attempts");
        maximumAttempts = DomainChecks.range(maximumAttempts, 1, 1_000, "maximumAttempts");
        if (attempts > maximumAttempts) throw new IllegalArgumentException("attempts exceeds maximumAttempts");
        DomainChecks.required(status, "status");
        workerUuid = DomainChecks.required(workerUuid, "workerUuid");
        DomainChecks.required(createdAt, "createdAt");
        if (!timeoutAt.isAfter(createdAt)) throw new IllegalArgumentException("timeoutAt must follow creation");
        if ((status == WorkerTaskStatus.RESERVED || status == WorkerTaskStatus.IN_PROGRESS)
                && (reservation.isEmpty() || workerUuid.isEmpty())) {
            throw new IllegalArgumentException("Reserved task requires reservation and worker");
        }
        if (reservation.isPresent() && workerUuid.isPresent()
                && !reservation.get().workerUuid().equals(workerUuid.get())) {
            throw new IllegalArgumentException("Reservation and worker must match");
        }
    }

    public static WorkerTask queued(UUID id, WorkerTaskType type, int priority, TaskTarget target,
                                    Instant createdAt, Instant timeoutAt, int maximumAttempts) {
        return new WorkerTask(id, type, priority, target, Optional.empty(), timeoutAt, 0,
                maximumAttempts, WorkerTaskStatus.QUEUED, Optional.empty(), createdAt);
    }
}
