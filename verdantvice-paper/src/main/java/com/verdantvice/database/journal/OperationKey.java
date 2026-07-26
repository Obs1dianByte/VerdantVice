package com.verdantvice.database.journal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

public record OperationKey(
        String operationId,
        String operationType,
        UUID actorUuid,
        String scopeKey,
        String requestHash
) {
    public OperationKey {
        operationId = requireLength(operationId, "operationId", 128);
        operationType = requireLength(operationType, "operationType", 64);
        if (scopeKey != null) {
            scopeKey = requireLength(scopeKey, "scopeKey", 191);
        }
        requestHash = Objects.requireNonNull(requestHash, "requestHash").toLowerCase(java.util.Locale.ROOT);
        if (!requestHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("requestHash must be a 64-character SHA-256 hex digest");
        }
    }

    public static OperationKey forPayload(
            String operationId,
            String operationType,
            UUID actorUuid,
            String scopeKey,
            String canonicalPayload
    ) {
        Objects.requireNonNull(canonicalPayload, "canonicalPayload");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return new OperationKey(
                    operationId,
                    operationType,
                    actorUuid,
                    scopeKey,
                    HexFormat.of().formatHex(digest.digest(canonicalPayload.getBytes(StandardCharsets.UTF_8)))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String requireLength(String value, String name, int maximum) {
        Objects.requireNonNull(value, name);
        String result = value.strip();
        if (result.isEmpty() || result.length() > maximum || result.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " must contain 1 to " + maximum + " safe characters");
        }
        return result;
    }
}
