package com.verdantvice.herbalist;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record HerbalistRecord(
        UUID herbalistUuid,
        UUID entityUuid,
        UUID ownerUuid,
        UUID worldUuid,
        int blockX,
        int blockY,
        int blockZ,
        HerbalistTier tier,
        long experience,
        int reputation,
        String offersJson,
        Instant createdAt,
        Instant updatedAt,
        long version
) {
    public HerbalistRecord {
        Objects.requireNonNull(herbalistUuid, "herbalistUuid");
        Objects.requireNonNull(entityUuid, "entityUuid");
        Objects.requireNonNull(worldUuid, "worldUuid");
        Objects.requireNonNull(tier, "tier");
        if (experience < 0L || reputation < 0 || version < 0L) {
            throw new IllegalArgumentException("Herbalist counters cannot be negative");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
