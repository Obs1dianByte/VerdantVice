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
import com.verdantvice.security.SecureItemService.ValidatedIdentity;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

/** Real migrations, SQLite transactions, signed batches and registry CAS; no Bukkit server mocks. */
final class ProcessingTransactionIntegrationTest {
    private static final byte[] SECRET = "processing-transaction-integration-secret".getBytes(StandardCharsets.UTF_8);
    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");
    @TempDir Path root;
    private DatabaseManager database;
    private UUID owner;
    private BatchService batches;
    private ItemRegistryRepository registry;
    private ProcessingTransactionService processing;
    private final BatchAuthenticityService authenticity = new BatchAuthenticityService();

    @BeforeEach
    void open() throws Exception {
        database = WorkerReliabilityTestSupport.start(root);
        owner = WorkerReliabilityTestSupport.insertParentGraph(database).owner();
        services();
    }

    private void services() {
        batches = new BatchService(new BatchRepository(database), authenticity, SECRET);
        registry = new ItemRegistryRepository(database);
        processing = new ProcessingTransactionService(database, batches, new RegistryGateway(registry), registry);
    }

    @AfterEach
    void close() { if (database != null) database.close(); }

    @Test
    void grindCommitsInputBatchOutputAndReceiptTogetherAndSurvivesRestart() throws Exception {
        ProductBatch source = batch(BatchState.DRIED);
        ValidatedIdentity input = input(CustomItemType.DRY_BATCH, source);
        ProductBatch ground = transition(source, BatchState.GROUND);
        ItemRegistration output = output(CustomItemType.GROUND_PRODUCT, ground);
        processing.commit(plan(List.of(input), List.of(change(source, ground)), null, output)).join();
        assertState(input, ItemRegistryState.CONSUMED);
        assertEquals(ground, stored(source));
        var receipts = processing.pending(owner).join();
        assertEquals(1, receipts.size());
        assertEquals(output.instanceId(), receipts.getFirst().data().uniqueInstanceId());
        assertEquals(List.of(input.data().uniqueInstanceId()), receipts.getFirst().inputs());
        assertEquals(ground, batches.cachedOrNull(source.batchId()));

        database.close();
        database = WorkerReliabilityTestSupport.start(root);
        services();
        assertEquals(receipts, processing.pending(owner).join(), "Restart must preserve the exact output identity");
        processing.acknowledge(UUID.randomUUID(), output.instanceId()).join();
        assertEquals(1, processing.pending(owner).join().size(), "Another player cannot acknowledge the result");
        processing.acknowledge(owner, output.instanceId()).join();
        processing.acknowledge(owner, output.instanceId()).join();
        assertTrue(processing.pending(owner).join().isEmpty());
        assertEquals(1, registry.find(output.instanceId()).join().orElseThrow().version());
        assertEquals(2, count("item_registry"));
    }

    @Test
    void secondMixInputFailureRollsBackFirstInputAndBothBatches() {
        ProductBatch first = batch(BatchState.GROUND);
        ProductBatch second = batch(BatchState.GROUND);
        ValidatedIdentity firstInput = input(CustomItemType.GROUND_PRODUCT, first);
        ValidatedIdentity secondInput = input(CustomItemType.GROUND_PRODUCT, second);
        ProductBatch mixed = signed(ProductBatch.fresh(UUID.randomUUID(), owner, "berry_fog", UUID.randomUUID(),
                20, 80, 0, HarvestTiming.PERFECT, NOW), BatchState.MIXED);
        ItemRegistration output = output(CustomItemType.MIXTURE, mixed);
        ValidatedIdentity invalid = new ValidatedIdentity(secondInput.data(), "f".repeat(64));
        assertThrows(CompletionException.class, () -> processing.commit(plan(List.of(firstInput, invalid),
                List.of(change(first, transition(first, BatchState.ROLLED)),
                        change(second, transition(second, BatchState.ROLLED))), mixed, output)).join());
        assertState(firstInput, ItemRegistryState.ACTIVE);
        assertState(secondInput, ItemRegistryState.ACTIVE);
        assertEquals(first, stored(first));
        assertEquals(second, stored(second));
        assertTrue(new BatchRepository(database).find(mixed.batchId()).join().isEmpty());
        assertTrue(registry.find(output.instanceId()).join().isEmpty());
    }

    @Test
    void outputWriteFailureRollsBackConsumptionBatchUpdatesAndNewMixedBatch() {
        ProductBatch first = batch(BatchState.GROUND);
        ProductBatch second = batch(BatchState.GROUND);
        ValidatedIdentity a = input(CustomItemType.GROUND_PRODUCT, first);
        ValidatedIdentity b = input(CustomItemType.GROUND_PRODUCT, second);
        ProductBatch mixed = batchValue(BatchState.MIXED);
        ItemRegistration output = output(CustomItemType.MIXTURE, mixed);
        rejectOutput(output);
        assertThrows(CompletionException.class, () -> processing.commit(plan(List.of(a, b),
                List.of(change(first, transition(first, BatchState.ROLLED)),
                        change(second, transition(second, BatchState.ROLLED))), mixed, output)).join());
        assertState(a, ItemRegistryState.ACTIVE);
        assertState(b, ItemRegistryState.ACTIVE);
        assertEquals(first, stored(first));
        assertEquals(second, stored(second));
        assertEquals(2, count("product_batches"));
        assertEquals(2, count("item_registry"));
        assertTrue(processing.pending(owner).join().isEmpty());
    }

    @Test
    void simultaneousGrindsHaveExactlyOneWinnerAndOneOutput() {
        ProductBatch source = batch(BatchState.DRIED);
        ValidatedIdentity input = input(CustomItemType.DRY_BATCH, source);
        ProductBatch ground = transition(source, BatchState.GROUND);
        List<CompletableFuture<Boolean>> attempts = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            attempts.add(processing.commit(plan(List.of(input), List.of(change(source, ground)), null,
                    output(CustomItemType.GROUND_PRODUCT, ground))).handle((ignored, failure) -> failure == null));
        }
        CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new)).join();
        assertEquals(1L, attempts.stream().filter(CompletableFuture::join).count());
        assertState(input, ItemRegistryState.CONSUMED);
        assertEquals(2, count("item_registry"));
        assertEquals(1, processing.pending(owner).join().size());
    }

    @Test
    void sameStateButChangedBatchSignatureRejectsStalePlanWithoutConsumption() {
        ProductBatch source = batch(BatchState.DRIED);
        ValidatedIdentity input = input(CustomItemType.DRY_BATCH, source);
        ProductBatch changed = authenticity.sign(source.transition(source.dryWeight(), 99,
                source.contamination(), source.freshness(), source.dryingData(), source.curingData(),
                source.state()), SECRET);
        batches.save(changed).join();
        ProductBatch ground = transition(source, BatchState.GROUND);
        assertThrows(CompletionException.class, () -> processing.commit(plan(List.of(input),
                List.of(change(source, ground)), null, output(CustomItemType.GROUND_PRODUCT, ground))).join());
        assertState(input, ItemRegistryState.ACTIVE);
        assertEquals(changed, stored(source));
        assertEquals(1, count("item_registry"));
    }

    @Test
    void curingJobInsertFailureRollsBackConsumedItemAndBatch() {
        ProductBatch source = batch(BatchState.DRIED);
        ValidatedIdentity input = input(CustomItemType.DRY_BATCH, source);
        ProductBatch started = authenticity.sign(new CuringService().start(source, NOW,
                Duration.ofMinutes(30), 95, 95), SECRET);
        database.update("CREATE TRIGGER fail_curing BEFORE INSERT ON curing_jobs "
                + "BEGIN SELECT RAISE(ABORT, 'injected curing insert failure'); END").join();
        var plan = new ProcessingTransactionService.Plan(owner, List.of(input), List.of(change(source, started)),
                null, null, new ProcessingTransactionService.CuringJob(UUID.randomUUID(), UUID.randomUUID()),
                "cure:test", NOW);
        assertThrows(CompletionException.class, () -> processing.commit(plan).join());
        assertState(input, ItemRegistryState.ACTIVE);
        assertEquals(source, stored(source));
        assertEquals(0, count("curing_jobs"));
    }

    @Test
    void occupiedCuringStationDoesNotConsumeAnotherBatch() {
        UUID station = UUID.randomUUID();
        ProductBatch first = batch(BatchState.DRIED);
        ProductBatch second = batch(BatchState.DRIED);
        ValidatedIdentity a = input(CustomItemType.DRY_BATCH, first);
        ValidatedIdentity b = input(CustomItemType.DRY_BATCH, second);
        processing.commit(curingPlan(first, a, station)).join();
        assertThrows(CompletionException.class, () -> processing.commit(curingPlan(second, b, station)).join());
        assertState(a, ItemRegistryState.CONSUMED);
        assertState(b, ItemRegistryState.ACTIVE);
        assertEquals(BatchState.CURING, stored(first).state());
        assertEquals(second, stored(second));
        assertEquals(1, count("curing_jobs"));
    }

    @Test
    void retrieveOutputFailureKeepsJobAndSuccessCreatesExactlyOneDurableReceipt() {
        ProductBatch source = batch(BatchState.DRIED);
        UUID jobId = UUID.randomUUID();
        UUID station = UUID.randomUUID();
        database.update("""
                INSERT INTO drying_jobs (drying_job_id, batch_id, owner_uuid, rack_id, drying_stage,
                  status, started_at, last_updated_at, completes_at, process_data_json, row_version)
                VALUES (?, ?, ?, ?, 'FINISHED', 'READY', ?, ?, ?, '{}', 0)
                """, jobId, source.batchId(), owner, station, NOW, NOW, NOW).join();
        var job = new ProcessingTransactionService.CompletedJob(jobId, station, source.batchId(),
                ProcessingStationType.DRYING_RACK);
        ItemRegistration rejected = output(CustomItemType.DRY_BATCH, source);
        rejectOutput(rejected);
        assertThrows(CompletionException.class, () -> processing.retrieve(plan(List.of(), List.of(), null, rejected), job).join());
        assertEquals(1, count("drying_jobs"));
        assertEquals(0, count("item_registry"));
        ItemRegistration accepted = output(CustomItemType.DRY_BATCH, source);
        processing.retrieve(plan(List.of(), List.of(), null, accepted), job).join();
        assertEquals(0, count("drying_jobs"));
        assertEquals(1, processing.pending(owner).join().size());
        assertThrows(CompletionException.class, () -> processing.retrieve(plan(List.of(), List.of(), null,
                output(CustomItemType.DRY_BATCH, source)), job).join());
        assertEquals(1, count("item_registry"));
    }

    @Test
    void interruptedRollingRecoversAllOriginalIdentitiesAfterRestartExactlyOnce() throws Exception {
        ProductBatch source = batch(BatchState.GROUND);
        List<ValidatedIdentity> inputs = rollingInputs(source);
        UUID session = UUID.randomUUID();
        processing.startRolling(owner, session, source, inputs.get(0), inputs.get(1), inputs.get(2), NOW).join();
        inputs.forEach(input -> assertState(input, ItemRegistryState.CONSUMED));
        assertTrue(processing.pending(owner).join().isEmpty());
        processing.restoreInterruptedRolling(owner, Set.of(session)).join();
        inputs.forEach(input -> assertState(input, ItemRegistryState.CONSUMED));

        database.close();
        database = WorkerReliabilityTestSupport.start(root);
        services();
        processing.restoreInterruptedRolling(owner, Set.of()).join();
        processing.restoreInterruptedRolling(owner, Set.of()).join();
        assertEquals(3, count("item_registry"), "Refund must reactivate identities rather than mint replacements");
        inputs.forEach(input -> assertState(input, ItemRegistryState.ACTIVE));
        Set<UUID> originals = Set.of(inputs.get(0).data().uniqueInstanceId(), inputs.get(1).data().uniqueInstanceId(),
                inputs.get(2).data().uniqueInstanceId());
        assertEquals(originals, processing.pending(owner).join().stream()
                .map(receipt -> receipt.data().uniqueInstanceId()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(inputs.stream().map(ValidatedIdentity::data).collect(java.util.stream.Collectors.toSet()),
                processing.pending(owner).join().stream().map(ProcessingTransactionService.PendingOutput::data)
                        .collect(java.util.stream.Collectors.toSet()),
                "Recovery must round-trip every signed field, including null SQL strain as the original empty string");
        assertEquals(2, processing.pending(owner).join().stream().filter(receipt -> receipt.data().batchId() == null).count());
        assertEquals(source, stored(source));
    }

    @Test
    void cancelledRollingCannotLaterFinishAndRefundIsIdempotent() {
        ProductBatch source = batch(BatchState.GROUND);
        List<ValidatedIdentity> inputs = rollingInputs(source);
        UUID session = UUID.randomUUID();
        processing.startRolling(owner, session, source, inputs.get(0), inputs.get(1), inputs.get(2), NOW).join();
        processing.restoreRolling(owner, session).join();
        processing.restoreRolling(owner, session).join();
        ProductBatch rolled = transition(source, BatchState.ROLLED);
        assertThrows(CompletionException.class, () -> processing.finishRolling(plan(List.of(),
                List.of(change(source, rolled)), null, output(CustomItemType.JOINT, rolled)), session).join());
        assertEquals(source, stored(source));
        assertEquals(3, processing.pending(owner).join().size());
        inputs.forEach(input -> assertState(input, ItemRegistryState.ACTIVE));
    }

    @Test
    void rollingCompletionAndCrashRecoveryCannotBothCreateOutputs() {
        ProductBatch source = batch(BatchState.GROUND);
        List<ValidatedIdentity> inputs = rollingInputs(source);
        UUID session = UUID.randomUUID();
        processing.startRolling(owner, session, source, inputs.get(0), inputs.get(1), inputs.get(2), NOW).join();
        ProductBatch rolled = transition(source, BatchState.ROLLED);
        ItemRegistration output = output(CustomItemType.JOINT, rolled);
        processing.finishRolling(plan(List.of(), List.of(change(source, rolled)), null, output), session).join();
        processing.restoreRolling(owner, session).join();
        processing.restoreInterruptedRolling(owner, Set.of()).join();
        inputs.forEach(input -> assertState(input, ItemRegistryState.CONSUMED));
        assertEquals(rolled, stored(source));
        var receipt = processing.pending(owner).join().getFirst();
        assertEquals(output.instanceId(), receipt.data().uniqueInstanceId());
        assertEquals(3, receipt.inputs().size());
        assertEquals(4, count("item_registry"));
        assertThrows(CompletionException.class, () -> processing.finishRolling(plan(List.of(),
                List.of(change(source, rolled)), null, output(CustomItemType.JOINT, rolled)), session).join());
    }

    @Test
    void failedRollingOutputKeepsRefundReceiptAndDoesNotChangeBatch() {
        ProductBatch source = batch(BatchState.GROUND);
        List<ValidatedIdentity> inputs = rollingInputs(source);
        UUID session = UUID.randomUUID();
        processing.startRolling(owner, session, source, inputs.get(0), inputs.get(1), inputs.get(2), NOW).join();
        ProductBatch rolled = transition(source, BatchState.ROLLED);
        ItemRegistration output = output(CustomItemType.JOINT, rolled);
        rejectOutput(output);
        assertThrows(CompletionException.class, () -> processing.finishRolling(plan(List.of(),
                List.of(change(source, rolled)), null, output), session).join());
        assertEquals(source, stored(source));
        assertTrue(registry.find(output.instanceId()).join().isEmpty());
        processing.restoreRolling(owner, session).join();
        inputs.forEach(input -> assertState(input, ItemRegistryState.ACTIVE));
        assertEquals(3, processing.pending(owner).join().size());
    }

    @Test
    void simultaneousRollingCancelAndFinishHaveOnlyOneDurableOutcome() {
        ProductBatch source = batch(BatchState.GROUND);
        List<ValidatedIdentity> inputs = rollingInputs(source);
        UUID session = UUID.randomUUID();
        processing.startRolling(owner, session, source, inputs.get(0), inputs.get(1), inputs.get(2), NOW).join();
        ProductBatch rolled = transition(source, BatchState.ROLLED);
        ItemRegistration output = output(CustomItemType.JOINT, rolled);
        CompletableFuture<Boolean> finished = processing.finishRolling(plan(List.of(),
                List.of(change(source, rolled)), null, output), session).handle((ignored, failure) -> failure == null);
        CompletableFuture<Void> cancelled = processing.restoreRolling(owner, session);
        CompletableFuture.allOf(finished, cancelled).join();
        if (finished.join()) {
            inputs.forEach(input -> assertState(input, ItemRegistryState.CONSUMED));
            assertEquals(rolled, stored(source));
            assertEquals(1, processing.pending(owner).join().size());
            assertEquals(4, count("item_registry"));
        } else {
            inputs.forEach(input -> assertState(input, ItemRegistryState.ACTIVE));
            assertEquals(source, stored(source));
            assertEquals(3, processing.pending(owner).join().size());
            assertEquals(3, count("item_registry"));
            assertTrue(registry.find(output.instanceId()).join().isEmpty());
        }
    }

    @Test
    void anotherOwnerCannotRecoverRollingInputs() {
        ProductBatch source = batch(BatchState.GROUND);
        List<ValidatedIdentity> inputs = rollingInputs(source);
        UUID session = UUID.randomUUID();
        processing.startRolling(owner, session, source, inputs.get(0), inputs.get(1), inputs.get(2), NOW).join();
        processing.restoreRolling(UUID.randomUUID(), session).join();
        inputs.forEach(input -> assertState(input, ItemRegistryState.CONSUMED));
        assertTrue(processing.pending(owner).join().isEmpty());
    }

    private ProductBatch batch(BatchState state) {
        ProductBatch batch = batchValue(state);
        batches.create(batch).join();
        return batch;
    }

    private ProductBatch batchValue(BatchState state) {
        ProductBatch fresh = ProductBatch.fresh(UUID.randomUUID(), owner, "berry_fog", UUID.randomUUID(),
                40, 80, 0, HarvestTiming.PERFECT, NOW);
        ProductBatch drying = new DryingService().start(fresh, NOW, Duration.ofMinutes(20), 100, 100, 1);
        ProductBatch dry = new DryingService().update(drying, NOW.plusSeconds(1200), true);
        return signed(dry, state);
    }

    private ProductBatch signed(ProductBatch source, BatchState state) {
        return authenticity.sign(source.transition(Math.max(1, source.dryWeight()), source.qualityScore(),
                source.contamination(), source.freshness(), source.dryingData(), source.curingData(), state), SECRET);
    }

    private ProductBatch transition(ProductBatch source, BatchState state) { return signed(source, state); }

    private ProductBatch stored(ProductBatch expected) {
        return new BatchRepository(database).find(expected.batchId()).join().orElseThrow();
    }

    private ProcessingTransactionService.Change change(ProductBatch before, ProductBatch after) {
        return new ProcessingTransactionService.Change(before, after);
    }

    private ProcessingTransactionService.Plan plan(List<ValidatedIdentity> inputs,
            List<ProcessingTransactionService.Change> changes, ProductBatch created, ItemRegistration output) {
        return new ProcessingTransactionService.Plan(owner, inputs, changes, created, output, null,
                "processing-test:" + UUID.randomUUID(), NOW);
    }

    private ProcessingTransactionService.Plan curingPlan(ProductBatch source, ValidatedIdentity input, UUID station) {
        ProductBatch started = authenticity.sign(new CuringService().start(source, NOW,
                Duration.ofMinutes(30), 95, 95), SECRET);
        return new ProcessingTransactionService.Plan(owner, List.of(input), List.of(change(source, started)),
                null, null, new ProcessingTransactionService.CuringJob(UUID.randomUUID(), station),
                "cure-test:" + UUID.randomUUID(), NOW);
    }

    private List<ValidatedIdentity> rollingInputs(ProductBatch batch) {
        return List.of(input(CustomItemType.ROLLING_PAPER, null), input(CustomItemType.FILTER, null),
                input(CustomItemType.GROUND_PRODUCT, batch));
    }

    private ValidatedIdentity input(CustomItemType type, ProductBatch batch) {
        SignedItemData data = data(type, batch);
        registry.register(registration(data)).join();
        return new ValidatedIdentity(data, hash(data));
    }

    private ItemRegistration output(CustomItemType type, ProductBatch batch) { return registration(data(type, batch)); }

    private SignedItemData data(CustomItemType type, ProductBatch batch) {
        return new SignedItemData(type, type.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'), UUID.randomUUID(),
                batch == null ? null : batch.batchId(), owner, batch == null ? 0 : batch.qualityScore(),
                batch == null ? "" : "berry_fog", NOW, 1);
    }

    private ItemRegistration registration(SignedItemData data) {
        return new ItemRegistration(data.uniqueInstanceId(), data.itemType().name(), data.itemId(), data.batchId(),
                owner, data.quality(), data.strain().isBlank() ? null : data.strain(), data.createdAt(), hash(data), 1, null);
    }

    private void rejectOutput(ItemRegistration output) {
        database.update("CREATE TRIGGER reject_processing_output BEFORE INSERT ON item_registry "
                + "WHEN NEW.instance_id='" + output.instanceId() + "' "
                + "BEGIN SELECT RAISE(ABORT, 'injected processing output failure'); END").join();
    }

    private void assertState(ValidatedIdentity input, ItemRegistryState expected) {
        assertEquals(expected, registry.find(input.data().uniqueInstanceId()).join().orElseThrow().state());
    }

    private int count(String table) {
        return database.read(session -> session.queryOne("SELECT COUNT(*) FROM " + table,
                row -> row.getInt(1)).orElseThrow()).join();
    }

    private static String hash(SignedItemData data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(data.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static final class RegistryGateway implements ItemConsumptionGateway {
        private final ItemRegistryRepository registry;
        private final ProcessingItemValidator validator = new ProcessingItemValidator();
        private RegistryGateway(ItemRegistryRepository registry) { this.registry = registry; }

        @Override
        public CompletableFuture<SecureItemService.ValidatedItem> validateActive(org.bukkit.inventory.ItemStack item,
                Set<CustomItemType> accepted, UUID actor) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("Domain identities are supplied directly"));
        }

        @Override
        public SecureItemService.ConsumedItem consume(SqlSession session, ValidatedIdentity input,
                Set<CustomItemType> accepted, UUID actor, String operation, Instant now) throws SQLException {
            validator.validateData(input.data(), accepted, actor);
            ItemRegistryRecord current = registry.find(session, input.data().uniqueInstanceId(), true).orElseThrow();
            validator.validateRecord(current, input.data(), input.payloadHash(), actor);
            UUID token = UUID.randomUUID();
            var reserved = registry.reserve(session, current.instanceId(), current.version(), input.payloadHash(),
                    token, actor.toString(), now.plusSeconds(60), operation, now);
            if (reserved.status() == CasStatus.REJECTED) throw new SQLException("Reservation rejected");
            var consumed = registry.consume(session, current.instanceId(), reserved.current().version(),
                    input.payloadHash(), token, operation, now);
            if (consumed.status() == CasStatus.REJECTED) throw new SQLException("Consumption rejected");
            return new SecureItemService.ConsumedItem(input.data(), operation);
        }
    }
}
