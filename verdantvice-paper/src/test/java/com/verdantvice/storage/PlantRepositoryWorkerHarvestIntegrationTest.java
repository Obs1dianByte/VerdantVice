package com.verdantvice.storage;

import com.verdantvice.common.BlockPosition;
import com.verdantvice.database.DatabaseManager;
import com.verdantvice.genetics.GeneticClass;
import com.verdantvice.genetics.GeneticProfile;
import com.verdantvice.harvest.HarvestTiming;
import com.verdantvice.plant.GrowthStage;
import com.verdantvice.plant.Plant;
import com.verdantvice.processing.BatchAuthenticityService;
import com.verdantvice.processing.ProductBatch;
import com.verdantvice.worker.WorkerHarvestDeliveryRepository;
import com.verdantvice.worker.WorkerReliabilityTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PlantRepositoryWorkerHarvestIntegrationTest {
    @TempDir
    Path root;

    private DatabaseManager database;
    private PlantRepository plants;
    private BatchService batches;
    private WorkerHarvestDeliveryRepository deliveries;
    private WorkerReliabilityTestSupport.ParentGraph graph;
    private byte[] secret;

    @BeforeEach
    void start() throws Exception {
        database = WorkerReliabilityTestSupport.start(root);
        graph = WorkerReliabilityTestSupport.insertParentGraph(database);
        plants = new PlantRepository(database);
        secret = "worker-harvest-integration-secret-32-bytes".getBytes();
        batches = new BatchService(
                new BatchRepository(database),
                new BatchAuthenticityService(),
                secret);
        deliveries = new WorkerHarvestDeliveryRepository(database);
    }

    @AfterEach
    void close() {
        if (database != null) database.close();
    }

    @Test
    void twoWorkersCannotHarvestTheSamePlantTwice() throws Exception {
        Fixture fixture = readyFixture();
        plants.create(fixture.plant(), fixture.genetics())
                .get(15, TimeUnit.SECONDS);
        ProductBatch batch = signedBatch(fixture);

        CompletableFuture<PlantRepository.HarvestedBatch> first =
                plants.harvestWithBatch(
                        fixture.plant().uniqueId(), batch, graph.worker(), graph.farm(),
                        graph.storage(), batches, deliveries);
        CompletableFuture<PlantRepository.HarvestedBatch> second =
                plants.harvestWithBatch(
                        fixture.plant().uniqueId(), batch, graph.worker(), graph.farm(),
                        graph.storage(), batches, deliveries);

        PlantRepository.HarvestedBatch harvested = first.get(15, TimeUnit.SECONDS);
        CompletionException failure = assertThrows(CompletionException.class, second::join);
        assertInstanceOf(ConcurrentPlantModificationException.class, failure.getCause());
        assertEquals(batch.batchId(), harvested.batch().batchId());
        assertEquals(1, rowCount("product_batches"));
        assertEquals(1, rowCount("worker_harvest_deliveries"));
        assertEquals(0, rowCount("plants"));
    }

    @Test
    void invalidDestinationRollsBackPlantAndBatchTogether() throws Exception {
        Fixture fixture = readyFixture();
        plants.create(fixture.plant(), fixture.genetics())
                .get(15, TimeUnit.SECONDS);
        ProductBatch batch = signedBatch(fixture);

        CompletionException failure = assertThrows(CompletionException.class, () ->
                plants.harvestWithBatch(
                        fixture.plant().uniqueId(), batch, graph.worker(), graph.farm(),
                        UUID.randomUUID(), batches, deliveries).join());
        assertTrue(failure.getCause() != null);
        assertEquals(1, rowCount("plants"));
        assertEquals(0, rowCount("product_batches"));
        assertEquals(0, rowCount("worker_harvest_deliveries"));
    }

    @Test
    void durablePendingDeliverySurvivesDatabaseRestartWithoutNewBatch()
            throws Exception {
        Fixture fixture = readyFixture();
        plants.create(fixture.plant(), fixture.genetics())
                .get(15, TimeUnit.SECONDS);
        ProductBatch batch = signedBatch(fixture);
        plants.harvestWithBatch(
                fixture.plant().uniqueId(), batch, graph.worker(), graph.farm(),
                graph.storage(), batches, deliveries).get(15, TimeUnit.SECONDS);

        database.close();
        database = new DatabaseManager(
                WorkerReliabilityTestSupport.config(root),
                WorkerReliabilityTestSupport.quietLogger());
        database.start().toCompletableFuture().get(15, TimeUnit.SECONDS);
        deliveries = new WorkerHarvestDeliveryRepository(database);

        assertEquals(1, deliveries.pending().get(15, TimeUnit.SECONDS).size());
        assertEquals(batch.batchId(), deliveries.pending()
                .get(15, TimeUnit.SECONDS).get(0).batchId());
        assertEquals(1, rowCount("product_batches"));
    }

    private int rowCount(String table) throws Exception {
        return database.read(session -> session.queryOne(
                "SELECT COUNT(*) AS total FROM " + table,
                result -> result.getInt("total")).orElse(0))
                .get(15, TimeUnit.SECONDS);
    }

    private ProductBatch signedBatch(Fixture fixture) {
        ProductBatch raw = ProductBatch.fresh(
                UUID.randomUUID(),
                graph.owner(),
                fixture.plant().strainId(),
                fixture.genetics().id(),
                12.5,
                78.0,
                0.0,
                HarvestTiming.OPTIMAL,
                Instant.now());
        return new BatchAuthenticityService().sign(raw, secret);
    }

    private Fixture readyFixture() {
        Instant now = Instant.parse("2026-07-29T12:00:00Z");
        UUID geneticsId = UUID.randomUUID();
        GeneticProfile genetics = new GeneticProfile(
                geneticsId, GeneticClass.COMMON,
                1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, now);
        Plant plant = new Plant(
                UUID.randomUUID(),
                graph.owner(),
                new BlockPosition(graph.world(), 3, 64, 3),
                "test_strain",
                geneticsId,
                GrowthStage.READY,
                100.0,
                100.0,
                80.0,
                80.0,
                15,
                0.0,
                90.0,
                Optional.empty(),
                0.0,
                60.0,
                65.0,
                75.0,
                now.minus(Duration.ofHours(12)),
                now,
                now.minus(Duration.ofMinutes(5)),
                now.minus(Duration.ofMinutes(1)),
                now.plus(Duration.ofHours(1)),
                Optional.of(graph.farm()),
                Optional.of(graph.worker()));
        return new Fixture(plant, genetics);
    }

    private record Fixture(Plant plant, GeneticProfile genetics) {
    }
}
