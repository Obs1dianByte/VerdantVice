package com.verdantvice.worker;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Deterministic single-flight and bounded-backoff policy for salary ticks. */
public final class WorkerSalaryAttemptPolicy {
    private final Duration initialBackoff;
    private final Duration maximumBackoff;

    public WorkerSalaryAttemptPolicy(Duration initialBackoff, Duration maximumBackoff) {
        this.initialBackoff = positive(initialBackoff, "initialBackoff");
        this.maximumBackoff = positive(maximumBackoff, "maximumBackoff");
        if (maximumBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("maximumBackoff must not be shorter than initialBackoff");
        }
    }

    public boolean shouldAttempt(long lastProcessedDay, long requestedDay,
            boolean inFlight, Instant retryAt, Instant now) {
        Objects.requireNonNull(retryAt, "retryAt");
        Objects.requireNonNull(now, "now");
        return requestedDay > lastProcessedDay && !inFlight && !now.isBefore(retryAt);
    }

    public Instant nextRetry(Instant now, int consecutiveFailures) {
        Objects.requireNonNull(now, "now");
        int failures = Math.max(1, consecutiveFailures);
        int shift = Math.min(20, failures - 1);
        long multiplier = 1L << shift;
        long millis;
        try {
            millis = Math.multiplyExact(initialBackoff.toMillis(), multiplier);
        } catch (ArithmeticException overflow) {
            millis = maximumBackoff.toMillis();
        }
        return now.plusMillis(Math.min(maximumBackoff.toMillis(), millis));
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
