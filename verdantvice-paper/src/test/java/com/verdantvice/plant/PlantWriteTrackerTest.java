package com.verdantvice.plant;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlantWriteTrackerTest {
    @Test
    void simulationRemainsBlockedUntilEveryWriteCompletes() {
        PlantWriteTracker tracker = new PlantWriteTracker();
        UUID plantId = UUID.randomUUID();
        tracker.begin(plantId);
        tracker.begin(plantId);
        assertTrue(tracker.hasPending(plantId));
        tracker.end(plantId);
        assertTrue(tracker.hasPending(plantId));
        tracker.end(plantId);
        assertFalse(tracker.hasPending(plantId));
    }

    @Test
    void repeatedTickFailureIsLoggedOnceUntilSuccess() {
        PlantWriteTracker tracker = new PlantWriteTracker();
        UUID plantId = UUID.randomUUID();
        assertTrue(tracker.shouldLogFailure(plantId));
        assertFalse(tracker.shouldLogFailure(plantId));
        tracker.markSuccess(plantId);
        assertTrue(tracker.shouldLogFailure(plantId));
    }
}
