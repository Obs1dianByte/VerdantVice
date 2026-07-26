package com.verdantvice.worker;

import com.verdantvice.common.BlockPosition;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Paper persistence envelope around the platform-neutral worker aggregate.
 */
public record WorkerRecord(
        Worker worker,
        UUID entityUuid,
        String name,
        WorkerSchedule schedule,
        Optional<BlockPosition> home,
        Optional<BlockPosition> lastKnownPosition,
        Instant createdAt,
        Instant updatedAt,
        long version
) {
    public WorkerRecord {
        Objects.requireNonNull(worker, "worker");
        Objects.requireNonNull(entityUuid, "entityUuid");
        name = Objects.requireNonNull(name, "name").strip();
        if (name.isEmpty() || name.length() > 64) {
            throw new IllegalArgumentException("Worker name must contain 1 to 64 characters");
        }
        Objects.requireNonNull(schedule, "schedule");
        home = Objects.requireNonNull(home, "home");
        lastKnownPosition = Objects.requireNonNull(lastKnownPosition, "lastKnownPosition");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt) || version < 0L) {
            throw new IllegalArgumentException("Invalid worker persistence metadata");
        }
    }

    public WorkerRecord withWorker(Worker updated, Instant now) {
        return new WorkerRecord(
                updated,
                entityUuid,
                name,
                schedule,
                home,
                lastKnownPosition,
                createdAt,
                now,
                version
        );
    }

    public WorkerRecord withPosition(BlockPosition position, Instant now) {
        return new WorkerRecord(
                worker,
                entityUuid,
                name,
                schedule,
                home,
                Optional.of(position),
                createdAt,
                now,
                version
        );
    }

    public WorkerRecord persisted(Instant now) {
        return new WorkerRecord(
                worker,
                entityUuid,
                name,
                schedule,
                home,
                lastKnownPosition,
                createdAt,
                now,
                version + 1L
        );
    }
}
