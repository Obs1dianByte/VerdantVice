package com.verdantvice.worker;

import com.verdantvice.common.DomainChecks;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

/**
 * Reservation operations are immutable; persistence should commit the returned value atomically.
 */
public final class WorkerTaskService {
    public WorkerTask reserve(WorkerTask task, UUID workerUuid, Instant now, Duration reservationDuration) {
        DomainChecks.required(task, "task");
        DomainChecks.required(workerUuid, "workerUuid");
        DomainChecks.required(now, "now");
        DomainChecks.positive(reservationDuration, "reservationDuration");
        ensureNotTimedOut(task, now);
        if (terminal(task.status())) throw new IllegalStateException("Terminal task cannot be reserved");
        if (task.reservation().filter(reservation -> reservation.activeAt(now)
                && !reservation.workerUuid().equals(workerUuid)).isPresent()) {
            throw new IllegalStateException("Task is reserved by another worker");
        }
        TaskReservation reservation = new TaskReservation(workerUuid, now, now.plus(reservationDuration));
        return copy(task, Optional.of(reservation), task.attempts(), WorkerTaskStatus.RESERVED,
                Optional.of(workerUuid));
    }

    public WorkerTask start(WorkerTask task, UUID workerUuid, Instant now) {
        verifyReservation(task, workerUuid, now);
        return copy(task, task.reservation(), task.attempts(), WorkerTaskStatus.IN_PROGRESS,
                Optional.of(workerUuid));
    }

    public WorkerTask complete(WorkerTask task, UUID workerUuid, Instant now) {
        verifyReservation(task, workerUuid, now);
        if (task.status() != WorkerTaskStatus.IN_PROGRESS) {
            throw new IllegalStateException("Only an in-progress task can complete");
        }
        return copy(task, Optional.empty(), task.attempts(), WorkerTaskStatus.COMPLETED,
                Optional.of(workerUuid));
    }

    public WorkerTask failAttempt(WorkerTask task, UUID workerUuid, Instant now) {
        verifyReservation(task, workerUuid, now);
        int attempts = task.attempts() + 1;
        WorkerTaskStatus status = attempts >= task.maximumAttempts()
                ? WorkerTaskStatus.BLOCKED : WorkerTaskStatus.QUEUED;
        return copy(task, Optional.empty(), attempts, status,
                status == WorkerTaskStatus.BLOCKED ? Optional.of(workerUuid) : Optional.empty());
    }

    public WorkerTask releaseExpired(WorkerTask task, Instant now) {
        DomainChecks.required(task, "task");
        DomainChecks.required(now, "now");
        if (task.reservation().isEmpty() || task.reservation().get().activeAt(now)
                || task.status() == WorkerTaskStatus.IN_PROGRESS) {
            return task;
        }
        return copy(task, Optional.empty(), task.attempts(), WorkerTaskStatus.QUEUED, Optional.empty());
    }

    public Optional<WorkerTask> selectNext(Collection<WorkerTask> tasks, Instant now) {
        DomainChecks.required(tasks, "tasks");
        DomainChecks.required(now, "now");
        return tasks.stream()
                .map(task -> releaseExpired(task, now))
                .filter(task -> task.status() == WorkerTaskStatus.QUEUED)
                .filter(task -> now.isBefore(task.timeoutAt()))
                .sorted(Comparator.comparingInt(WorkerTask::priority).reversed()
                        .thenComparing(WorkerTask::createdAt)
                        .thenComparing(task -> task.taskId().toString()))
                .findFirst();
    }

    private static void verifyReservation(WorkerTask task, UUID workerUuid, Instant now) {
        DomainChecks.required(task, "task");
        DomainChecks.required(workerUuid, "workerUuid");
        DomainChecks.required(now, "now");
        ensureNotTimedOut(task, now);
        TaskReservation reservation = task.reservation()
                .orElseThrow(() -> new IllegalStateException("Task is not reserved"));
        if (!reservation.workerUuid().equals(workerUuid)) {
            throw new IllegalStateException("Task belongs to another worker");
        }
        if (!reservation.activeAt(now)) throw new IllegalStateException("Task reservation expired");
    }

    private static void ensureNotTimedOut(WorkerTask task, Instant now) {
        if (!now.isBefore(task.timeoutAt())) throw new IllegalStateException("Task timed out");
    }

    private static boolean terminal(WorkerTaskStatus status) {
        return status == WorkerTaskStatus.COMPLETED || status == WorkerTaskStatus.FAILED
                || status == WorkerTaskStatus.BLOCKED || status == WorkerTaskStatus.CANCELLED;
    }

    private static WorkerTask copy(WorkerTask task, Optional<TaskReservation> reservation, int attempts,
                                   WorkerTaskStatus status, Optional<UUID> workerUuid) {
        return new WorkerTask(task.taskId(), task.type(), task.priority(), task.target(), reservation,
                task.timeoutAt(), attempts, task.maximumAttempts(), status, workerUuid, task.createdAt());
    }
}
