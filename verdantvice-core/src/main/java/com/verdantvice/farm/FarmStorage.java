package com.verdantvice.farm;

import com.verdantvice.common.BlockPosition;
import com.verdantvice.common.DomainChecks;

import java.time.Instant;
import java.util.UUID;

public record FarmStorage(
        UUID storageId,
        UUID farmId,
        BlockPosition position,
        StorageType type,
        boolean enabled,
        Instant createdAt
) {
    public FarmStorage {
        DomainChecks.required(storageId, "storageId");
        DomainChecks.required(farmId, "farmId");
        DomainChecks.required(position, "position");
        DomainChecks.required(type, "type");
        DomainChecks.required(createdAt, "createdAt");
    }
}
