package com.verdantvice.processing;

import com.verdantvice.database.DatabaseManager;
import com.verdantvice.database.SqlSession;
import com.verdantvice.database.item.ItemRegistration;
import com.verdantvice.database.item.ItemRegistryRecord;
import com.verdantvice.database.item.ItemRegistryRepository;
import com.verdantvice.database.item.ItemRegistryState;
import com.verdantvice.database.item.RegistrationStatus;
import com.verdantvice.security.CustomItemType;
import com.verdantvice.security.ItemConsumptionGateway;
import com.verdantvice.security.ItemSigner;
import com.verdantvice.security.SecureItemService;
import com.verdantvice.security.SignedItemData;
import com.verdantvice.storage.BatchService;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Database-only atomic transitions with a durable, replayable inventory handoff. */
public final class ProcessingTransferService {
    private final DatabaseManager database;
    private final BatchService batches;
    private final ItemConsumptionGateway items;
    private final ItemRegistryRepository registry;
    private final java.util.function.Function<SignedItemData, String> payloadHash;

    public ProcessingTransferService(DatabaseManager database, BatchService batches,
                                     ItemConsumptionGateway items, ItemRegistryRepository registry,
                                     ItemSigner signer) {
        this(database, batches, items, registry, signer::payloadHash);
    }

    public ProcessingTransferService(DatabaseManager database, BatchService batches,
                                     ItemConsumptionGateway items, ItemRegistryRepository registry,
                                     java.util.function.Function<SignedItemData, String> payloadHash) {
        this.database = Objects.requireNonNull(database, "database");
        this.batches = Objects.requireNonNull(batches, "batches");
        this.items = Objects.requireNonNull(items, "items");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.payloadHash = Objects.requireNonNull(payloadHash, "payloadHash");
    }

    public CompletableFuture<Delivery> packageBatch(
            SecureItemService.ValidatedIdentity input, UUID owner, UUID farm,
            UUID storage, String operationId, Instant now) {
        Objects.requireNonNull(input, "input");
        return database.transaction(session -> {
            Optional<Delivery> replay = find(session, operationId);
            if (replay.isPresent()) return requireReplay(replay.get(), owner, farm);
            requireStorage(session, farm, storage);
            ProductBatch batch = batches.require(session, input.data().batchId(), owner,
                    Set.of(BatchState.DRIED, BatchState.CURED), true).batch();
            items.consume(session, input,
                    Set.of(CustomItemType.DRY_BATCH, CustomItemType.CURED_BATCH), owner,
                    operationId, now);
            return create(session, batch, CustomItemType.PACKAGE, farm, storage, operationId, now);
        });
    }

    public CompletableFuture<Delivery> unloadDrying(
            UUID station, UUID batchId, UUID owner, UUID farm, UUID storage,
            String operationId, Instant now) {
        return database.transaction(session -> {
            Optional<Delivery> replay = find(session, operationId);
            if (replay.isPresent()) return requireReplay(replay.get(), owner, farm);
            requireStorage(session, farm, storage);
            String lock = session.databaseType().isNetworkDatabase() ? " FOR UPDATE" : "";
            UUID jobId = session.queryOne("""
                    SELECT drying_job_id FROM drying_jobs
                    WHERE rack_id=? AND batch_id=? AND owner_uuid=?
                    """ + lock,
                    row -> UUID.fromString(row.getString(1)), station, batchId, owner)
                    .orElseThrow(() -> new SQLException("Drying job is no longer available"));
            ProductBatch batch = batches.require(session, batchId, owner,
                    Set.of(BatchState.DRIED, BatchState.SPOILED), true).batch();
            int deleted = session.update("DELETE FROM drying_jobs WHERE drying_job_id=?", jobId);
            if (deleted != 1) throw new SQLException("Drying job changed during retrieval");
            return create(session, batch, CustomItemType.DRY_BATCH, farm, storage, operationId, now);
        });
    }

    public CompletableFuture<Optional<Delivery>> find(String operationId) {
        return database.read(session -> find(session, operationId));
    }

    public CompletableFuture<List<Delivery>> pending() {
        return database.read(session -> {
            List<String> ids = session.query("""
                    SELECT operation_id FROM processing_deliveries
                    WHERE delivery_status='PENDING' ORDER BY created_at, operation_id
                    """, row -> row.getString(1));
            java.util.ArrayList<Delivery> result = new java.util.ArrayList<>();
            for (String id : ids) result.add(find(session, id).orElseThrow());
            return List.copyOf(result);
        });
    }

    public CompletableFuture<Void> delivered(String operationId, Instant now) {
        return database.update("""
                UPDATE processing_deliveries SET delivery_status='DELIVERED', delivered_at=?
                WHERE operation_id=? AND delivery_status='PENDING'
                """, now, operationId).thenApply(ignored -> null);
    }

    private Delivery create(SqlSession session, ProductBatch batch, CustomItemType type,
                            UUID farm, UUID storage, String operationId, Instant now) throws SQLException {
        Instant itemCreatedAt = Instant.ofEpochMilli(now.toEpochMilli());
        UUID instance = UUID.nameUUIDFromBytes(("processing-output:" + operationId)
                .getBytes(StandardCharsets.UTF_8));
        String strain = batch.strainComposition().keySet().stream().sorted().findFirst().orElse("");
        SignedItemData data = new SignedItemData(type,
                type == CustomItemType.PACKAGE ? "package" : "dry-batch", instance,
                batch.batchId(), batch.ownerUuid(), batch.qualityScore(), strain, itemCreatedAt, 1);
        if (registry.register(session, new ItemRegistration(instance, type.name(), data.itemId(),
                data.batchId(), data.ownerUuid(), data.quality(), strain.isBlank() ? null : strain,
                itemCreatedAt, payloadHash.apply(data), 1, null)).status() == RegistrationStatus.CONFLICT) {
            throw new SQLException("Processing output identity conflict");
        }
        session.update("""
                INSERT INTO processing_deliveries (
                  operation_id, batch_id, owner_uuid, farm_id, storage_id,
                  item_instance_id, delivery_status, created_at, delivered_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, NULL)
                """, operationId, batch.batchId(), batch.ownerUuid(), farm, storage, instance, now);
        return new Delivery(operationId, farm, storage, data, false, ItemRegistryState.ACTIVE);
    }

    private Optional<Delivery> find(SqlSession session, String operationId) throws SQLException {
        return session.queryOne("""
                SELECT farm_id, storage_id, item_instance_id, delivery_status
                FROM processing_deliveries WHERE operation_id=?
                """, row -> {
            ItemRegistryRecord item = registry.find(session,
                    UUID.fromString(row.getString("item_instance_id")), false)
                    .orElseThrow(() -> new SQLException("Missing processing output identity"));
            SignedItemData data = new SignedItemData(CustomItemType.valueOf(item.itemType()),
                    item.itemId(), item.instanceId(), item.batchId(), item.ownerUuid(),
                    item.quality(), item.strainId() == null ? "" : item.strainId(),
                    item.createdAt(), item.schemaVersion());
            return new Delivery(operationId, UUID.fromString(row.getString("farm_id")),
                    UUID.fromString(row.getString("storage_id")), data,
                    "DELIVERED".equals(row.getString("delivery_status")), item.state());
        }, operationId);
    }

    private static void requireStorage(SqlSession session, UUID farm, UUID storage) throws SQLException {
        if (!session.exists("SELECT storage_id FROM farm_storages WHERE storage_id=? AND farm_id=?",
                storage, farm)) throw new SQLException("Processing destination is not linked to this farm");
    }

    private static Delivery requireReplay(Delivery delivery, UUID owner, UUID farm) {
        if (!delivery.data().ownerUuid().equals(owner) || !delivery.farmId().equals(farm)) {
            throw new IllegalArgumentException("Processing operation belongs to another farm or owner");
        }
        return delivery;
    }

    public record Delivery(String operationId, UUID farmId, UUID storageId,
                           SignedItemData data, boolean delivered, ItemRegistryState itemState) {
    }
}
