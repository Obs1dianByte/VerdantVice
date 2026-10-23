package com.verdantvice.worker;

import com.verdantvice.worker.ai.WorkerStateMachine;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class WorkerStateMachineReliabilityTest {
    private final WorkerStateMachine machine = new WorkerStateMachine();

    @Test
    void waitingForPaymentDuringRestStaysExceptional() {
        assertEquals(Optional.empty(), machine.scheduledTransition(
                WorkerState.WAITING_FOR_PAYMENT,
                WorkerStateMachine.ScheduleDirective.REST,
                false,
                true,
                false));
    }

    @Test
    void blockedDuringRestStaysBlocked() {
        assertEquals(Optional.empty(), machine.scheduledTransition(
                WorkerState.BLOCKED,
                WorkerStateMachine.ScheduleDirective.REST,
                false,
                true,
                true));
    }

    @Test
    void paidWorkerCanReturnToSearchingDuringWork() {
        assertEquals(Optional.of(WorkerState.SEARCHING_TASK),
                machine.scheduledTransition(
                        WorkerState.WAITING_FOR_PAYMENT,
                        WorkerStateMachine.ScheduleDirective.WORK,
                        false,
                        true,
                        true));
    }

    @Test
    void unpaidWorkerCannotReturnToWork() {
        assertFalse(machine.scheduledTransition(
                WorkerState.WAITING_FOR_PAYMENT,
                WorkerStateMachine.ScheduleDirective.WORK,
                false,
                true,
                false).isPresent());
    }

    @Test
    void harvestWalksDirectlyToTargetWithoutToolStorage() {
        assertFalse(machine.needsStorage(WorkerTaskType.HARVEST));
        assertEquals(WorkerState.WALKING_TO_TARGET,
                machine.afterReservation(WorkerTaskType.HARVEST));
    }

    @Test
    void twoHundredNormalExceptionalTicksDoNotThrowOrChangeState() {
        for (int tick = 0; tick < 200; tick++) {
            assertEquals(Optional.empty(), machine.scheduledTransition(
                    WorkerState.WAITING_FOR_PAYMENT,
                    WorkerStateMachine.ScheduleDirective.REST,
                    false, true, false));
            assertEquals(Optional.empty(), machine.scheduledTransition(
                    WorkerState.BLOCKED,
                    WorkerStateMachine.ScheduleDirective.REST,
                    false, true, true));
        }
    }

    @Test
    void unrelatedIllegalTransitionStillFailsLoudly() {
        assertThrows(IllegalStateException.class, () -> machine.transition(
                WorkerState.HARVESTING,
                WorkerState.SLEEPING));
    }
}
