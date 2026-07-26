package com.verdantvice.security;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SignedItemData(
        CustomItemType itemType,
        String itemId,
        UUID uniqueInstanceId,
        UUID batchId,
        UUID ownerUuid,
        double quality,
        String strain,
        Instant createdAt,
        int schemaVersion
) {
    public SignedItemData {
        Objects.requireNonNull(itemType, "itemType");
        itemId = requireNonBlank(itemId, "itemId");
        Objects.requireNonNull(uniqueInstanceId, "uniqueInstanceId");
        strain = strain == null ? "" : strain;
        Objects.requireNonNull(createdAt, "createdAt");
        if (!Double.isFinite(quality) || quality < 0.0 || quality > 100.0) {
            throw new IllegalArgumentException("quality must be within 0..100");
        }
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
    }

    public static SignedItemData create(
            CustomItemType type,
            String itemId,
            UUID ownerUuid,
            String strain
    ) {
        return new SignedItemData(
                type,
                itemId,
                UUID.randomUUID(),
                null,
                ownerUuid,
                0.0,
                strain,
                Instant.now(),
                1
        );
    }

    private static String requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        return value;
    }
}
