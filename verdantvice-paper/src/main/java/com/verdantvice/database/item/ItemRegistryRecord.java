package com.verdantvice.database.item;

import java.time.Instant;
import java.util.UUID;

public record ItemRegistryRecord(
        UUID instanceId,
        String itemType,
        String itemId,
        UUID batchId,
        UUID ownerUuid,
        Double quality,
        String strainId,
        Instant createdAt,
        Instant updatedAt,
        String signature,
        int schemaVersion,
        ItemRegistryState state,
        UUID reservationToken,
        String reservedBy,
        Instant reservedUntil,
        Instant consumedAt,
        String consumedByOperation,
        String lastOperationId,
        String metadataJson,
        long version
) {
}
