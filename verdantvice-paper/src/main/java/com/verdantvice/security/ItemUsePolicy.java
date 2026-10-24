package com.verdantvice.security;

import com.verdantvice.database.SqlSession;
import com.verdantvice.database.DatabaseType;
import com.verdantvice.database.item.ItemRegistration;
import com.verdantvice.database.item.ItemRegistryRecord;
import com.verdantvice.database.item.ItemRegistryRepository;
import com.verdantvice.database.item.ItemRegistryState;
import com.verdantvice.processing.ProcessingFailureException;
import com.verdantvice.processing.ProcessingFailureReason;

import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Optional duplicate policing, separate from durable transaction bookkeeping. */
public final class ItemUsePolicy {
    private final BooleanSupplier enabled;
    private final ProcessingItemValidator validator = new ProcessingItemValidator();

    public ItemUsePolicy(boolean enabled) {
        this(() -> enabled);
    }

    public ItemUsePolicy(BooleanSupplier enabled) {
        this.enabled = Objects.requireNonNull(enabled, "enabled");
    }

    public boolean antiDupeEnabled() {
        return enabled.getAsBoolean();
    }

    public void validateRecord(ItemRegistryRecord record, SignedItemData data,
                               String payloadHash, UUID actor) {
        validator.validateData(data, Set.of(data.itemType()), actor);
        Objects.requireNonNull(payloadHash, "payloadHash");
        boolean enforce = antiDupeEnabled();
        if (record == null) {
            if (enforce) {
                throw new ProcessingFailureException(ProcessingFailureReason.UNREGISTERED_ITEM);
            }
            return;
        }
        if (enforce) {
            validator.validateRecord(record, data, payloadHash, actor);
        } else {
            validator.validateIdentity(record, data, payloadHash, actor);
        }
    }

    /** Called inside the caller's transaction, after authenticating the physical input. */
    public ItemRegistryRecord prepareConsumption(
            SqlSession session, ItemRegistryRepository registry, SignedItemData data,
            String payloadHash, UUID actor, Instant now
    ) throws SQLException {
        validator.validateData(data, Set.of(data.itemType()), actor);
        boolean enforce = antiDupeEnabled();
        ItemRegistryRecord record = registry.find(session, data.uniqueInstanceId(), true).orElse(null);
        if (record == null && !enforce) {
            record = registry.register(session, new ItemRegistration(
                    data.uniqueInstanceId(), data.itemType().name(), data.itemId(), data.batchId(),
                    data.ownerUuid(), data.quality(), data.strain().isBlank() ? null : data.strain(),
                    data.createdAt(), payloadHash, data.schemaVersion(), null)).record();
        }
        if (record == null) {
            throw new ProcessingFailureException(ProcessingFailureReason.UNREGISTERED_ITEM);
        }
        validator.validateIdentity(record, data, payloadHash, actor);
        if (!enforce && record.state() == ItemRegistryState.CONSUMED) {
            // A live rolling/refund/delivery receipt must not be overwritten by another operation.
            if (record.metadataJson() != null) {
                throw new ProcessingFailureException(ProcessingFailureReason.ITEM_ALREADY_USED);
            }
            archiveConsumption(session, record, now);
            if (session.update("""
                    UPDATE item_registry SET registry_state='ACTIVE', consumed_at=NULL,
                      consumed_by_operation=NULL, reservation_token=NULL, reserved_by=NULL,
                      reserved_until=NULL, updated_at=?, row_version=row_version+1
                    WHERE instance_id=? AND row_version=? AND registry_state='CONSUMED'
                      AND metadata_json IS NULL
                    """, now, record.instanceId(), record.version()) != 1) {
                throw new SQLException("Item changed while preparing physical input");
            }
            record = registry.find(session, data.uniqueInstanceId(), true).orElseThrow();
        }
        validator.validateRecord(record, data, payloadHash, actor);
        return record;
    }

    /** Historical receipts remain valid even after another physical copy is consumed. */
    public boolean consumptionRecorded(SqlSession session, ItemRegistryRepository registry,
                                       UUID instanceId, String operationId) throws SQLException {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(operationId, "operationId");
        ItemRegistryRecord current = registry.find(session, instanceId, false).orElse(null);
        if (current != null && operationId.equals(current.consumedByOperation())) {
            return true;
        }
        return session.exists("""
                SELECT 1 FROM operation_journal
                WHERE operation_id=? AND operation_type='ITEM_USE_RECEIPT'
                  AND operation_state='COMPLETED' AND scope_key=?
                """, receiptId(instanceId, operationId), instanceId.toString());
    }

    private void archiveConsumption(SqlSession session, ItemRegistryRecord record,
                                    Instant now) throws SQLException {
        if (record.consumedByOperation() == null) {
            throw new SQLException("Consumed item has no durable operation receipt");
        }
        String receipt = receiptId(record.instanceId(), record.consumedByOperation());
        Instant consumedAt = record.consumedAt() == null ? now : record.consumedAt();
        String verb = session.databaseType() == DatabaseType.SQLITE
                ? "INSERT OR IGNORE" : "INSERT IGNORE";
        session.update(verb + """
                 INTO operation_journal (
                  operation_id, operation_type, actor_uuid, scope_key, request_hash,
                  operation_state, claim_token, lease_until, result_json, error_code,
                  attempt_count, created_at, updated_at, completed_at, row_version
                ) VALUES (?, 'ITEM_USE_RECEIPT', ?, ?, ?, 'COMPLETED', NULL, NULL,
                          '{}', NULL, 1, ?, ?, ?, 0)
                """, receipt, record.ownerUuid(), record.instanceId().toString(),
                record.signature(), consumedAt, consumedAt, consumedAt);
        if (!session.exists("""
                SELECT 1 FROM operation_journal
                WHERE operation_id=? AND operation_type='ITEM_USE_RECEIPT'
                  AND operation_state='COMPLETED' AND actor_uuid=? AND scope_key=?
                  AND request_hash=?
                """, receipt, record.ownerUuid(), record.instanceId().toString(), record.signature())) {
            throw new SQLException("Item consumption receipt conflicts with another operation");
        }
    }

    private static String receiptId(UUID instanceId, String operationId) {
        try {
            byte[] payload = (instanceId + "\n" + operationId).getBytes(StandardCharsets.UTF_8);
            return "item-use:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
