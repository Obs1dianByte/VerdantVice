package com.verdantvice.worker;

import com.verdantvice.database.DatabaseManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorkerHarvestDeliveryRepositoryIntegrationTest {
    @TempDir
    Path root;

    private DatabaseManager database;
    private WorkerHarvestDeliveryRepository deliveries;
    private WorkerReliabilityTestSupport.ParentGraph graph;
    private UUID batch;

    @BeforeEach
    void start() throws Exception {
        database = WorkerReliabilityTestSupport.start(root);
        graph = WorkerReliabilityTestSupport.insertParentGraph(database);
        batch = WorkerReliabilityTestSupport.insertBatch(database, graph);
        deliveries = new WorkerHarvestDeliveryRepository(database);
    }

    @AfterEach
    void close() {
        if (database != null) database.close();
    }

    @Test
    void pendingDeliveryPersistsEveryAntiDupeIdentity() throws Exception {
        UUID plant = UUID.randomUUID();
        UUID item = UUID.randomUUID();
        Instant now = Instant.now();
        WorkerHarvestDeliveryRepository.HarvestDelivery created =
                database.transaction(session -> deliveries.create(
                        session, plant, batch, graph.worker(), graph.farm(),
                        graph.storage(), item, now, now))
                        .get(15, TimeUnit.SECONDS);
        assertEquals(plant, created.plantUuid());
        assertEquals(batch, created.batchId());
        assertEquals(item, created.itemInstanceId());
        assertEquals(WorkerHarvestDeliveryRepository.DeliveryStatus.PENDING,
                created.status());
    }

    @Test
    void pendingRowsSurviveRepositoryRecreation() throws Exception {
        WorkerHarvestDeliveryRepository.HarvestDelivery created = createDelivery();
        deliveries = new WorkerHarvestDeliveryRepository(database);
        assertEquals(created.deliveryId(), deliveries.pending()
                .get(15, TimeUnit.SECONDS).get(0).deliveryId());
    }

    @Test
    void markDeliveredIsIdempotentAndRemovesPendingRow() throws Exception {
        WorkerHarvestDeliveryRepository.HarvestDelivery created = createDelivery();
        Instant deliveredAt = Instant.now().plusSeconds(1);
        WorkerHarvestDeliveryRepository.HarvestDelivery first = deliveries.markDelivered(
                created.deliveryId(), deliveredAt).get(15, TimeUnit.SECONDS);
        WorkerHarvestDeliveryRepository.HarvestDelivery second = deliveries.markDelivered(
                created.deliveryId(), deliveredAt).get(15, TimeUnit.SECONDS);
        assertEquals(first, second);
        assertEquals(WorkerHarvestDeliveryRepository.DeliveryStatus.DELIVERED,
                first.status());
        assertTrue(deliveries.pending().get(15, TimeUnit.SECONDS).isEmpty());
    }

    @Test
    void samePlantCannotProduceSecondDurableDelivery() throws Exception {
        WorkerHarvestDeliveryRepository.HarvestDelivery created = createDelivery();
        UUID anotherBatch = WorkerReliabilityTestSupport.insertBatch(database, graph);
        CompletionException failure = assertThrows(CompletionException.class, () ->
                database.transaction(session -> deliveries.create(
                        session,
                        created.plantUuid(),
                        anotherBatch,
                        graph.worker(),
                        graph.farm(),
                        graph.storage(),
                        UUID.randomUUID(),
                        Instant.now(),
                        Instant.now())).join());
        assertTrue(failure.getCause() != null);
        assertEquals(1, deliveries.pending().get(15, TimeUnit.SECONDS).size());
    }

    @Test
    void missingStorageRollsBackDeliveryInsert() {
        CompletionException failure = assertThrows(CompletionException.class, () ->
                database.transaction(session -> deliveries.create(
                        session,
                        UUID.randomUUID(),
                        batch,
                        graph.worker(),
                        graph.farm(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        Instant.now(),
                        Instant.now())).join());
        assertTrue(failure.getCause() != null);
        assertTrue(deliveries.pending().join().isEmpty());
    }

    private WorkerHarvestDeliveryRepository.HarvestDelivery createDelivery()
            throws Exception {
        Instant now = Instant.now();
        return database.transaction(session -> deliveries.create(
                        session,
                        UUID.randomUUID(),
                        batch,
                        graph.worker(),
                        graph.farm(),
                        graph.storage(),
                        UUID.randomUUID(),
                        now,
                        now))
                .get(15, TimeUnit.SECONDS);
    }
}
