package com.verdantvice.storage;

import com.verdantvice.database.SqlSession;
import com.verdantvice.processing.BatchAuthenticityService;
import com.verdantvice.processing.BatchState;
import com.verdantvice.processing.ProductBatch;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared, verified catalog of durable product batches. Database rows remain the
 * source of truth; this class only caches batches after successful persistence
 * or a verified repository read.
 */
public final class BatchService {
    private final BatchPersistence persistence;
    private final BatchAuthenticityService authenticity;
    private final byte[] signingSecret;
    private final ConcurrentHashMap<UUID, ProductBatch> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<Optional<ProductBatch>>> inFlight =
            new ConcurrentHashMap<>();

    public BatchService(
            BatchPersistence persistence,
            BatchAuthenticityService authenticity,
            byte[] signingSecret
    ) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.authenticity = Objects.requireNonNull(authenticity, "authenticity");
        this.signingSecret = Objects.requireNonNull(signingSecret, "signingSecret").clone();
    }

    public CompletableFuture<Void> loadAll() {
        return persistence.loadAll().thenAccept(rows -> {
            ConcurrentHashMap<UUID, ProductBatch> verified = new ConcurrentHashMap<>();
            for (ProductBatch batch : rows) {
                verifySignature(batch);
                verified.put(batch.batchId(), batch);
            }
            cache.clear();
            cache.putAll(verified);
        });
    }

    public CompletableFuture<ProductBatch> create(ProductBatch batch) {
        verifySignature(batch);
        return persistence.create(batch).thenApply(ignored -> remember(batch));
    }

    public void create(SqlSession session, ProductBatch batch, Instant now)
            throws SQLException {
        verifySignature(batch);
        persistence.create(session, batch, now);
    }

    public CompletableFuture<ProductBatch> save(ProductBatch batch) {
        verifySignature(batch);
        return persistence.save(batch).thenApply(ignored -> remember(batch));
    }

    public Optional<ProductBatch> cached(UUID batchId) {
        return Optional.ofNullable(cache.get(Objects.requireNonNull(batchId, "batchId")));
    }

    public ProductBatch cachedOrNull(UUID batchId) {
        return cache.get(Objects.requireNonNull(batchId, "batchId"));
    }

    public ProductBatch remember(ProductBatch batch) {
        verifySignature(batch);
        cache.put(batch.batchId(), batch);
        return batch;
    }

    public void invalidate(UUID batchId) {
        cache.remove(Objects.requireNonNull(batchId, "batchId"));
    }

    public CompletableFuture<Optional<ProductBatch>> find(UUID batchId) {
        Objects.requireNonNull(batchId, "batchId");
        ProductBatch cached = cache.get(batchId);
        if (cached != null) {
            return CompletableFuture.completedFuture(Optional.of(cached));
        }
        return loadShared(batchId).thenApply(optional -> optional.map(this::remember));
    }

    public CompletableFuture<ProductBatch> require(
            UUID batchId,
            UUID ownerUuid,
            Set<BatchState> expectedStates
    ) {
        Objects.requireNonNull(batchId, "batchId");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Set<BatchState> states = Set.copyOf(expectedStates);
        ProductBatch cached = cache.get(batchId);
        if (cached != null) {
            return CompletableFuture.completedFuture(validate(cached, ownerUuid, states));
        }
        return loadShared(batchId).thenApply(optional -> remember(validate(
                optional.orElseThrow(() -> new BatchAccessException(
                        BatchAccessFailure.MISSING_BATCH)),
                ownerUuid,
                states)));
    }

    public BatchPersistence.StoredBatch require(
            SqlSession session,
            UUID batchId,
            UUID ownerUuid,
            Set<BatchState> expectedStates,
            boolean forUpdate
    ) throws SQLException {
        BatchPersistence.StoredBatch stored = persistence.find(session, batchId, forUpdate)
                .orElseThrow(() -> new BatchAccessException(BatchAccessFailure.MISSING_BATCH));
        validate(stored.batch(), ownerUuid, Set.copyOf(expectedStates));
        return stored;
    }

    public void save(
            SqlSession session,
            BatchPersistence.StoredBatch expected,
            ProductBatch replacement,
            Instant now
    ) throws SQLException {
        verifySignature(replacement);
        persistence.save(session, expected, replacement, now);
    }

    private CompletableFuture<Optional<ProductBatch>> loadShared(UUID batchId) {
        CompletableFuture<Optional<ProductBatch>> future =
                inFlight.computeIfAbsent(batchId, this::loadOneVerified);
        future.whenComplete((ignored, failure) -> inFlight.remove(batchId, future));
        return future;
    }

    private CompletableFuture<Optional<ProductBatch>> loadOneVerified(UUID batchId) {
        return persistence.find(batchId).thenApply(optional -> optional.map(batch -> {
            verifySignature(batch);
            return batch;
        }));
    }

    private ProductBatch validate(
            ProductBatch batch,
            UUID ownerUuid,
            Set<BatchState> expectedStates
    ) {
        verifySignature(batch);
        if (!batch.ownerUuid().equals(ownerUuid)) {
            throw new BatchAccessException(BatchAccessFailure.WRONG_OWNER);
        }
        if (!expectedStates.contains(batch.state())) {
            throw new BatchAccessException(BatchAccessFailure.WRONG_BATCH_STATE);
        }
        return batch;
    }

    private void verifySignature(ProductBatch batch) {
        Objects.requireNonNull(batch, "batch");
        if (!authenticity.verify(batch, signingSecret)) {
            throw new BatchAccessException(BatchAccessFailure.INVALID_BATCH_SIGNATURE);
        }
    }

    int cachedCount() {
        return cache.size();
    }

    int inFlightCount() {
        return inFlight.size();
    }
}
