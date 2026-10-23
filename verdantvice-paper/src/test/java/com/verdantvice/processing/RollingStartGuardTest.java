package com.verdantvice.processing;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RollingStartGuardTest {
    @Test
    void twoFastStartsProduceOneLease() {
        RollingStartGuard guard = new RollingStartGuard();
        UUID player = UUID.randomUUID();

        RollingStartGuard.Lease first = guard.acquire(player, false).orElseThrow();
        assertTrue(guard.isPending(player));
        assertTrue(guard.acquire(player, false).isEmpty());

        first.close();
        first.close();
        assertFalse(guard.isPending(player));
        assertTrue(guard.acquire(player, false).isPresent());
    }

    @Test
    void clearReleasesPendingStartsAfterShutdownOrFailure() {
        RollingStartGuard guard = new RollingStartGuard();
        UUID player = UUID.randomUUID();
        guard.acquire(player, false).orElseThrow();
        assertTrue(guard.isPending(player));

        guard.clear();

        assertFalse(guard.isPending(player));
        assertTrue(guard.acquire(player, false).isPresent());
    }

    @Test
    void openSessionBlocksAnotherStart() {
        assertTrue(new RollingStartGuard().acquire(UUID.randomUUID(), true).isEmpty());
    }
}
