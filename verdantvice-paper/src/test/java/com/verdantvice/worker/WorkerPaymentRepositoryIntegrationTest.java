package com.verdantvice.worker;

import com.verdantvice.database.DatabaseIntegrityException;
import com.verdantvice.database.DatabaseManager;
import com.verdantvice.database.DatabaseUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorkerPaymentRepositoryIntegrationTest {
    @TempDir
    Path root;

    private DatabaseManager database;
    private WorkerPaymentRepository repository;
    private WorkerReliabilityTestSupport.ParentGraph graph;

    @BeforeEach
    void start() throws Exception {
        database = WorkerReliabilityTestSupport.start(root);
        repository = new WorkerPaymentRepository(database);
        graph = WorkerReliabilityTestSupport.insertParentGraph(database);
    }

    @AfterEach
    void close() {
        if (database != null) database.close();
    }

    @Test
    void createPendingWorksForExistingWorkerPayerAndFarm() throws Exception {
        WorkerPaymentRepository.PaymentRecord payment = repository.createPending(
                graph.worker(), graph.owner(), graph.farm(), 4, 12, Instant.now())
                .get(15, TimeUnit.SECONDS);
        assertEquals(WorkerPaymentRepository.PaymentStatus.PENDING, payment.status());
        assertEquals(graph.worker(), payment.workerUuid());
        assertEquals(graph.owner(), payment.payerUuid());
    }

    @Test
    void missingWorkerParentHasDedicatedReason() {
        WorkerPaymentException failure = paymentFailure(repository.createPending(
                UUID.randomUUID(), graph.owner(), graph.farm(), 4, 13, Instant.now()));
        assertEquals(WorkerPaymentFailureReason.MISSING_WORKER_PARENT, failure.reason());
    }

    @Test
    void missingPayerParentHasDedicatedReason() {
        WorkerPaymentException failure = paymentFailure(repository.createPending(
                graph.worker(), UUID.randomUUID(), graph.farm(), 4, 14, Instant.now()));
        assertEquals(WorkerPaymentFailureReason.MISSING_PAYER_PARENT, failure.reason());
    }

    @Test
    void missingFarmParentHasDedicatedReason() {
        WorkerPaymentException failure = paymentFailure(repository.createPending(
                graph.worker(), graph.owner(), UUID.randomUUID(), 4, 15, Instant.now()));
        assertEquals(WorkerPaymentFailureReason.MISSING_FARM_PARENT, failure.reason());
    }

    @Test
    void runtimeIdentityMismatchIsStaleRuntime() throws Exception {
        UUID anotherOwner = UUID.randomUUID();
        Instant now = Instant.now();
        database.transaction(session -> {
            session.update("INSERT INTO players (player_uuid, created_at, updated_at, "
                            + "last_seen_at, row_version) VALUES (?, ?, ?, ?, 0)",
                    anotherOwner, now, now, now);
            return null;
        }).get(15, TimeUnit.SECONDS);
        WorkerPaymentException failure = paymentFailure(repository.createPending(
                graph.worker(), anotherOwner, graph.farm(), 4, 16, now));
        assertEquals(WorkerPaymentFailureReason.STALE_RUNTIME, failure.reason());
    }

    @Test
    void oneMinecraftDayCreatesExactlyOneLedger() throws Exception {
        WorkerPaymentRepository.PaymentRecord first = repository.createPending(
                graph.worker(), graph.owner(), graph.farm(), 4, 17, Instant.now())
                .get(15, TimeUnit.SECONDS);
        WorkerPaymentRepository.PaymentRecord second = repository.createPending(
                graph.worker(), graph.owner(), graph.farm(), 4, 17, Instant.now())
                .get(15, TimeUnit.SECONDS);
        assertEquals(first.paymentId(), second.paymentId());
        assertEquals(1, repository.history(graph.worker(), 10)
                .get(15, TimeUnit.SECONDS).size());
    }

    @Test
    void concurrentSalaryTicksConvergeOnOneLedger() throws Exception {
        List<CompletableFuture<WorkerPaymentRepository.PaymentRecord>> futures =
                new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            futures.add(repository.createPending(
                    graph.worker(), graph.owner(), graph.farm(), 4, 18, Instant.now()));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .get(20, TimeUnit.SECONDS);
        UUID paymentId = futures.get(0).join().paymentId();
        assertTrue(futures.stream().allMatch(future ->
                future.join().paymentId().equals(paymentId)));
        assertEquals(1, repository.history(graph.worker(), 100)
                .get(15, TimeUnit.SECONDS).size());
    }

    @Test
    void foreignKeyFailureIsNotRetriedOrWrappedAsUnavailable() {
        AtomicInteger attempts = new AtomicInteger();
        CompletionException completion = assertThrows(CompletionException.class, () ->
                database.transaction(session -> {
                    attempts.incrementAndGet();
                    throw new SQLException(
                            "[SQLITE_CONSTRAINT_FOREIGNKEY] FOREIGN KEY constraint failed",
                            "23000",
                            19);
                }).join());
        assertEquals(1, attempts.get());
        assertInstanceOf(DatabaseIntegrityException.class, completion.getCause());
        assertFalse(completion.getCause() instanceof DatabaseUnavailableException);
    }

    @Test
    void paidAndFailedLedgersBecomeTerminalExactlyOnce() throws Exception {
        WorkerPaymentRepository.PaymentRecord paid = repository.createPending(
                graph.worker(), graph.owner(), graph.farm(), 4, 19, Instant.now())
                .get(15, TimeUnit.SECONDS);
        WorkerPaymentRepository.PaymentRecord finalized = repository.markPaid(
                paid.paymentId(), Instant.now()).get(15, TimeUnit.SECONDS);
        assertEquals(WorkerPaymentRepository.PaymentStatus.PAID, finalized.status());
        assertEquals(finalized, repository.markPaid(
                paid.paymentId(), finalized.paidAt().orElseThrow())
                .get(15, TimeUnit.SECONDS));

        WorkerPaymentRepository.PaymentRecord failed = repository.createPending(
                graph.worker(), graph.owner(), graph.farm(), 4, 20, Instant.now())
                .get(15, TimeUnit.SECONDS);
        assertEquals(WorkerPaymentRepository.PaymentStatus.FAILED,
                repository.markFailed(failed.paymentId())
                        .get(15, TimeUnit.SECONDS).status());
        assertNotEquals(paid.paymentId(), failed.paymentId());
    }

    private static WorkerPaymentException paymentFailure(
            CompletableFuture<?> future) {
        CompletionException failure = assertThrows(CompletionException.class, future::join);
        return assertInstanceOf(WorkerPaymentException.class, failure.getCause());
    }
}
