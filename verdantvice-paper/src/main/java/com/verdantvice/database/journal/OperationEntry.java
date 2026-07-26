package com.verdantvice.database.journal;

import java.time.Instant;
import java.util.UUID;

public record OperationEntry(
        OperationKey key,
        OperationState state,
        UUID claimToken,
        Instant leaseUntil,
        String resultJson,
        String errorCode,
        int attemptCount,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        long version
) {
}
