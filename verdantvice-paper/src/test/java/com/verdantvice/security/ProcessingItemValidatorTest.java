package com.verdantvice.security;

import com.verdantvice.database.item.ItemRegistryRecord;
import com.verdantvice.database.item.ItemRegistryState;
import com.verdantvice.processing.ProcessingFailureException;
import com.verdantvice.processing.ProcessingFailureReason;
import com.verdantvice.test.TestFixtures;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

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
    void ownerAndStateAreReportedSeparately() {
        SignedItemData data = data(TestFixtures.OWNER);
        assertReason(ProcessingFailureReason.WRONG_OWNER,
                () -> validator.validateData(data, Set.of(CustomItemType.FRESH_BATCH),
                        UUID.randomUUID()));
        assertReason(ProcessingFailureReason.WRONG_BATCH_STATE,
                () -> validator.validateData(data, Set.of(CustomItemType.DRY_BATCH),
                        TestFixtures.OWNER));
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
