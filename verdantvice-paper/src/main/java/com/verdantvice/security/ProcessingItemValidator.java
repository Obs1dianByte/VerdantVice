package com.verdantvice.security;

import com.verdantvice.database.item.ItemRegistryRecord;
import com.verdantvice.database.item.ItemRegistryState;
import com.verdantvice.processing.ProcessingFailureException;
import com.verdantvice.processing.ProcessingFailureReason;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Validates a signed item reference against its durable registry identity. */
public final class ProcessingItemValidator {
    public void validateData(
            SignedItemData data,
            Set<CustomItemType> accepted,
            UUID actor
    ) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(actor, "actor");
        if (!accepted.contains(data.itemType()) || data.batchId() == null) {
            throw new ProcessingFailureException(ProcessingFailureReason.WRONG_BATCH_STATE);
        }
        if (!actor.equals(data.ownerUuid())) {
            throw new ProcessingFailureException(ProcessingFailureReason.WRONG_OWNER);
        }
    }

    public void validateRecord(
            ItemRegistryRecord record,
            SignedItemData data,
            String payloadHash,
            UUID actor
    ) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(payloadHash, "payloadHash");
        Objects.requireNonNull(actor, "actor");
        if (record.state() != ItemRegistryState.ACTIVE) {
            throw new ProcessingFailureException(ProcessingFailureReason.ITEM_ALREADY_USED);
        }
        if (!constantEquals(record.signature(), payloadHash)
                || !record.itemType().equals(data.itemType().name())
                || !Objects.equals(record.batchId(), data.batchId())) {
            throw new ProcessingFailureException(ProcessingFailureReason.INVALID_SIGNATURE);
        }
        if (!actor.equals(record.ownerUuid()) || !actor.equals(data.ownerUuid())) {
            throw new ProcessingFailureException(ProcessingFailureReason.WRONG_OWNER);
        }
    }

    private static boolean constantEquals(String left, String right) {
        if (left == null || right == null) return false;
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }
}
