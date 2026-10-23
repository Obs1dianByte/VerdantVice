package com.verdantvice.worker;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorkerSalaryAttemptPolicyTest {
    private final WorkerSalaryAttemptPolicy policy = new WorkerSalaryAttemptPolicy(
            Duration.ofSeconds(5), Duration.ofSeconds(40));
    private final Instant now = Instant.parse("2026-07-29T12:00:00Z");

    @Test
    void newDayIsEligibleOnce() {
        assertTrue(policy.shouldAttempt(9, 10, false, Instant.MIN, now));
    }

    @Test
    void processedDayIsNotEligibleAgain() {
        assertFalse(policy.shouldAttempt(10, 10, false, Instant.MIN, now));
    }

    @Test
    void inFlightOperationBlocksSecondTick() {
        assertFalse(policy.shouldAttempt(9, 10, true, Instant.MIN, now));
    }

    @Test
    void retryWaitsUntilBackoffExpires() {
        assertFalse(policy.shouldAttempt(9, 10, false, now.plusSeconds(1), now));
        assertTrue(policy.shouldAttempt(9, 10, false, now, now));
    }

    @Test
    void backoffIsExponentialAndBounded() {
        assertEquals(now.plusSeconds(5), policy.nextRetry(now, 1));
        assertEquals(now.plusSeconds(10), policy.nextRetry(now, 2));
        assertEquals(now.plusSeconds(40), policy.nextRetry(now, 10));
    }
}
