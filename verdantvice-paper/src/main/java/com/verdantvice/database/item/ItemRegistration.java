package com.verdantvice.database.item;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record ItemRegistration(
        UUID instanceId,
        String itemType,
        String itemId,
        UUID batchId,
        UUID ownerUuid,
        Double quality,
        String strainId,
        Instant createdAt,
        String signature,
        int schemaVersion,
        String metadataJson
) {
    public ItemRegistration {
        Objects.requireNonNull(instanceId, "instanceId");
        itemType = requireLength(itemType, "itemType", 64);
        itemId = requireLength(itemId, "itemId", 128);
        if (quality != null && (!Double.isFinite(quality) || quality < 0.0D || quality > 100.0D)) {
            throw new IllegalArgumentException("quality must be finite and between 0 and 100");
        }
        if (strainId != null) {
            strainId = requireLength(strainId, "strainId", 64);
        }
        Objects.requireNonNull(createdAt, "createdAt");
        signature = Objects.requireNonNull(signature, "signature").toLowerCase(Locale.ROOT);
        if (!signature.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("signature must be a 64-character SHA-256 HMAC hex digest");
        }
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        if (metadataJson != null && metadataJson.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("metadataJson must not contain NUL characters");
        }
    }

    private static String requireLength(String value, String name, int maximum) {
        Objects.requireNonNull(value, name);
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maximum || normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " must contain 1 to " + maximum + " safe characters");
        }
        return normalized;
    }
}
