package com.verdantvice.worker;

import com.verdantvice.database.DatabaseManager;
import com.verdantvice.database.SqlSession;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Durable handoff between an atomic plant harvest and a Bukkit inventory. */
public final class WorkerHarvestDeliveryRepository {
    private static final String COLUMNS = """
            delivery_id, plant_uuid, batch_id, worker_uuid, farm_id, storage_id,
            item_instance_id, item_created_at, delivery_status, created_at,
            updated_at, delivered_at, row_version
            """.replace('\n', ' ').strip();

    private final DatabaseManager database;

    public WorkerHarvestDeliveryRepository(DatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public HarvestDelivery create(
            SqlSession session,
            UUID plantUuid,
            UUID batchId,
            UUID workerUuid,
            UUID farmId,
            UUID storageId,
            UUID itemInstanceId,
            Instant itemCreatedAt,
            Instant now
    ) throws SQLException {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(plantUuid, "plantUuid");
        Objects.requireNonNull(batchId, "batchId");
        Objects.requireNonNull(workerUuid, "workerUuid");
        Objects.requireNonNull(farmId, "farmId");
        Objects.requireNonNull(storageId, "storageId");
        Objects.requireNonNull(itemInstanceId, "itemInstanceId");
        Objects.requireNonNull(itemCreatedAt, "itemCreatedAt");
        Objects.requireNonNull(now, "now");
        UUID deliveryId = UUID.nameUUIDFromBytes(
                ("worker-harvest-delivery:" + plantUuid)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        session.update("""
                        INSERT INTO worker_harvest_deliveries (
                          delivery_id, plant_uuid, batch_id, worker_uuid, farm_id,
                          storage_id, item_instance_id, item_created_at,
                          delivery_status, created_at, updated_at, delivered_at,
                          row_version
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, NULL, 0)
                        """,
                deliveryId, plantUuid, batchId, workerUuid, farmId, storageId,
                itemInstanceId, itemCreatedAt, now, now);
        return new HarvestDelivery(
                deliveryId, plantUuid, batchId, workerUuid, farmId, storageId,
                itemInstanceId, itemCreatedAt, DeliveryStatus.PENDING,
                now, now, Optional.empty(), 0L);
    }

    public CompletableFuture<List<HarvestDelivery>> pending() {
        return database.query(
                "SELECT " + COLUMNS
                        + " FROM worker_harvest_deliveries "
                        + "WHERE delivery_status='PENDING' ORDER BY created_at, delivery_id",
                WorkerHarvestDeliveryRepository::map);
    }

    public CompletableFuture<Optional<HarvestDelivery>> findByPlant(UUID plantUuid) {
        Objects.requireNonNull(plantUuid, "plantUuid");
        return database.queryOne(
                "SELECT " + COLUMNS
                        + " FROM worker_harvest_deliveries WHERE plant_uuid=?",
                WorkerHarvestDeliveryRepository::map,
                plantUuid);
    }

    public CompletableFuture<HarvestDelivery> markDelivered(
            UUID deliveryId,
            Instant deliveredAt
    ) {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(deliveredAt, "deliveredAt");
        Instant persistedDeliveredAt = Instant.ofEpochMilli(deliveredAt.toEpochMilli());
        return database.transaction(session -> {
            HarvestDelivery current = session.queryOne(
                    "SELECT " + COLUMNS
                            + " FROM worker_harvest_deliveries WHERE delivery_id=?",
                    WorkerHarvestDeliveryRepository::map,
                    deliveryId
            ).orElseThrow(() -> new SQLException(
                    "Unknown worker harvest delivery " + deliveryId));
            if (current.status() == DeliveryStatus.DELIVERED) {
                return current;
            }
            int changed = session.update("""
                            UPDATE worker_harvest_deliveries SET
                              delivery_status='DELIVERED', delivered_at=?, updated_at=?,
                              row_version=row_version+1
                            WHERE delivery_id=? AND delivery_status='PENDING'
                              AND row_version=?
                            """,
                    persistedDeliveredAt, persistedDeliveredAt, deliveryId, current.version());
            if (changed != 1) {
                throw new SQLException("Harvest delivery changed concurrently");
            }
            return new HarvestDelivery(
                    current.deliveryId(), current.plantUuid(), current.batchId(),
                    current.workerUuid(), current.farmId(), current.storageId(),
                    current.itemInstanceId(), current.itemCreatedAt(),
                    DeliveryStatus.DELIVERED, current.createdAt(), persistedDeliveredAt,
                    Optional.of(persistedDeliveredAt), current.version() + 1L);
        });
    }

    private static HarvestDelivery map(ResultSet result) throws SQLException {
        long delivered = result.getLong("delivered_at");
        Optional<Instant> deliveredAt = result.wasNull()
                ? Optional.empty()
                : Optional.of(Instant.ofEpochMilli(delivered));
        try {
            return new HarvestDelivery(
                    UUID.fromString(result.getString("delivery_id")),
                    UUID.fromString(result.getString("plant_uuid")),
                    UUID.fromString(result.getString("batch_id")),
                    UUID.fromString(result.getString("worker_uuid")),
                    UUID.fromString(result.getString("farm_id")),
                    UUID.fromString(result.getString("storage_id")),
                    UUID.fromString(result.getString("item_instance_id")),
                    Instant.ofEpochMilli(result.getLong("item_created_at")),
                    DeliveryStatus.valueOf(result.getString("delivery_status")),
                    Instant.ofEpochMilli(result.getLong("created_at")),
                    Instant.ofEpochMilli(result.getLong("updated_at")),
                    deliveredAt,
                    result.getLong("row_version"));
        } catch (IllegalArgumentException failure) {
            throw new SQLException("Invalid worker harvest delivery row", failure);
        }
    }

    public enum DeliveryStatus { PENDING, DELIVERED }

    public record HarvestDelivery(
            UUID deliveryId,
            UUID plantUuid,
            UUID batchId,
            UUID workerUuid,
            UUID farmId,
            UUID storageId,
            UUID itemInstanceId,
            Instant itemCreatedAt,
            DeliveryStatus status,
            Instant createdAt,
            Instant updatedAt,
            Optional<Instant> deliveredAt,
            long version
    ) {
        public HarvestDelivery {
            Objects.requireNonNull(deliveryId, "deliveryId");
            Objects.requireNonNull(plantUuid, "plantUuid");
            Objects.requireNonNull(batchId, "batchId");
            Objects.requireNonNull(workerUuid, "workerUuid");
            Objects.requireNonNull(farmId, "farmId");
            Objects.requireNonNull(storageId, "storageId");
            Objects.requireNonNull(itemInstanceId, "itemInstanceId");
            Objects.requireNonNull(itemCreatedAt, "itemCreatedAt");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
            deliveredAt = Objects.requireNonNull(deliveredAt, "deliveredAt");
            if (version < 0L) throw new IllegalArgumentException("version cannot be negative");
        }
    }
}
