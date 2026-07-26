package com.verdantvice.tolerance;

import com.verdantvice.common.DomainChecks;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record ToleranceState(
        UUID playerUuid,
        double level,
        Optional<Instant> lastUsedAt,
        Instant lastUpdatedAt
) {
    public ToleranceState {
        DomainChecks.required(playerUuid, "playerUuid");
        level = DomainChecks.range(level, 0.0, 100.0, "level");
        lastUsedAt = DomainChecks.required(lastUsedAt, "lastUsedAt");
        DomainChecks.required(lastUpdatedAt, "lastUpdatedAt");
        lastUsedAt.ifPresent(last -> {
            if (last.isAfter(lastUpdatedAt)) {
                throw new IllegalArgumentException("lastUsedAt cannot follow lastUpdatedAt");
            }
        });
    }

    public static ToleranceState initial(UUID playerUuid, Instant now) {
        return new ToleranceState(playerUuid, 0.0, Optional.empty(), now);
    }
}
