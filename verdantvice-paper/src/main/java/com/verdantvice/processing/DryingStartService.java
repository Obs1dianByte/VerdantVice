package com.verdantvice.processing;

import com.verdantvice.database.DatabaseManager;
import com.verdantvice.security.CustomItemType;
import com.verdantvice.security.SecureItemService;
import com.verdantvice.security.ItemConsumptionGateway;
import com.verdantvice.storage.BatchAccessException;
import com.verdantvice.storage.BatchAccessFailure;
import com.verdantvice.storage.BatchPersistence;
import com.verdantvice.storage.BatchService;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Atomically consumes one fresh item, advances its batch and creates one drying job. */
public final class DryingStartService {
    private static final Set<CustomItemType> FRESH_ITEMS = Set.of(CustomItemType.FRESH_BATCH);
    private static final Set<BatchState> FRESH_STATES = Set.of(BatchState.FRESH);

    private final DatabaseManager database;
    private final BatchService batches;
    private final ItemConsumptionGateway items;
    private final BatchAuthenticityService authenticity;
    private final byte[] signingSecret;
    private final DryingService drying = new DryingService();

    public DryingStartService(
            DatabaseManager database,
            BatchService batches,
            ItemConsumptionGateway items,
            BatchAuthenticityService authenticity,
            byte[] signingSecret
    ) {
        this.database = Objects.requireNonNull(database, "database");
        this.batches = Objects.requireNonNull(batches, "batches");
        this.items = Objects.requireNonNull(items, "items");
        this.authenticity = Objects.requireNonNull(authenticity, "authenticity");
        this.signingSecret = Objects.requireNonNull(signingSecret, "signingSecret").clone();
    }

    public CompletableFuture<Result> start(
            SecureItemService.ValidatedItem item,
            UUID playerUuid,
            UUID stationId,
            Instant now,
            Parameters parameters
    ) {
        Objects.requireNonNull(item, "item");
        return startIdentity(item.identity(), playerUuid, stationId, now, parameters);
    }

    /** Domain identity entry point used by automated integration tests and worker jobs. */
    public CompletableFuture<Result> startIdentity(
            SecureItemService.ValidatedIdentity item,
            UUID playerUuid,
            UUID stationId,
            Instant now,
            Parameters parameters
    ) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(stationId, "stationId");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(parameters, "parameters");
        UUID jobId = UUID.randomUUID();
        String operationId = "dry:" + item.data().uniqueInstanceId();
        return database.transaction(session -> {
            boolean occupied = session.exists("""
                    SELECT drying_job_id FROM drying_jobs
                    WHERE rack_id=? AND status IN ('ACTIVE', 'READY')
                    """, stationId);
            if (occupied) {
                throw new ProcessingFailureException(ProcessingFailureReason.STATION_BUSY);
            }
            BatchPersistence.StoredBatch stored;
            try {
                stored = batches.require(
                        session,
                        item.data().batchId(),
                        playerUuid,
                        FRESH_STATES,
                        true
                );
            } catch (BatchAccessException failure) {
                throw map(failure);
            }
            items.consume(session, item, FRESH_ITEMS, playerUuid, operationId, now);
            ProductBatch started = authenticity.sign(drying.start(
                    stored.batch(),
                    now,
                    parameters.duration(),
                    parameters.cleanliness(),
                    parameters.stability(),
                    parameters.speed()
            ), signingSecret);
            batches.save(session, stored, started, now);
            insertJob(session, jobId, stationId, playerUuid, started);
            return new Result(jobId, stationId, started);
        }).thenApply(result -> {
            batches.remember(result.batch());
            return result;
        });
    }

    private static void insertJob(
            com.verdantvice.database.SqlSession session,
            UUID jobId,
            UUID stationId,
            UUID ownerUuid,
            ProductBatch batch
    ) throws SQLException {
        DryingData data = batch.dryingData().orElseThrow();
        session.update("""
                INSERT INTO drying_jobs (
                  drying_job_id, batch_id, owner_uuid, rack_id, drying_stage,
                  status, started_at, last_updated_at, completes_at,
                  process_data_json, row_version
                ) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, '{}', 0)
                """,
                jobId,
                batch.batchId(),
                ownerUuid,
                stationId,
                data.stage(),
                data.startedAt(),
                data.lastUpdatedAt(),
                data.startedAt().plus(data.targetDuration())
        );
    }

    private static ProcessingFailureException map(BatchAccessException failure) {
        ProcessingFailureReason reason = switch (failure.reason()) {
            case MISSING_BATCH -> ProcessingFailureReason.MISSING_BATCH;
            case INVALID_BATCH_SIGNATURE -> ProcessingFailureReason.INVALID_BATCH_SIGNATURE;
            case WRONG_OWNER -> ProcessingFailureReason.WRONG_OWNER;
            case WRONG_BATCH_STATE -> ProcessingFailureReason.WRONG_BATCH_STATE;
        };
        return new ProcessingFailureException(reason);
    }

    public record Parameters(
            Duration duration,
            double cleanliness,
            double stability,
            double speed
    ) {
        public Parameters {
            Objects.requireNonNull(duration, "duration");
            if (duration.isZero() || duration.isNegative()) {
                throw new IllegalArgumentException("duration must be positive");
            }
        }
    }

    public record Result(UUID jobId, UUID stationId, ProductBatch batch) {
    }
}
