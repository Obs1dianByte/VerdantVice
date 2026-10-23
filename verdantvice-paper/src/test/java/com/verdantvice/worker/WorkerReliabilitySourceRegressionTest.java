package com.verdantvice.worker;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorkerReliabilitySourceRegressionTest {
    private static final Path ROOT = Path.of("src/main/java/com/verdantvice");

    @Test
    void runtimeIsAddedOnlyAfterRepositoryHireCompletes() throws Exception {
        String source = read(ROOT.resolve("worker/WorkerManager.java"));
        source = source.substring(source.indexOf("public CompletableFuture<HireResult> hire("));
        int persisted = source.indexOf(".thenCompose(record -> onMainSupply(() -> {");
        int activated = source.indexOf("runtimes.put(record.worker().workerUuid(), runtime)");
        assertTrue(persisted >= 0 && activated > persisted);
    }

    @Test
    void salaryDayIsUpdatedOnlyInsideSuccessfulCompletionBranch() throws Exception {
        String source = read(ROOT.resolve("worker/WorkerManager.java"));
        int completion = source.indexOf(".whenComplete((outcome, failure)");
        int successGuard = source.indexOf("if (failure != null)", completion);
        int update = source.indexOf("runtime.lastSalaryProcessedDay =", successGuard);
        assertTrue(completion >= 0 && successGuard > completion && update > successGuard);
        assertFalse(source.substring(0, completion)
                .contains("runtime.lastSalaryProcessedDay = day"));
    }

    @Test
    void salaryUsesSingleFlightAndBoundedRetry() throws Exception {
        String source = read(ROOT.resolve("worker/WorkerManager.java"));
        assertTrue(source.contains("runtime.salaryInFlight"));
        assertTrue(source.contains("salaryAttempts.nextRetry"));
        assertTrue(source.contains("salary.retry-maximum-seconds"));
    }

    @Test
    void harvestDoesNotRequireToolStorage() throws Exception {
        String source = read(ROOT.resolve("worker/ai/WorkerStateMachine.java"));
        assertTrue(source.contains("case INSPECT, HARVEST -> false"));
    }

    @Test
    void workerHarvestRequiresExactlyReadyStage() throws Exception {
        String action = read(ROOT.resolve("worker/ai/WorkerActionService.java"));
        String plantManager = read(ROOT.resolve("plant/PlantManager.java"));
        String workerManager = read(ROOT.resolve("worker/WorkerManager.java"));
        assertTrue(action.contains("plant.growthStage() == GrowthStage.READY"));
        assertTrue(plantManager.contains("plant.growthStage() != GrowthStage.READY"));
        assertTrue(workerManager.contains(
                "return plant.growthStage() == GrowthStage.READY;"));
    }

    @Test
    void harvestCreatesBatchAndDeliveryInOneTransaction() throws Exception {
        String repository = read(ROOT.resolve("storage/PlantRepository.java"));
        int transaction = repository.indexOf("return database.transaction(session -> {");
        int delete = repository.indexOf("DELETE FROM plants", transaction);
        int batch = repository.indexOf("batches.create(session, batch, now)", delete);
        int delivery = repository.indexOf("deliveries.create(", batch);
        assertTrue(transaction >= 0 && delete > transaction
                && batch > delete && delivery > batch);
    }

    @Test
    void restartRecoveryUsesDurablePendingDeliveries() throws Exception {
        String source = read(ROOT.resolve("worker/ai/WorkerActionService.java"));
        assertTrue(source.contains("deliveries.pending()"));
        assertTrue(source.contains("containsInstance(inventory, delivery.itemInstanceId())"));
        assertTrue(source.contains("deliveries.markDelivered"));
    }

    @Test
    void pendingHarvestDeliveriesAreRecoveredImmediatelyOnStart() throws Exception {
        String source = read(ROOT.resolve("worker/WorkerManager.java"));
        int start = source.indexOf("public void start()");
        int recovery = source.indexOf("actions.recoverPendingDeliveries();", start);
        int schedule = source.indexOf("runTaskTimer", start);
        assertTrue(start >= 0 && recovery > start && schedule > recovery);
    }

    @Test
    void databaseConstraintsAreNeverDisabled() throws Exception {
        String database = read(ROOT.resolve("database/DatabaseManager.java"));
        String migration = read(ROOT.resolve("database/migration/MigrationCatalog.java"));
        assertFalse(database.contains("foreign_keys=OFF"));
        assertFalse(migration.contains("foreign_keys=OFF"));
        assertTrue(migration.contains("fk_payments_worker"));
        assertTrue(migration.contains("fk_payments_payer"));
    }

    @Test
    void exceptionalStatesAreNotOverwrittenByRestDirective() throws Exception {
        String source = read(ROOT.resolve("worker/ai/WorkerStateMachine.java"));
        assertTrue(source.contains("current == WorkerState.WAITING_FOR_PAYMENT"));
        assertTrue(source.contains("current == WorkerState.BLOCKED"));
        assertFalse(source.contains("allow(values, WorkerState.WAITING_FOR_PAYMENT,\n"
                + "                WorkerState.RESTING"));
    }

    private static String read(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }
}
