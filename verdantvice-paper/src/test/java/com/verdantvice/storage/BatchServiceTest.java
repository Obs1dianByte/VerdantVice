package com.verdantvice.storage;

import com.verdantvice.database.SqlSession;
import com.verdantvice.processing.BatchAuthenticityService;
import com.verdantvice.processing.BatchState;
import com.verdantvice.processing.ProductBatch;
import com.verdantvice.test.TestFixtures;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BatchServiceTest {
    private static final byte[] SECRET = "batch-service-test-secret-32-bytes!".getBytes();
    private final BatchAuthenticityService authenticity = new BatchAuthenticityService();

    @Test
    void createCachesOnlyAfterSuccessfulPersistence() {
        FakePersistence persistence = new FakePersistence();
        persistence.createFuture = new CompletableFuture<>();
        BatchService service = service(persistence);
        ProductBatch batch = signed(TestFixtures.freshBatch());

        CompletableFuture<ProductBatch> created = service.create(batch);
        assertTrue(service.cached(batch.batchId()).isEmpty());

        persistence.createFuture.complete(null);
        assertSame(batch, created.join());
        assertSame(batch, service.cached(batch.batchId()).orElseThrow());
    }

    @Test
    void concurrentCacheMissesShareExactlyOneRepositoryQuery() {
        FakePersistence persistence = new FakePersistence();
        persistence.findFuture = new CompletableFuture<>();
        BatchService service = service(persistence);
        ProductBatch batch = signed(TestFixtures.freshBatch());

        CompletableFuture<Optional<ProductBatch>> first = service.find(batch.batchId());
        CompletableFuture<Optional<ProductBatch>> second = service.find(batch.batchId());

        assertEquals(1, persistence.findCalls.get());
        assertEquals(0, persistence.loadAllCalls.get());
        persistence.findFuture.complete(Optional.of(batch));
        assertSame(batch, first.join().orElseThrow());
        assertSame(batch, second.join().orElseThrow());
        assertEquals(0, service.inFlightCount());
        assertSame(batch, service.cached(batch.batchId()).orElseThrow());
    }

    @Test
    void missingRepositoryRowProducesMissingBatch() {
        FakePersistence persistence = new FakePersistence();
        persistence.findFuture = CompletableFuture.completedFuture(Optional.empty());
        BatchService service = service(persistence);

        CompletionException failure = assertThrows(CompletionException.class, () ->
                service.require(UUID.randomUUID(), TestFixtures.OWNER,
                        Set.of(BatchState.FRESH)).join());
        assertEquals(BatchAccessFailure.MISSING_BATCH,
                ((BatchAccessException) failure.getCause()).reason());
        assertEquals(0, service.inFlightCount());
    }

    @Test
    void invalidStoredSignatureIsRejectedAndNeverCached() {
        FakePersistence persistence = new FakePersistence();
        ProductBatch unsigned = TestFixtures.freshBatch();
        persistence.findFuture = CompletableFuture.completedFuture(Optional.of(unsigned));
        BatchService service = service(persistence);

        CompletionException failure = assertThrows(CompletionException.class,
                () -> service.find(unsigned.batchId()).join());
        assertEquals(BatchAccessFailure.INVALID_BATCH_SIGNATURE,
                ((BatchAccessException) failure.getCause()).reason());
        assertTrue(service.cached(unsigned.batchId()).isEmpty());
        assertEquals(0, service.inFlightCount());
    }

    @Test
    void ownerAndStateAreValidatedSeparately() {
        FakePersistence persistence = new FakePersistence();
        ProductBatch batch = signed(TestFixtures.freshBatch());
        persistence.findFuture = CompletableFuture.completedFuture(Optional.of(batch));
        BatchService service = service(persistence);

        CompletionException ownerFailure = assertThrows(CompletionException.class, () ->
                service.require(batch.batchId(), UUID.randomUUID(),
                        Set.of(BatchState.FRESH)).join());
        assertEquals(BatchAccessFailure.WRONG_OWNER,
                ((BatchAccessException) ownerFailure.getCause()).reason());
        assertTrue(service.cached(batch.batchId()).isEmpty());

        CompletionException stateFailure = assertThrows(CompletionException.class, () ->
                service.require(batch.batchId(), TestFixtures.OWNER,
                        Set.of(BatchState.DRIED)).join());
        assertEquals(BatchAccessFailure.WRONG_BATCH_STATE,
                ((BatchAccessException) stateFailure.getCause()).reason());
        assertTrue(service.cached(batch.batchId()).isEmpty());
    }

    @Test
    void saveUpdatesCacheOnlyAfterDatabaseSuccess() {
        FakePersistence persistence = new FakePersistence();
        BatchService service = service(persistence);
        ProductBatch fresh = signed(TestFixtures.freshBatch());
        service.remember(fresh);
        ProductBatch drying = signed(new com.verdantvice.processing.DryingService().start(
                fresh, TestFixtures.NOW, java.time.Duration.ofHours(1),
                100.0, 100.0, 1.0));
        persistence.saveFuture = new CompletableFuture<>();

        CompletableFuture<ProductBatch> saved = service.save(drying);
        assertEquals(BatchState.FRESH,
                service.cached(fresh.batchId()).orElseThrow().state());
        persistence.saveFuture.complete(null);
        assertEquals(BatchState.DRYING, saved.join().state());
        assertEquals(BatchState.DRYING,
                service.cached(fresh.batchId()).orElseThrow().state());
    }

    private BatchService service(FakePersistence persistence) {
        return new BatchService(persistence, authenticity, SECRET);
    }

    private ProductBatch signed(ProductBatch batch) {
        return authenticity.sign(batch, SECRET);
    }

    private static final class FakePersistence implements BatchPersistence {
        private final AtomicInteger findCalls = new AtomicInteger();
        private final AtomicInteger loadAllCalls = new AtomicInteger();
        private CompletableFuture<Optional<ProductBatch>> findFuture =
                CompletableFuture.completedFuture(Optional.empty());
        private CompletableFuture<Void> createFuture = CompletableFuture.completedFuture(null);
        private CompletableFuture<Void> saveFuture = CompletableFuture.completedFuture(null);

        @Override
        public CompletableFuture<List<ProductBatch>> loadAll() {
            loadAllCalls.incrementAndGet();
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<Optional<ProductBatch>> find(UUID batchId) {
            findCalls.incrementAndGet();
            return findFuture;
        }

        @Override
        public CompletableFuture<Void> create(ProductBatch batch) {
            return createFuture;
        }

        @Override
        public void create(SqlSession session, ProductBatch batch, Instant now)
                throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> save(ProductBatch batch) {
            return saveFuture;
        }

        @Override
        public Optional<StoredBatch> find(SqlSession session, UUID batchId, boolean forUpdate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void save(SqlSession session, StoredBatch expected, ProductBatch replacement,
                         Instant now) throws SQLException {
            throw new UnsupportedOperationException();
        }
    }
}
