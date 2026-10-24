package com.verdantvice.security;

import com.verdantvice.database.item.ItemRegistryRecord;
import com.verdantvice.database.item.ItemRegistryState;
import com.verdantvice.processing.ProcessingFailureException;
import com.verdantvice.processing.ProcessingFailureReason;
import com.verdantvice.test.TestFixtures;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ProcessingItemValidatorTest {
    private static final String HASH = "a".repeat(64);
    private final ProcessingItemValidator validator = new ProcessingItemValidator();

    @Test
    void signatureMismatchIsInvalidSignature() {
        SignedItemData data = data(TestFixtures.OWNER);
        ItemRegistryRecord record = record(data, "b".repeat(64), ItemRegistryState.ACTIVE,
                TestFixtures.OWNER);
        assertReason(ProcessingFailureReason.INVALID_SIGNATURE,
                () -> validator.validateRecord(record, data, HASH, TestFixtures.OWNER));
    }

    @Test
    void consumedItemIsReportedSeparately() {
        SignedItemData data = data(TestFixtures.OWNER);
        ItemRegistryRecord record = record(data, HASH, ItemRegistryState.CONSUMED,
                TestFixtures.OWNER);
        assertReason(ProcessingFailureReason.ITEM_ALREADY_USED,
                () -> validator.validateRecord(record, data, HASH, TestFixtures.OWNER));
    }

    @Test
    void liveReservationCannotBeTakenByAnotherOperation() {
        SignedItemData data = data(TestFixtures.OWNER);
        ProcessingItemValidator timed = new ProcessingItemValidator(
                Clock.fixed(TestFixtures.NOW, ZoneOffset.UTC));
        ItemRegistryRecord reserved = reserved(data, TestFixtures.NOW.plusSeconds(1), TestFixtures.OWNER, HASH);
        assertReason(ProcessingFailureReason.ITEM_ALREADY_USED,
                () -> timed.validateRecord(reserved, data, HASH, TestFixtures.OWNER));
    }

    @Test
    void expiredLegacyReservationCanBeReclaimedWithoutBypassingAuthenticityOrOwnership() {
        SignedItemData data = data(TestFixtures.OWNER);
        ProcessingItemValidator timed = new ProcessingItemValidator(
                Clock.fixed(TestFixtures.NOW, ZoneOffset.UTC));
        assertDoesNotThrow(() -> timed.validateRecord(
                reserved(data, TestFixtures.NOW, TestFixtures.OWNER, HASH), data, HASH, TestFixtures.OWNER));
        assertReason(ProcessingFailureReason.WRONG_OWNER, () -> timed.validateRecord(
                reserved(data, TestFixtures.NOW, UUID.randomUUID(), HASH), data, HASH, TestFixtures.OWNER));
        assertReason(ProcessingFailureReason.INVALID_SIGNATURE, () -> timed.validateRecord(
                reserved(data, TestFixtures.NOW, TestFixtures.OWNER, "b".repeat(64)), data, HASH, TestFixtures.OWNER));
    }

    private static ItemRegistryRecord reserved(SignedItemData data, Instant until, UUID owner, String hash) {
        return new ItemRegistryRecord(data.uniqueInstanceId(), data.itemType().name(), data.itemId(),
                data.batchId(), owner, data.quality(), data.strain(), data.createdAt(), TestFixtures.NOW,
                hash, data.schemaVersion(), ItemRegistryState.RESERVED, UUID.randomUUID(), owner.toString(),
                until, null, null, "legacy-reservation", null, 1L);
    }

    @Test
    void rollingPaperAndFilterDoNotRequireBatchId() {
        SignedItemData paper = nonBatchData(CustomItemType.ROLLING_PAPER);
        SignedItemData filter = nonBatchData(CustomItemType.FILTER);

        assertDoesNotThrow(() -> validator.validateData(
                paper, Set.of(CustomItemType.ROLLING_PAPER), TestFixtures.OWNER));
        assertDoesNotThrow(() -> validator.validateData(
                filter, Set.of(CustomItemType.FILTER), TestFixtures.OWNER));
    }

    @Test
    void batchBoundProductStillRequiresBatchId() {
        SignedItemData product = nonBatchData(CustomItemType.GROUND_PRODUCT);
        assertReason(ProcessingFailureReason.WRONG_BATCH_STATE,
                () -> validator.validateData(product, Set.of(CustomItemType.GROUND_PRODUCT),
                        TestFixtures.OWNER));
    }

    @Test
    void ownerAndStateAreReportedSeparately() {
        SignedItemData data = data(TestFixtures.OWNER);
        assertReason(ProcessingFailureReason.WRONG_OWNER,
                () -> validator.validateData(data, Set.of(CustomItemType.FRESH_BATCH),
                        UUID.randomUUID()));
        assertReason(ProcessingFailureReason.WRONG_BATCH_STATE,
                () -> validator.validateData(data, Set.of(CustomItemType.DRY_BATCH),
                        TestFixtures.OWNER));
    }

    private static SignedItemData nonBatchData(CustomItemType type) {
        return new SignedItemData(
                type, "verdantvice:" + type.name().toLowerCase(java.util.Locale.ROOT),
                UUID.randomUUID(), null, TestFixtures.OWNER, 0.0, "",
                TestFixtures.NOW, 1);
    }

    private static SignedItemData data(UUID owner) {
        return new SignedItemData(
                CustomItemType.FRESH_BATCH, "verdantvice:fresh_batch", UUID.randomUUID(),
                TestFixtures.freshBatch().batchId(), owner, 80.0, "berry_fog",
                TestFixtures.NOW, 1);
    }

    private static ItemRegistryRecord record(
            SignedItemData data,
            String signature,
            ItemRegistryState state,
            UUID owner
    ) {
        Instant now = TestFixtures.NOW;
        return new ItemRegistryRecord(
                data.uniqueInstanceId(), data.itemType().name(), data.itemId(), data.batchId(),
                owner, data.quality(), data.strain(), data.createdAt(), now, signature,
                data.schemaVersion(), state, null, null, null,
                state == ItemRegistryState.CONSUMED ? now : null,
                state == ItemRegistryState.CONSUMED ? "dry:test" : null,
                null, null, 0L);
    }

    private static void assertReason(ProcessingFailureReason expected, Runnable action) {
        ProcessingFailureException failure = assertThrows(
                ProcessingFailureException.class, action::run);
        assertEquals(expected, failure.reason());
    }
}
