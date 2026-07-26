package com.verdantvice.worker;

import com.verdantvice.common.BlockPosition;
import com.verdantvice.test.TestFixtures;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorkerServicesTest {
    private final WorkerTaskService tasks = new WorkerTaskService();

    @Test
    void reservationPreventsTwoWorkersFromTakingOneTask() {
        WorkerTask task = task(80, 3);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        WorkerTask reserved = tasks.reserve(task, first, TestFixtures.NOW, Duration.ofMinutes(5));
        assertEquals(WorkerTaskStatus.RESERVED, reserved.status());
        assertThrows(IllegalStateException.class,
                () -> tasks.reserve(reserved, second, TestFixtures.NOW.plusSeconds(1),
                        Duration.ofMinutes(5)));

        WorkerTask reassigned = tasks.reserve(reserved, second,
                TestFixtures.NOW.plus(Duration.ofMinutes(6)), Duration.ofMinutes(5));
        assertEquals(second, reassigned.workerUuid().orElseThrow());
    }

    @Test
    void selectionIsStableAndUsesHighestPriorityFirst() {
        WorkerTask low = task(20, 3);
        WorkerTask high = new WorkerTask(UUID.randomUUID(), WorkerTaskType.SAVE_DYING_PLANTS, 100,
                target(), Optional.empty(), TestFixtures.NOW.plus(Duration.ofHours(1)),
                0, 3, WorkerTaskStatus.QUEUED, Optional.empty(), TestFixtures.NOW.plusSeconds(1));
        assertEquals(high.taskId(), tasks.selectNext(List.of(low, high), TestFixtures.NOW.plusSeconds(2))
                .orElseThrow().taskId());
    }

    @Test
    void repeatedPathFailuresEventuallyBlockTask() {
        WorkerTask task = task(80, 2);
        UUID worker = UUID.randomUUID();
        WorkerTask firstReservation = tasks.reserve(task, worker, TestFixtures.NOW,
                Duration.ofMinutes(5));
        WorkerTask firstFailure = tasks.failAttempt(firstReservation, worker,
                TestFixtures.NOW.plusSeconds(1));
        assertEquals(WorkerTaskStatus.QUEUED, firstFailure.status());
        WorkerTask secondReservation = tasks.reserve(firstFailure, worker,
                TestFixtures.NOW.plusSeconds(2), Duration.ofMinutes(5));
        WorkerTask blocked = tasks.failAttempt(secondReservation, worker,
                TestFixtures.NOW.plusSeconds(3));
        assertEquals(WorkerTaskStatus.BLOCKED, blocked.status());
        assertEquals(2, blocked.attempts());
    }

    @Test
    void unpaidDaysLowerMoraleAndEventuallyCauseRefusal() {
        Worker worker = Worker.hired(UUID.randomUUID(), TestFixtures.OWNER, UUID.randomUUID(),
                WorkerSpecialization.GROWER, 100);
        SalaryService salary = new SalaryService();
        Worker one = salary.processDay(worker, 101, 0).worker();
        Worker two = salary.processDay(one, 102, 0).worker();
        Worker three = salary.processDay(two, 103, 0).worker();
        assertEquals(3, three.unpaidDays());
        assertTrue(three.morale() < worker.morale());
        assertTrue(three.refusesNonCriticalTasks());
        assertEquals(WorkerState.WAITING_FOR_PAYMENT, three.state());
    }

    @Test
    void salaryIsTakenOncePerMinecraftDay() {
        Worker worker = Worker.hired(UUID.randomUUID(), TestFixtures.OWNER, UUID.randomUUID(),
                WorkerSpecialization.PACKAGER, 100);
        SalaryService salary = new SalaryService();
        SalaryPayment paid = salary.processDay(worker, 101, 10);
        assertTrue(paid.paid());
        assertEquals(4, paid.emeraldsTaken());
        assertEquals(6, paid.remainingEmeralds());
        SalaryPayment repeated = salary.processDay(paid.worker(), 101, 6);
        assertEquals(0, repeated.emeraldsTaken());
        assertEquals("not-due", repeated.resultCode());
    }

    @Test
    void moraleAndSpecializationAffectPerformance() {
        Worker worker = Worker.hired(UUID.randomUUID(), TestFixtures.OWNER, UUID.randomUUID(),
                WorkerSpecialization.HARVESTER, 100);
        double harvest = new WorkerProgressionService().taskEfficiency(worker, WorkerTaskType.HARVEST);
        double clean = new WorkerProgressionService().taskEfficiency(worker, WorkerTaskType.CLEAN);
        assertTrue(harvest > clean);
        Worker harmed = new MoraleService().applyDailyFactors(worker,
                new MoraleFactors(false, false, false, false, false,
                        30, 10, true, true, true, 4, true));
        assertTrue(harmed.morale() < worker.morale());
        assertTrue(harmed.workSpeedMultiplier() < worker.workSpeedMultiplier());
    }

    private static WorkerTask task(int priority, int maximumAttempts) {
        return WorkerTask.queued(UUID.randomUUID(), WorkerTaskType.WATER, priority, target(),
                TestFixtures.NOW, TestFixtures.NOW.plus(Duration.ofHours(1)), maximumAttempts);
    }

    private static TaskTarget target() {
        return new TaskTarget("plant", UUID.randomUUID(),
                Optional.of(new BlockPosition(TestFixtures.WORLD, 4, 64, 4)));
    }
}
