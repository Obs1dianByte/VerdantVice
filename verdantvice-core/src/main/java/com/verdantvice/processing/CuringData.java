package com.verdantvice.processing;

import com.verdantvice.common.DomainChecks;

import java.time.Duration;
import java.time.Instant;

public record CuringData(
        CuringStage stage,
        Instant startedAt,
        Instant lastUpdatedAt,
        Duration targetDuration,
        double containerCleanliness,
        double containerStability,
        double startingQuality,
        double startingContamination,
        double startingFreshness
) {
    public CuringData {
        DomainChecks.required(stage, "stage");
        DomainChecks.ordered(startedAt, lastUpdatedAt, "curing update");
        targetDuration = DomainChecks.positive(targetDuration, "targetDuration");
        containerCleanliness = DomainChecks.range(containerCleanliness, 0.0, 100.0,
                "containerCleanliness");
        containerStability = DomainChecks.range(containerStability, 0.0, 100.0, "containerStability");
        startingQuality = DomainChecks.range(startingQuality, 0.0, 100.0, "startingQuality");
        startingContamination = DomainChecks.range(startingContamination, 0.0, 100.0,
                "startingContamination");
        startingFreshness = DomainChecks.range(startingFreshness, 0.0, 100.0, "startingFreshness");
    }

    public double progressAt(Instant now) {
        DomainChecks.required(now, "now");
        if (now.isBefore(startedAt)) throw new IllegalArgumentException("now precedes curing start");
        return Duration.between(startedAt, now).toMillis() / (double) targetDuration.toMillis();
    }
}
