package com.verdantvice.processing;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StationBreakGuardTest {
    @Test
    void oneStationCanBeClaimedOnlyOnceUntilReleased() {
        StationBreakGuard<UUID> guard = new StationBreakGuard<>();
        UUID station = UUID.randomUUID();

        StationBreakGuard.Lease<UUID> first = guard.acquire(station, false).orElseThrow();
        assertTrue(guard.isClaimed(station));
        assertTrue(guard.acquire(station, false).isEmpty());

        first.close();
        first.close();
        assertFalse(guard.isClaimed(station));
        assertTrue(guard.acquire(station, false).isPresent());
    }

    @Test
    void clearReleasesEveryPendingStationClaim() {
        StationBreakGuard<UUID> guard = new StationBreakGuard<>();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        guard.acquire(first, false).orElseThrow();
        guard.acquire(second, false).orElseThrow();

        guard.clear();

        assertFalse(guard.isClaimed(first));
        assertFalse(guard.isClaimed(second));
    }

    @Test
    void activeStationCannotBeClaimed() {
        StationBreakGuard<UUID> guard = new StationBreakGuard<>();
        assertTrue(guard.acquire(UUID.randomUUID(), true).isEmpty());
    }
}
