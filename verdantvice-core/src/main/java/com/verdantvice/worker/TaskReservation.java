package com.verdantvice.worker;

import com.verdantvice.common.DomainChecks;

import java.time.Instant;
import java.util.UUID;

public record TaskReservation(UUID workerUuid, Instant reservedAt, Instant expiresAt) {
    public TaskReservation {
        DomainChecks.required(workerUuid, "workerUuid");
        DomainChecks.ordered(reservedAt, expiresAt, "task reservation");
        if (reservedAt.equals(expiresAt)) throw new IllegalArgumentException("Reservation must have a duration");
    }

    public boolean activeAt(Instant now) {
        DomainChecks.required(now, "now");
        return now.isBefore(expiresAt);
    }
}
