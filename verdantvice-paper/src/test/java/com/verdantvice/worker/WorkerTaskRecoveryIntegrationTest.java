package com.verdantvice.worker;

import com.verdantvice.common.BlockPosition;
import com.verdantvice.database.DatabaseManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

final class WorkerTaskRecoveryIntegrationTest {
    @TempDir
    Path root;

    private DatabaseManager database;
    private WorkerTaskRepository tasks;
    private WorkerReliabilityTestSupport.ParentGraph graph;

    @BeforeEach
    void start() throws Exception {
        database = WorkerReliabilityTestSupport.start(root);
        graph = WorkerReliabilityTestSupport.insertParentGraph(database);
        tasks = new WorkerTaskRepository(database);
    }

    @AfterEach
    void close() {
        if (database != null) database.close();
    }

    @Test
    void interruptedReservationIsCountedBeforeRecovery() throws Exception {
        WorkerTaskRepository.TaskLease lease = reservedTask();
        assertEquals(1, tasks.interruptedCount().get(15, TimeUnit.SECONDS));
        assertEquals(WorkerTaskStatus.RESERVED, lease.task().task().status());
    }

    @Test
    void restartRequeuesReservedTaskWithoutChangingIdentity() throws Exception {
        WorkerTaskRepository.TaskLease lease = reservedTask();
        UUID taskId = lease.task().task().taskId();
        String key = lease.task().idempotencyKey();
        assertEquals(1, tasks.recoverInterrupted(Instant.now())
                .get(15, TimeUnit.SECONDS));
        WorkerTaskRepository.StoredTask recovered = tasks.find(taskId)
                .get(15, TimeUnit.SECONDS).orElseThrow();
        assertEquals(WorkerTaskStatus.QUEUED, recovered.task().status());
        assertEquals(key, recovered.idempotencyKey());
        assertTrue(recovered.task().workerUuid().isEmpty());
        assertTrue(recovered.reservationToken().isEmpty());
    }

    @Test
    void restartRequeuesInProgressTaskExactlyOnce() throws Exception {
        WorkerTaskRepository.TaskLease started = tasks.start(
                reservedTask(), Instant.now()).get(15, TimeUnit.SECONDS);
        assertEquals(WorkerTaskStatus.IN_PROGRESS, started.task().task().status());
        assertEquals(1, tasks.recoverInterrupted(Instant.now())
                .get(15, TimeUnit.SECONDS));
        assertEquals(0, tasks.recoverInterrupted(Instant.now())
                .get(15, TimeUnit.SECONDS));
    }

    @Test
    void releaseForDeadWorkerLeavesTaskAvailableToAnotherWorker() throws Exception {
        WorkerTaskRepository.TaskLease lease = reservedTask();
        assertEquals(1, tasks.releaseForWorker(graph.worker(), Instant.now())
                .get(15, TimeUnit.SECONDS));
        Optional<WorkerTaskRepository.TaskLease> again = tasks.reserveNext(
                graph.farm(), graph.worker(), Set.of(WorkerTaskType.HARVEST),
                Instant.now(), Duration.ofMinutes(1)).get(15, TimeUnit.SECONDS);
        assertTrue(again.isPresent());
        assertEquals(lease.task().task().taskId(),
                again.orElseThrow().task().task().taskId());
    }

    private WorkerTaskRepository.TaskLease reservedTask() throws Exception {
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
                now.plusSeconds(600),
                3);
        tasks.createQueued(graph.farm(), task, "harvest:" + task.taskId())
                .get(15, TimeUnit.SECONDS);
        return tasks.reserveNext(
                graph.farm(), graph.worker(), Set.of(WorkerTaskType.HARVEST),
                now.plusMillis(1), Duration.ofMinutes(1))
                .get(15, TimeUnit.SECONDS).orElseThrow();
    }
}
