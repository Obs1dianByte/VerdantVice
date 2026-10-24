package com.verdantvice.processing;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.verdantvice.database.AmbiguousCommitException;
import com.verdantvice.database.DatabaseManager;
import com.verdantvice.database.SqlSession;
import com.verdantvice.database.item.ItemRegistration;
import com.verdantvice.database.item.ItemRegistryRecord;
import com.verdantvice.database.item.ItemRegistryRepository;
import com.verdantvice.database.item.ItemRegistryState;
import com.verdantvice.database.item.RegistrationStatus;
import com.verdantvice.security.CustomItemType;
import com.verdantvice.security.ItemConsumptionGateway;
import com.verdantvice.security.SecureItemService.ValidatedIdentity;
import com.verdantvice.security.SignedItemData;
import com.verdantvice.storage.BatchPersistence;
import com.verdantvice.storage.BatchService;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Atomic processing, independent of Bukkit inventories and rendering. */
public final class ProcessingTransactionService {
    private static final String RECEIPT_PREFIX = "{\"processingDelivery\":true,";
    private static final String ROLLING_PREFIX = "{\"rollingSession\":true,";
    private final DatabaseManager database;
    private final BatchService batches;
    private final ItemConsumptionGateway items;
    private final ItemRegistryRepository registry;

    public ProcessingTransactionService(DatabaseManager database, BatchService batches,
                                        ItemConsumptionGateway items, ItemRegistryRepository registry) {
        this.database = Objects.requireNonNull(database);
        this.batches = Objects.requireNonNull(batches);
        this.items = Objects.requireNonNull(items);
        this.registry = Objects.requireNonNull(registry);
    }

    public CompletableFuture<Void> commit(Plan plan) {
        return commit(plan, null, null);
    }

    public CompletableFuture<Void> retrieve(Plan plan, CompletedJob job) {
        Objects.requireNonNull(job, "job");
        if (plan.output() == null || !job.batchId().equals(plan.output().batchId())) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid job output"));
        }
        return commit(plan, job, null);
    }

    public CompletableFuture<Void> finishRolling(Plan plan, UUID rollingSessionId) {
        Objects.requireNonNull(rollingSessionId, "rollingSessionId");
        if (plan.output() == null || !plan.output().itemType().equals(CustomItemType.JOINT.name())) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid rolling output"));
        }
        return commit(plan, null, rollingSessionId);
    }

    private CompletableFuture<Void> commit(Plan plan, CompletedJob job, UUID rollingSessionId) {
        Objects.requireNonNull(plan, "plan");
        return database.<Void>transaction(session -> {
            List<Change> ordered = plan.changes().stream()
                    .sorted(Comparator.comparing(value -> value.before().batchId())).toList();
            List<BatchPersistence.StoredBatch> locked = new ArrayList<>();
            for (Change change : ordered) {
                BatchPersistence.StoredBatch stored = batches.require(session, change.before().batchId(),
                        plan.owner(), Set.of(change.before().state()), true);
                if (!stored.batch().signature().equals(change.before().signature())) {
                    throw new SQLException("Processing batch changed before commit");
                }
                if (!change.before().batchId().equals(change.after().batchId())
                        || !plan.owner().equals(change.after().ownerUuid())) {
                    throw new IllegalArgumentException("Invalid processing replacement identity");
                }
                locked.add(stored);
            }
            List<UUID> consumedInputs = new ArrayList<>(plan.inputs().stream()
                    .map(value -> value.data().uniqueInstanceId()).toList());
            if (rollingSessionId != null) {
                RollingReceipt rolling = requireRolling(session, plan.owner(), rollingSessionId);
                if (!rolling.productBatchId().equals(plan.output().batchId())) {
                    throw new SQLException("Rolling receipt belongs to another batch");
                }
                consumedInputs.addAll(rolling.inputs());
                clearRolling(session, rolling, plan.now());
            }
            for (ValidatedIdentity input : plan.inputs().stream()
                    .sorted(Comparator.comparing(value -> value.data().uniqueInstanceId())).toList()) {
                items.consume(session, input, Set.of(input.data().itemType()), plan.owner(),
                        plan.operationId(), plan.now());
            }
            for (int index = 0; index < ordered.size(); index++) {
                batches.save(session, locked.get(index), ordered.get(index).after(), plan.now());
            }
            if (plan.created() != null) {
                if (!plan.owner().equals(plan.created().ownerUuid())) throw new IllegalArgumentException("wrong_owner");
                batches.create(session, plan.created(), plan.now());
            }
            if (plan.curingJob() != null) {
                CuringJob curing = plan.curingJob();
                if (session.exists("SELECT curing_job_id FROM curing_jobs WHERE container_id=?", curing.stationId())) {
                    throw new SQLException("Curing station is busy");
                }
                ProductBatch started = plan.changes().getFirst().after();
                CuringData data = started.curingData().orElseThrow();
                session.update("""
                        INSERT INTO curing_jobs (curing_job_id, batch_id, owner_uuid, container_id,
                          status, started_at, last_updated_at, completes_at, process_data_json, row_version)
                        VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, '{}', 0)
                        """, curing.jobId(), started.batchId(), plan.owner(), curing.stationId(), data.startedAt(),
                        data.lastUpdatedAt(), data.startedAt().plus(data.targetDuration()));
            }
            if (job != null) {
                batches.require(session, job.batchId(), plan.owner(), job.type() == ProcessingStationType.DRYING_RACK
                        ? Set.of(BatchState.DRIED, BatchState.SPOILED)
                        : Set.of(BatchState.CURED, BatchState.SPOILED), true);
                String table = job.type() == ProcessingStationType.DRYING_RACK ? "drying_jobs" : "curing_jobs";
                String column = job.type() == ProcessingStationType.DRYING_RACK ? "drying_job_id" : "curing_job_id";
                String stationColumn = job.type() == ProcessingStationType.DRYING_RACK ? "rack_id" : "container_id";
                if (session.update("DELETE FROM " + table + " WHERE " + column + "=? AND batch_id=? AND "
                        + stationColumn + "=? AND owner_uuid=?", job.jobId(), job.batchId(), job.stationId(),
                        plan.owner()) != 1) {
                    throw new SQLException("Processing job was already retrieved or changed");
                }
            }
            if (plan.output() != null) {
                ItemRegistration output = plan.output();
                if (!plan.owner().equals(output.ownerUuid())) throw new IllegalArgumentException("wrong_owner");
                ItemRegistration durable = new ItemRegistration(output.instanceId(), output.itemType(),
                        output.itemId(), output.batchId(), output.ownerUuid(), output.quality(), output.strainId(),
                        output.createdAt(), output.signature(), output.schemaVersion(), receipt(consumedInputs));
                if (registry.register(session, durable).status() != RegistrationStatus.REGISTERED) {
                    throw new SQLException("Processing output identity conflict");
                }
            }
            return null;
        }).exceptionallyCompose(failure -> {
            Throwable cause = failure;
            while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
            if (!(cause instanceof AmbiguousCommitException)) return CompletableFuture.failedFuture(failure);
            // Resolve an uncertain commit by its exact durable output/job, never by replaying inputs.
            return database.read(session -> {
                if (plan.output() != null) return registry.find(session, plan.output().instanceId(), false)
                        .filter(row -> row.signature().equals(plan.output().signature())
                                && plan.owner().equals(row.ownerUuid())
                                && Objects.equals(plan.output().batchId(), row.batchId())).isPresent();
                return plan.curingJob() != null && session.exists(
                        "SELECT curing_job_id FROM curing_jobs WHERE curing_job_id=?", plan.curingJob().jobId());
            }).thenCompose(committed -> committed ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.failedFuture(failure));
        }).thenRun(() -> {
            plan.changes().forEach(change -> batches.remember(change.after()));
            if (plan.created() != null) batches.remember(plan.created());
        });
    }

    /** Consumes all three inputs and records their exact identities before opening the minigame. */
    public CompletableFuture<Void> startRolling(UUID owner, UUID sessionId, ProductBatch source,
            ValidatedIdentity paper, ValidatedIdentity filter, ValidatedIdentity product, Instant now) {
        Objects.requireNonNull(owner); Objects.requireNonNull(sessionId); Objects.requireNonNull(source);
        Objects.requireNonNull(now);
        if (paper.data().itemType() != CustomItemType.ROLLING_PAPER
                || filter.data().itemType() != CustomItemType.FILTER
                || !Set.of(CustomItemType.GROUND_PRODUCT, CustomItemType.MIXTURE).contains(product.data().itemType())
                || !source.batchId().equals(product.data().batchId())) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid rolling ingredients"));
        }
        List<ValidatedIdentity> inputs = List.of(paper, filter, product).stream()
                .sorted(Comparator.comparing(value -> value.data().uniqueInstanceId())).toList();
        if (inputs.stream().map(value -> value.data().uniqueInstanceId()).distinct().count() != 3) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Duplicate rolling ingredient"));
        }
        return database.<Void>transaction(session -> {
            ProductBatch current = batches.require(session, source.batchId(), owner,
                    Set.of(BatchState.GROUND, BatchState.MIXED), true).batch();
            if (!source.signature().equals(current.signature())) throw new SQLException("Rolling batch changed");
            for (ValidatedIdentity input : inputs) {
                items.consume(session, input, Set.of(input.data().itemType()), owner, "roll:" + sessionId, now);
            }
            JsonObject marker = new JsonObject();
            marker.addProperty("rollingSession", true);
            marker.addProperty("sessionId", sessionId.toString());
            JsonArray ids = new JsonArray();
            inputs.forEach(input -> ids.add(input.data().uniqueInstanceId().toString()));
            marker.add("inputs", ids);
            if (session.update("""
                    UPDATE item_registry SET metadata_json=?, updated_at=?, row_version=row_version+1
                    WHERE instance_id=? AND owner_uuid=? AND registry_state='CONSUMED' AND consumed_by_operation=?
                    """, marker.toString(), now, product.data().uniqueInstanceId(), owner, "roll:" + sessionId) != 1) {
                throw new SQLException("Missing rolling product after consumption");
            }
            return null;
        }).exceptionallyCompose(failure -> {
            Throwable cause = unwrap(failure);
            if (!(cause instanceof AmbiguousCommitException)) return CompletableFuture.failedFuture(failure);
            return database.read(session -> findRolling(session, owner, sessionId).isPresent())
                    .thenCompose(committed -> committed ? CompletableFuture.completedFuture(null)
                            : CompletableFuture.failedFuture(failure));
        });
    }

    /** Reactivates the original identities, with replay receipts, never minting replacement items. */
    public CompletableFuture<Void> restoreRolling(UUID owner, UUID sessionId) {
        return database.transaction(session -> {
            var optional = findRolling(session, owner, sessionId);
            if (optional.isEmpty()) return null; // Successful finish or an earlier refund already resolved it.
            RollingReceipt rolling = optional.orElseThrow();
            Instant now = Instant.now();
            for (UUID id : rolling.inputs().stream().sorted().toList()) {
                ItemRegistryRecord input = registry.find(session, id, true).orElseThrow(() ->
                        new SQLException("Missing durable rolling ingredient"));
                if (!owner.equals(input.ownerUuid()) || input.state() != ItemRegistryState.CONSUMED
                        || !("roll:" + sessionId).equals(input.consumedByOperation())) {
                    throw new SQLException("Rolling ingredient changed before refund");
                }
                if (session.update("""
                        UPDATE item_registry SET registry_state='ACTIVE', consumed_at=NULL,
                          consumed_by_operation=NULL, last_operation_id=?, metadata_json=?,
                          updated_at=?, row_version=row_version+1
                        WHERE instance_id=? AND owner_uuid=? AND row_version=? AND registry_state='CONSUMED'
                        """, "roll-refund:" + sessionId, receipt(List.of()), now, id, owner, input.version()) != 1) {
                    throw new SQLException("Concurrent rolling ingredient refund");
                }
            }
            return null;
        });
    }

    /** A new login/server instance has no live GUI for these durable sessions. */
    public CompletableFuture<Void> restoreInterruptedRolling(UUID owner, Set<UUID> liveSessions) {
        Objects.requireNonNull(liveSessions, "liveSessions");
        return database.query("""
                SELECT metadata_json FROM item_registry WHERE owner_uuid=?
                  AND registry_state='CONSUMED' AND metadata_json LIKE ?
                """, row -> UUID.fromString(JsonParser.parseString(row.getString("metadata_json"))
                        .getAsJsonObject().get("sessionId").getAsString()), owner, ROLLING_PREFIX + "%")
                .thenCompose(ids -> {
                    CompletableFuture<Void> restored = CompletableFuture.completedFuture(null);
                    for (UUID id : ids) {
                        // The manager's concurrent set is deliberately checked after the DB read:
                        // a GUI start can commit while this scan is still in flight.
                        if (!liveSessions.contains(id)) restored = restored.thenCompose(ignored -> restoreRolling(owner, id));
                    }
                    return restored;
                });
    }

    private java.util.Optional<RollingReceipt> findRolling(SqlSession session, UUID owner, UUID sessionId)
            throws SQLException {
        String suffix = session.databaseType().isNetworkDatabase() ? " FOR UPDATE" : "";
        List<RollingReceipt> rows = session.query("""
                SELECT instance_id, batch_id, metadata_json FROM item_registry WHERE owner_uuid=?
                  AND registry_state='CONSUMED' AND metadata_json LIKE ?
                """ + suffix, row -> {
            JsonObject metadata = JsonParser.parseString(row.getString("metadata_json")).getAsJsonObject();
            List<UUID> ids = new ArrayList<>();
            metadata.getAsJsonArray("inputs").forEach(value -> ids.add(UUID.fromString(value.getAsString())));
            return new RollingReceipt(UUID.fromString(row.getString("instance_id")),
                    UUID.fromString(row.getString("batch_id")),
                    UUID.fromString(metadata.get("sessionId").getAsString()), List.copyOf(ids));
        }, owner, ROLLING_PREFIX + "%");
        return rows.stream().filter(row -> row.sessionId().equals(sessionId)).findFirst();
    }

    private RollingReceipt requireRolling(SqlSession session, UUID owner, UUID sessionId) throws SQLException {
        return findRolling(session, owner, sessionId).orElseThrow(() ->
                new SQLException("Rolling session was refunded or already completed"));
    }

    private void clearRolling(SqlSession session, RollingReceipt rolling, Instant now) throws SQLException {
        if (session.update("""
                UPDATE item_registry SET metadata_json=NULL, updated_at=?, row_version=row_version+1
                WHERE instance_id=? AND registry_state='CONSUMED' AND consumed_by_operation=?
                """, now, rolling.productId(), "roll:" + rolling.sessionId()) != 1) {
            throw new SQLException("Rolling session changed before completion");
        }
    }

    private static String receipt(List<UUID> ids) {
        JsonObject receipt = new JsonObject();
        receipt.addProperty("processingDelivery", true);
        JsonArray inputs = new JsonArray();
        ids.forEach(id -> inputs.add(id.toString()));
        receipt.add("inputs", inputs);
        return receipt.toString();
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable result = failure;
        while (result instanceof CompletionException && result.getCause() != null) result = result.getCause();
        return result;
    }

    public CompletableFuture<List<PendingOutput>> pending(UUID owner) {
        return database.query("""
                SELECT instance_id, item_type, item_id, batch_id, owner_uuid, quality,
                  strain_id, created_at, schema_version, metadata_json
                FROM item_registry WHERE owner_uuid=? AND registry_state=? AND metadata_json LIKE ?
                ORDER BY created_at, instance_id
                """, row -> {
            SignedItemData data = new SignedItemData(CustomItemType.valueOf(row.getString("item_type")),
                    row.getString("item_id"), UUID.fromString(row.getString("instance_id")),
                    nullableUuid(row.getString("batch_id")), UUID.fromString(row.getString("owner_uuid")),
                    row.getDouble("quality"), Objects.requireNonNullElse(row.getString("strain_id"), ""),
                    Instant.ofEpochMilli(row.getLong("created_at")), row.getInt("schema_version"));
            List<UUID> inputs = new ArrayList<>();
            JsonParser.parseString(row.getString("metadata_json")).getAsJsonObject()
                    .getAsJsonArray("inputs").forEach(value -> inputs.add(UUID.fromString(value.getAsString())));
            return new PendingOutput(data, List.copyOf(inputs));
        }, owner, ItemRegistryState.ACTIVE, RECEIPT_PREFIX + "%");
    }

    private static UUID nullableUuid(String value) { return value == null ? null : UUID.fromString(value); }

    public CompletableFuture<Void> acknowledge(UUID owner, UUID instance) {
        return database.update("""
                UPDATE item_registry SET metadata_json=NULL, row_version=row_version+1, updated_at=?
                WHERE instance_id=? AND owner_uuid=? AND metadata_json LIKE ?
                """, Instant.now(), instance, owner, RECEIPT_PREFIX + "%").thenApply(ignored -> null);
    }

    public record Change(ProductBatch before, ProductBatch after) { }
    public record CuringJob(UUID jobId, UUID stationId) { }
    public record CompletedJob(UUID jobId, UUID stationId, UUID batchId, ProcessingStationType type) {
        public CompletedJob {
            Objects.requireNonNull(jobId); Objects.requireNonNull(stationId);
            Objects.requireNonNull(batchId); Objects.requireNonNull(type);
            if (type == ProcessingStationType.GRINDER) throw new IllegalArgumentException("Grinders have no job");
        }
    }
    private record RollingReceipt(UUID productId, UUID productBatchId, UUID sessionId, List<UUID> inputs) { }
    public record Plan(UUID owner, List<ValidatedIdentity> inputs, List<Change> changes,
                       ProductBatch created, ItemRegistration output, CuringJob curingJob,
                       String operationId, Instant now) {
        public Plan {
            Objects.requireNonNull(owner); Objects.requireNonNull(operationId); Objects.requireNonNull(now);
            inputs = List.copyOf(inputs); changes = List.copyOf(changes);
            if (inputs.stream().map(value -> value.data().uniqueInstanceId()).distinct().count() != inputs.size()) {
                throw new IllegalArgumentException("Duplicate processing input identity");
            }
            if (changes.stream().map(value -> value.before().batchId()).distinct().count() != changes.size()) {
                throw new IllegalArgumentException("Duplicate processing batch change");
            }
            if (curingJob != null && (changes.size() != 1 || created != null || output != null)) {
                throw new IllegalArgumentException("Invalid curing plan");
            }
            if (output != null && (output.batchId() == null || (created == null
                    || !created.batchId().equals(output.batchId())) && changes.stream()
                    .noneMatch(change -> change.after().batchId().equals(output.batchId())))) {
                // A completed drying/curing job has an unchanged persisted batch, validated by retrieve().
                if (!changes.isEmpty() || created != null) throw new IllegalArgumentException("Unrelated processing output");
            }
        }
    }
    public record PendingOutput(SignedItemData data, List<UUID> inputs) { }
}
