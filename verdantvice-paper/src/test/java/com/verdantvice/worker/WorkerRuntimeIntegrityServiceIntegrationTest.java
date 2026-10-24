package com.verdantvice.worker;

import com.verdantvice.common.BlockPosition;
import com.verdantvice.database.DatabaseManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorkerRuntimeIntegrityServiceIntegrationTest {
    @TempDir
    Path root;

    private DatabaseManager database;
    private WorkerTaskRepository tasks;
    private WorkerRuntimeIntegrityService integrity;
    private WorkerReliabilityTestSupport.ParentGraph graph;

    @BeforeEach
    void start() throws Exception {
        database = WorkerReliabilityTestSupport.start(root);
        graph = WorkerReliabilityTestSupport.insertParentGraph(database);
        tasks = new WorkerTaskRepository(database);
        integrity = new WorkerRuntimeIntegrityService(
                database, tasks, WorkerReliabilityTestSupport.quietLogger());
    }

    @AfterEach
    void close() {
        if (database != null) database.close();
    }

    @Test
    void startupBacksUpThenClearsWorkerPointerAndRequeuesTask() throws Exception {
        WorkerTaskRepository.TaskLease lease = reserveAndStartTask();
        UUID taskId = lease.task().task().taskId();
        database.update("""
                        UPDATE workers SET current_task_id=?, worker_state='HARVESTING',
                          row_version=row_version+1 WHERE worker_uuid=?
                        """,
                taskId, graph.worker()).get(15, TimeUnit.SECONDS);

        WorkerRuntimeIntegrityService.IntegrityReport report =
                integrity.migrate("en_US").get(15, TimeUnit.SECONDS);

        assertTrue(report.backup().isPresent());
        assertTrue(Files.isRegularFile(report.backup().orElseThrow()));
        assertEquals(1, report.clearedTaskPointers());
        assertEquals(1, report.releasedInterruptedTasks());
        assertTrue(report.disabledWorkerIds().isEmpty());
        WorkerRecord worker = new WorkerRepository(database).find(graph.worker())
                .get(15, TimeUnit.SECONDS).orElseThrow();
        assertTrue(worker.worker().activeTaskId().isEmpty());
        assertEquals(WorkerState.IDLE, worker.worker().state());
        WorkerTaskRepository.StoredTask recovered = tasks.find(taskId)
                .get(15, TimeUnit.SECONDS).orElseThrow();
        assertEquals(WorkerTaskStatus.QUEUED, recovered.task().status());
        assertTrue(recovered.task().workerUuid().isEmpty());
        assertTrue(recovered.reservationToken().isEmpty());
    }

    @Test
    void secondIntegrityPassIsIdempotentAndDoesNotCreateAnotherBackup()
            throws Exception {
        WorkerTaskRepository.TaskLease lease = reserveAndStartTask();
        database.update("UPDATE workers SET current_task_id=?, worker_state='HARVESTING', "
                        + "row_version=row_version+1 WHERE worker_uuid=?",
                lease.task().task().taskId(), graph.worker())
                .get(15, TimeUnit.SECONDS);
        integrity.migrate("en_US").get(15, TimeUnit.SECONDS);

        WorkerRuntimeIntegrityService.IntegrityReport second =
                integrity.migrate("en_US").get(15, TimeUnit.SECONDS);
        assertEquals(0, second.clearedTaskPointers());
        assertEquals(0, second.releasedInterruptedTasks());
        assertEquals(0, second.repairedPlayerParents());
        assertTrue(second.disabledWorkerIds().isEmpty());
        assertFalse(second.backup().isPresent());
    }

    @Test
    void cleanRuntimeNeedsNoMutationOrBackup() throws Exception {
        WorkerRuntimeIntegrityService.IntegrityReport report =
                integrity.migrate("pl_PL").get(15, TimeUnit.SECONDS);
        assertEquals(0, report.clearedTaskPointers());
        assertEquals(0, report.releasedInterruptedTasks());
        assertEquals(0, report.repairedPlayerParents());
        assertTrue(report.disabledWorkerIds().isEmpty());
        assertTrue(report.backup().isEmpty());
    }

    private WorkerTaskRepository.TaskLease reserveAndStartTask() throws Exception {
        Instant now = Instant.now();
        WorkerTask task = WorkerTask.queued(
                UUID.randomUUID(),
                WorkerTaskType.HARVEST,
                80,
                new TaskTarget(
                        "plant",
                        UUID.randomUUID(),
                        Optional.of(new BlockPosition(graph.world(), 2, 64, 2))),
                now,
                now.plus(Duration.ofMinutes(10)),
                3);
        tasks.createQueued(graph.farm(), task, "integrity:" + task.taskId())
                .get(15, TimeUnit.SECONDS);
        WorkerTaskRepository.TaskLease reserved = tasks.reserveNext(
                        graph.farm(), graph.worker(), Set.of(WorkerTaskType.HARVEST),
                        now.plusMillis(1), Duration.ofMinutes(1))
                .get(15, TimeUnit.SECONDS).orElseThrow();
        return tasks.start(reserved, now.plusMillis(2))
                .get(15, TimeUnit.SECONDS);
    }
}
