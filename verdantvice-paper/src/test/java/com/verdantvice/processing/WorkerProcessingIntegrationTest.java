package com.verdantvice.processing;

import com.verdantvice.database.DatabaseManager;
import com.verdantvice.database.SqlSession;
import com.verdantvice.database.item.CasStatus;
import com.verdantvice.database.item.ItemRegistration;
import com.verdantvice.database.item.ItemRegistryRecord;
import com.verdantvice.database.item.ItemRegistryRepository;
import com.verdantvice.database.item.ItemRegistryState;
import com.verdantvice.harvest.HarvestTiming;
import com.verdantvice.security.CustomItemType;
import com.verdantvice.security.ItemConsumptionGateway;
import com.verdantvice.security.ProcessingItemValidator;
import com.verdantvice.security.SecureItemService;
import com.verdantvice.security.SignedItemData;
import com.verdantvice.storage.BatchRepository;
import com.verdantvice.storage.BatchService;
import com.verdantvice.worker.WorkerReliabilityTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real temporary SQLite, real migrations, batch signatures and registry CAS, without Bukkit. */
final class WorkerProcessingIntegrationTest {
    private static final byte[] SECRET = "worker-processing-integration-signature-secret".getBytes(StandardCharsets.UTF_8);
    @TempDir Path root;
    private DatabaseManager database;
    private WorkerReliabilityTestSupport.ParentGraph graph;
    private BatchService batches;
    private ItemRegistryRepository registry;
    private ProcessingTransferService transfers;
    private ItemConsumptionGateway gateway;
    private final BatchAuthenticityService authenticity = new BatchAuthenticityService();

    @BeforeEach
    void start() throws Exception {
        database = WorkerReliabilityTestSupport.start(root);
        graph = WorkerReliabilityTestSupport.insertParentGraph(database);
        services();
    }

    private void services() {
        batches = new BatchService(new BatchRepository(database), authenticity, SECRET);
        registry = new ItemRegistryRepository(database);
        gateway = new RegistryGateway(registry);
        transfers = new ProcessingTransferService(database, batches, gateway, registry,
                WorkerProcessingIntegrationTest::hash);
    }

    @AfterEach
    void stop() {
        if (database != null) database.close();
    }

    @Test
    void workerLoadsFreshBatchIntoActualDryingJobAtomically() {
        ProductBatch fresh = freshBatch();
        batches.create(fresh).join();
        SecureItemService.ValidatedIdentity input = input(fresh, CustomItemType.FRESH_BATCH);
        UUID rack = UUID.randomUUID();
        DryingStartService drying = new DryingStartService(database, batches, gateway, authenticity, SECRET);
        DryingStartService.Result result = drying.startIdentity(input, graph.owner(), rack,
                Instant.now(), new DryingStartService.Parameters(Duration.ofMinutes(20), 95, 95, 1)).join();

        assertEquals(BatchState.DRYING, result.batch().state());
        assertEquals(ItemRegistryState.CONSUMED, registry.find(input.data().uniqueInstanceId()).join().orElseThrow().state());
        assertEquals(1, count("drying_jobs"));
        assertEquals(BatchState.DRYING, new BatchRepository(database).find(fresh.batchId()).join().orElseThrow().state());
        assertThrows(CompletionException.class, () -> drying.startIdentity(input, graph.owner(), rack,
                Instant.now(), new DryingStartService.Parameters(Duration.ofMinutes(20), 95, 95, 1)).join());
        assertEquals(1, count("drying_jobs"));
    }

    @Test
    void occupiedRackDoesNotConsumeSecondInput() {
        ProductBatch first = freshBatch();
        ProductBatch second = freshBatch();
        batches.create(first).join();
        batches.create(second).join();
        SecureItemService.ValidatedIdentity firstInput = input(first, CustomItemType.FRESH_BATCH);
        SecureItemService.ValidatedIdentity secondInput = input(second, CustomItemType.FRESH_BATCH);
        UUID rack = UUID.randomUUID();
        DryingStartService drying = new DryingStartService(database, batches, gateway, authenticity, SECRET);
        DryingStartService.Parameters parameters = new DryingStartService.Parameters(Duration.ofMinutes(20), 95, 95, 1);
        drying.startIdentity(firstInput, graph.owner(), rack, Instant.now(), parameters).join();
        assertThrows(CompletionException.class, () -> drying.startIdentity(secondInput, graph.owner(), rack,
                Instant.now(), parameters).join());
        assertEquals(ItemRegistryState.ACTIVE, registry.find(secondInput.data().uniqueInstanceId()).join().orElseThrow().state());
        assertEquals(BatchState.FRESH, new BatchRepository(database).find(second.batchId()).join().orElseThrow().state());
    }

    @Test
    void packagingCreatesRealSignedPackageAndConsumesSource() {
        ProductBatch batch = driedBatch();
        batches.create(batch).join();
        SecureItemService.ValidatedIdentity input = input(batch, CustomItemType.DRY_BATCH);
        ProcessingTransferService.Delivery delivery = packageInput(input, "package-one");

        assertEquals(CustomItemType.PACKAGE, delivery.data().itemType());
        assertEquals(batch.batchId(), delivery.data().batchId());
        assertEquals(batch.qualityScore(), delivery.data().quality());
        assertEquals(ItemRegistryState.ACTIVE, registry.find(delivery.data().uniqueInstanceId()).join().orElseThrow().state());
        assertEquals(ItemRegistryState.CONSUMED, registry.find(input.data().uniqueInstanceId()).join().orElseThrow().state());
        assertEquals(1, transfers.pending().join().size());
        assertEquals(batch, new BatchRepository(database).find(batch.batchId()).join().orElseThrow());
    }

    @Test
    void taskReplayReturnsSameIdentityWithoutConsumingOrIssuingAgain() {
        ProductBatch batch = driedBatch();
        batches.create(batch).join();
        SecureItemService.ValidatedIdentity input = input(batch, CustomItemType.DRY_BATCH);
        ProcessingTransferService.Delivery first = packageInput(input, "same-task");
        ProcessingTransferService.Delivery second = packageInput(input, "same-task");
        assertEquals(first, second);
        assertEquals(2, count("item_registry"));
        assertEquals(1, count("processing_deliveries"));
    }

    @Test
    void copiedSourceCannotBePackagedByAnotherTaskOrWorker() {
        ProductBatch batch = driedBatch();
        batches.create(batch).join();
        SecureItemService.ValidatedIdentity input = input(batch, CustomItemType.DRY_BATCH);
        packageInput(input, "first-worker");
        assertThrows(CompletionException.class, () -> packageInput(input, "second-worker"));
        assertEquals(1, count("processing_deliveries"));
        assertEquals(2, count("item_registry"));
    }

    @Test
    void missingOutputStorageLeavesSourceUntouched() {
        ProductBatch batch = driedBatch();
        batches.create(batch).join();
        SecureItemService.ValidatedIdentity input = input(batch, CustomItemType.DRY_BATCH);
        assertThrows(CompletionException.class, () -> transfers.packageBatch(input, graph.owner(),
                graph.farm(), UUID.randomUUID(), "invalid-storage", Instant.now()).join());
        assertEquals(ItemRegistryState.ACTIVE, registry.find(input.data().uniqueInstanceId()).join().orElseThrow().state());
        assertEquals(0, count("processing_deliveries"));
    }

    @Test
    void outputIdentityConflictRollsBackEarlierConsumption() {
        ProductBatch batch = driedBatch();
        batches.create(batch).join();
        SecureItemService.ValidatedIdentity input = input(batch, CustomItemType.DRY_BATCH);
        UUID conflict = UUID.nameUUIDFromBytes("processing-output:conflicting-task".getBytes(StandardCharsets.UTF_8));
        registry.register(new ItemRegistration(conflict, "SEED", "seed", null, graph.owner(), 0.0,
                "berry_fog", Instant.now(), "a".repeat(64), 1, null)).join();
        assertThrows(CompletionException.class, () -> packageInput(input, "conflicting-task"));
        assertEquals(ItemRegistryState.ACTIVE, registry.find(input.data().uniqueInstanceId()).join().orElseThrow().state());
        assertEquals(0, count("processing_deliveries"));
    }

    @Test
    void unloadingDeletesJobAndCreatesOneDurableDryOutput() {
        ProductBatch batch = driedBatch();
        batches.create(batch).join();
        UUID rack = dryingJob(batch);
        ProcessingTransferService.Delivery first = transfers.unloadDrying(rack, batch.batchId(), graph.owner(),
                graph.farm(), graph.storage(), "unload-task", Instant.now()).join();
        ProcessingTransferService.Delivery replay = transfers.unloadDrying(rack, batch.batchId(), graph.owner(),
                graph.farm(), graph.storage(), "unload-task", Instant.now()).join();
        assertEquals(first, replay);
        assertEquals(CustomItemType.DRY_BATCH, first.data().itemType());
        assertEquals(0, count("drying_jobs"));
        assertEquals(1, count("processing_deliveries"));
        assertEquals(1, count("item_registry"));
    }

    @Test
    void unloadingBeforeReadyLeavesJobAndRegistryUntouched() {
        ProductBatch batch = authenticity.sign(new DryingService().start(freshBatch(),
                Instant.now(), Duration.ofMinutes(20), 100, 100, 1), SECRET);
        batches.create(batch).join();
        UUID rack = dryingJob(batch);
        assertThrows(CompletionException.class, () -> transfers.unloadDrying(rack, batch.batchId(), graph.owner(),
                graph.farm(), graph.storage(), "too-early", Instant.now()).join());
        assertEquals(1, count("drying_jobs"));
        assertEquals(0, count("processing_deliveries"));
        assertEquals(0, count("item_registry"));
    }

    @Test
    void outboxSurvivesFullDatabaseRestartAndAcknowledgementIsIdempotent() throws Exception {
        ProductBatch batch = driedBatch();
        batches.create(batch).join();
        ProcessingTransferService.Delivery first = packageInput(input(batch, CustomItemType.DRY_BATCH), "restart-task");
        database.close();
        database = WorkerReliabilityTestSupport.start(root);
        services();
        ProcessingTransferService.Delivery recovered = transfers.pending().join().getFirst();
        assertEquals(first, recovered);
        transfers.delivered(first.operationId(), Instant.now()).join();
        transfers.delivered(first.operationId(), Instant.now()).join();
        assertTrue(transfers.pending().join().isEmpty());
        assertTrue(transfers.find(first.operationId()).join().orElseThrow().delivered());
        assertEquals(2, count("item_registry"));
    }

    private ProcessingTransferService.Delivery packageInput(SecureItemService.ValidatedIdentity input, String operation) {
        return transfers.packageBatch(input, graph.owner(), graph.farm(), graph.storage(), operation, Instant.now()).join();
    }

    private ProductBatch freshBatch() {
        return authenticity.sign(ProductBatch.fresh(UUID.randomUUID(), graph.owner(), "berry_fog", UUID.randomUUID(),
                40, 80, 0, HarvestTiming.PERFECT,
                Instant.ofEpochMilli(Instant.now().minusSeconds(3600).toEpochMilli())), SECRET);
    }

    private ProductBatch driedBatch() {
        Instant start = Instant.ofEpochMilli(Instant.now().minusSeconds(3600).toEpochMilli());
        ProductBatch drying = new DryingService().start(freshBatch(), start, Duration.ofMinutes(20), 100, 100, 1);
        return authenticity.sign(new DryingService().update(drying, start.plusSeconds(1200), true), SECRET);
    }

    private SecureItemService.ValidatedIdentity input(ProductBatch batch, CustomItemType type) {
        SignedItemData data = new SignedItemData(type, type.name().toLowerCase(), UUID.randomUUID(), batch.batchId(),
                graph.owner(), batch.qualityScore(), "berry_fog", Instant.now(), 1);
        String hash = hash(data);
        registry.register(new ItemRegistration(data.uniqueInstanceId(), type.name(), data.itemId(), batch.batchId(),
                graph.owner(), data.quality(), data.strain(), data.createdAt(), hash, 1, null)).join();
        return new SecureItemService.ValidatedIdentity(data, hash);
    }

    private UUID dryingJob(ProductBatch batch) {
        UUID rack = UUID.randomUUID();
        DryingData drying = batch.dryingData().orElseThrow();
        database.update("""
                INSERT INTO drying_jobs (drying_job_id, batch_id, owner_uuid, rack_id, drying_stage,
                  status, started_at, last_updated_at, completes_at, process_data_json, row_version)
                VALUES (?, ?, ?, ?, ?, 'READY', ?, ?, ?, '{}', 0)
                """, UUID.randomUUID(), batch.batchId(), graph.owner(), rack, drying.stage(),
                drying.startedAt(), drying.lastUpdatedAt(), drying.startedAt().plus(drying.targetDuration())).join();
        return rack;
    }

    private int count(String table) {
        return database.read(session -> session.queryOne("SELECT COUNT(*) FROM " + table,
                row -> row.getInt(1)).orElseThrow()).join();
    }

    private static String hash(SignedItemData data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(data.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new AssertionError(failure);
        }
    }

    private static final class RegistryGateway implements ItemConsumptionGateway {
        private final ItemRegistryRepository registry;
        private final ProcessingItemValidator validator = new ProcessingItemValidator();
        private RegistryGateway(ItemRegistryRepository registry) { this.registry = registry; }

        @Override
        public CompletableFuture<SecureItemService.ValidatedItem> validateActive(org.bukkit.inventory.ItemStack item,
                Set<CustomItemType> accepted, UUID actor) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("Tests use validated domain identities"));
        }

        @Override
        public SecureItemService.ConsumedItem consume(SqlSession session, SecureItemService.ValidatedIdentity validated,
                Set<CustomItemType> accepted, UUID actor, String operationId, Instant now) throws SQLException {
            validator.validateData(validated.data(), accepted, actor);
            ItemRegistryRecord record = registry.find(session, validated.data().uniqueInstanceId(), true).orElseThrow();
            validator.validateRecord(record, validated.data(), validated.payloadHash(), actor);
            UUID token = UUID.randomUUID();
            var reserved = registry.reserve(session, record.instanceId(), record.version(), validated.payloadHash(),
                    token, actor.toString(), now.plusSeconds(60), operationId, now);
            assertFalse(reserved.status() == CasStatus.REJECTED);
            var consumed = registry.consume(session, record.instanceId(), reserved.current().version(),
                    validated.payloadHash(), token, operationId, now);
            if (consumed.status() == CasStatus.REJECTED) throw new SQLException("Registry consumption rejected");
            return new SecureItemService.ConsumedItem(validated.data(), operationId);
        }
    }
}
