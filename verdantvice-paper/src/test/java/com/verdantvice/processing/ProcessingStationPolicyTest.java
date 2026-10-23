package com.verdantvice.processing;

import org.bukkit.event.block.Action;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProcessingStationPolicyTest {
    @Test
    void onlyRightClickOpensStationMenu() {
        assertTrue(ProcessingStationPolicy.opensMenu(Action.RIGHT_CLICK_BLOCK));
        assertFalse(ProcessingStationPolicy.opensMenu(Action.LEFT_CLICK_BLOCK));
        assertFalse(ProcessingStationPolicy.opensMenu(Action.PHYSICAL));
    }

    @Test
    void leftClickNeverOpensGuiEvenWhenStationIsActive() {
        assertFalse(ProcessingStationPolicy.opensMenu(Action.LEFT_CLICK_BLOCK));
        assertEquals(
                ProcessingStationPolicy.BreakDecision.BUSY,
                ProcessingStationPolicy.breakDecision(
                        ProcessingStationType.CURING_CONTAINER, true, false)
        );
    }

    @Test
    void everyIdleStationTypeCanBeBroken() {
        for (ProcessingStationType type : ProcessingStationType.values()) {
            assertEquals(
                    ProcessingStationPolicy.BreakDecision.ALLOW,
                    ProcessingStationPolicy.breakDecision(type, false, false),
                    type.name()
            );
        }
    }

    @Test
    void activeOrPendingStationIsBusy() {
        assertEquals(
                ProcessingStationPolicy.BreakDecision.BUSY,
                ProcessingStationPolicy.breakDecision(
                        ProcessingStationType.DRYING_RACK, true, false)
        );
        assertEquals(
                ProcessingStationPolicy.BreakDecision.BUSY,
                ProcessingStationPolicy.breakDecision(
                        ProcessingStationType.GRINDER, false, true)
        );
    }
}
