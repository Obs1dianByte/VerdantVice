package com.verdantvice.security;

import com.verdantvice.database.item.ItemRegistryRecord;
import com.verdantvice.database.item.ItemRegistryState;
import com.verdantvice.processing.ProcessingFailureException;
import com.verdantvice.processing.ProcessingFailureReason;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Validates a signed item reference against its durable registry identity. */
public final class ProcessingItemValidator {
    private final Clock clock;

    public ProcessingItemValidator() {
        this(Clock.systemUTC());
    }

    ProcessingItemValidator(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void validateData(
            SignedItemData data,
            Set<CustomItemType> accepted,
            UUID actor
    ) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(actor, "actor");
        if (!accepted.contains(data.itemType())) {
            throw new ProcessingFailureException(ProcessingFailureReason.WRONG_BATCH_STATE);
        }
        if (data.itemType().batchBound() && data.batchId() == null) {
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
        boolean expiredReservation = record.state() == ItemRegistryState.RESERVED
                && (record.reservedUntil() == null
                || !record.reservedUntil().isAfter(Instant.now(clock)));
        if (record.state() != ItemRegistryState.ACTIVE && !expiredReservation) {
            throw new ProcessingFailureException(ProcessingFailureReason.ITEM_ALREADY_USED);
        }
        validateIdentity(record, data, payloadHash, actor);
    }

    public void validateIdentity(
            ItemRegistryRecord record, SignedItemData data, String payloadHash, UUID actor
    ) {
        if (!constantEquals(record.signature(), payloadHash)
                || !record.itemType().equals(data.itemType().name())
                || !record.itemId().equals(data.itemId())
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
