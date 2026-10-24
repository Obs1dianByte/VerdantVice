package com.verdantvice.worker;

import com.verdantvice.common.BlockPosition;
import com.verdantvice.database.DatabaseManager;
import com.verdantvice.genetics.GeneticClass;
import com.verdantvice.genetics.GeneticProfile;
import com.verdantvice.harvest.HarvestTiming;
import com.verdantvice.herbalist.HerbalistRecord;
import com.verdantvice.herbalist.HerbalistTier;
import com.verdantvice.plant.GrowthStage;
import com.verdantvice.plant.Plant;
import com.verdantvice.processing.BatchAuthenticityService;
import com.verdantvice.processing.ProductBatch;
import com.verdantvice.storage.BatchRepository;
import com.verdantvice.storage.BatchService;
import com.verdantvice.storage.PlantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorkerReliabilityDatabaseFlowIntegrationTest {
    @TempDir
    Path root;

    private DatabaseManager database;

    @AfterEach
    void close() {
        if (database != null) database.close();
    }

    @Test
    void hireSalaryHarvestAndRestartRemainSingleAndDurable() throws Exception {
        database = WorkerReliabilityTestSupport.start(root);
        Instant now = Instant.parse("2026-07-29T12:00:00Z");
        UUID farmOwner = UUID.randomUUID();
        UUID hiringOwner = UUID.randomUUID();
        UUID farmId = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();
        UUID herbalistId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        UUID storageId = UUID.randomUUID();
        database.transaction(session -> {
            session.update("INSERT INTO players (player_uuid, locale, created_at, updated_at, "
                            + "last_seen_at, row_version) VALUES (?, 'en_US', ?, ?, ?, 0)",
                    farmOwner, now, now, now);
            session.update("INSERT INTO farms (farm_id, owner_uuid, farm_name, world_uuid, "
                            + "table_x, table_y, table_z, created_at, updated_at, row_version) "
                            + "VALUES (?, ?, 'Runtime Flow', ?, 0, 64, 0, ?, ?, 0)",
                    farmId, farmOwner, worldId, now, now);
            session.update("""
                    INSERT INTO herbalists (
                      herbalist_uuid, entity_uuid, owner_uuid, world_uuid,
                      block_x, block_y, block_z, herbalist_level, experience,
                      reputation, offers_json, created_at, updated_at, row_version
                    ) VALUES (?, ?, NULL, ?, 1, 64, 1, 6, 220, 10, NULL, ?, ?, 0)
                    """,
                    herbalistId, entityId, worldId, now, now);
            session.update("""
                    INSERT INTO farm_storages (
                      storage_id, farm_id, storage_type, world_uuid,
                      block_x, block_y, block_z, created_at, updated_at, row_version
                    ) VALUES (?, ?, 'FRESH_HARVEST_STORAGE', ?, 2, 64, 2, ?, ?, 0)
                    """,
                    storageId, farmId, worldId, now, now);
            return null;
        }).get(15, TimeUnit.SECONDS);

        HerbalistRecord herbalist = new HerbalistRecord(
                herbalistId, entityId, null, worldId,
                1, 64, 1, HerbalistTier.VERDANT_EXPERT,
                220, 10, null, now, now, 0L);
        WorkerRepository workers = new WorkerRepository(database);
        WorkerRecord hired = workers.hireHerbalist(
                herbalist, hiringOwner, farmId, "Verdant Worker",
                WorkerSpecialization.HARVESTER, 4, 7,
                new WorkerSchedule(0, 1_000, 6_000, 7_000, 12_000, 16_000),
                new BlockPosition(worldId, 1, 64, 1),
                4, "pl_PL", now.plusSeconds(1))
                .get(15, TimeUnit.SECONDS);

        WorkerPaymentRepository payments = new WorkerPaymentRepository(database);
        WorkerPaymentRepository.PaymentRecord pending = payments.createPending(
                hired.worker().workerUuid(), hiringOwner, farmId,
                hired.worker().salaryPerMinecraftDay(), 7,
                now.plusSeconds(2)).get(15, TimeUnit.SECONDS);
        SalaryPayment salary = new SalaryService().processDay(hired.worker(), 7, 4);
        assertTrue(salary.paid());
        WorkerRecord paidWorker = workers.save(hired.withWorker(
                        salary.worker(), now.plusSeconds(3)))
                .get(15, TimeUnit.SECONDS);
        payments.markPaid(pending.paymentId(), now.plusSeconds(4))
                .get(15, TimeUnit.SECONDS);

        PlantRepository plants = new PlantRepository(database);
        GeneticProfile genetics = genetics(now);
        Plant plant = readyPlant(
                hiringOwner, farmId, hired.worker().workerUuid(),
                worldId, genetics.id(), now);
        plants.create(plant, genetics).get(15, TimeUnit.SECONDS);
        byte[] secret = "worker-reliability-flow-secret-32-bytes".getBytes();
        BatchService batches = new BatchService(
                new BatchRepository(database),
                new BatchAuthenticityService(),
                secret);
        WorkerHarvestDeliveryRepository deliveries =
                new WorkerHarvestDeliveryRepository(database);
        ProductBatch raw = ProductBatch.fresh(
                UUID.randomUUID(), hiringOwner, plant.strainId(), genetics.id(),
                12.5, 78.0, 0.0, HarvestTiming.OPTIMAL, now.plusSeconds(5));
        ProductBatch signed = new BatchAuthenticityService().sign(raw, secret);
        PlantRepository.HarvestedBatch harvest = plants.harvestWithBatch(
                        plant.uniqueId(), signed, hired.worker().workerUuid(),
                        farmId, storageId, batches, deliveries)
                .get(15, TimeUnit.SECONDS);
        deliveries.markDelivered(
                harvest.delivery().deliveryId(), now.plusSeconds(6))
                .get(15, TimeUnit.SECONDS);

        database.close();
        database = WorkerReliabilityTestSupport.start(root);
        workers = new WorkerRepository(database);
        payments = new WorkerPaymentRepository(database);
        plants = new PlantRepository(database);
        deliveries = new WorkerHarvestDeliveryRepository(database);

        WorkerRecord restarted = workers.find(paidWorker.worker().workerUuid())
                .get(15, TimeUnit.SECONDS).orElseThrow();
        assertEquals(7, restarted.worker().lastPaidMinecraftDay());
        assertEquals(WorkerPaymentRepository.PaymentStatus.PAID,
                payments.findForDay(restarted.worker().workerUuid(), 7)
                        .get(15, TimeUnit.SECONDS).orElseThrow().status());
        assertEquals(pending.paymentId(), payments.createPending(
                        restarted.worker().workerUuid(), hiringOwner, farmId,
                        4, 7, now.plusSeconds(7))
                .get(15, TimeUnit.SECONDS).paymentId());
        assertEquals(1, count("worker_payments"));
        assertEquals(1, count("product_batches"));
        assertEquals(1, count("worker_harvest_deliveries"));
        assertEquals(0, count("plants"));
        assertTrue(deliveries.pending().get(15, TimeUnit.SECONDS).isEmpty());

        PlantRepository restartedPlants = plants;
        WorkerHarvestDeliveryRepository restartedDeliveries = deliveries;
        CompletionException duplicateHarvest = assertThrows(
                CompletionException.class,
                () -> restartedPlants.harvestWithBatch(
                        plant.uniqueId(), signed, restarted.worker().workerUuid(),
                        farmId, storageId,
                        new BatchService(
                                new BatchRepository(database),
                                new BatchAuthenticityService(), secret),
                        restartedDeliveries).join());
        assertTrue(duplicateHarvest.getCause() != null);
        assertEquals(1, count("product_batches"));
        assertEquals(1, count("worker_harvest_deliveries"));
    }

    private int count(String table) throws Exception {
        return database.queryOne("SELECT COUNT(*) AS total FROM " + table,
                        result -> result.getInt("total"))
                .get(15, TimeUnit.SECONDS).orElse(0);
    }

    private static GeneticProfile genetics(Instant now) {
        return new GeneticProfile(
                UUID.randomUUID(), GeneticClass.COMMON,
                1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, now);
    }

    private static Plant readyPlant(
            UUID owner,
            UUID farm,
            UUID worker,
            UUID world,
            UUID genetics,
            Instant now
    ) {
        return new Plant(
                UUID.randomUUID(), owner,
                new BlockPosition(world, 3, 64, 3),
                "runtime_flow", genetics, GrowthStage.READY,
                100.0, 100.0, 80.0, 80.0, 15,
                0.0, 90.0, Optional.empty(), 0.0,
                60.0, 65.0, 75.0,
                now.minus(Duration.ofHours(12)), now,
                now.minus(Duration.ofMinutes(5)),
                now.minus(Duration.ofMinutes(1)),
                now.plus(Duration.ofHours(1)),
                Optional.of(farm), Optional.of(worker));
    }
}
