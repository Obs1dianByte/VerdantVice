package com.verdantvice.processing;

import com.verdantvice.common.DomainChecks;

import java.time.Duration;
import java.time.Instant;

public record DryingData(
        DryingStage stage,
        Instant startedAt,
        Instant lastUpdatedAt,
        Duration targetDuration,
        double rackCleanliness,
        double rackStability,
        double rackSpeed,
        double startingQuality,
        double startingContamination
) {
    public DryingData {
        DomainChecks.required(stage, "stage");
        DomainChecks.ordered(startedAt, lastUpdatedAt, "drying update");
        targetDuration = DomainChecks.positive(targetDuration, "targetDuration");
        rackCleanliness = DomainChecks.range(rackCleanliness, 0.0, 100.0, "rackCleanliness");
        rackStability = DomainChecks.range(rackStability, 0.0, 100.0, "rackStability");
        rackSpeed = DomainChecks.range(rackSpeed, 0.25, 4.0, "rackSpeed");
        startingQuality = DomainChecks.range(startingQuality, 0.0, 100.0, "startingQuality");
        startingContamination = DomainChecks.range(startingContamination, 0.0, 100.0,
                "startingContamination");
    }

    public double progressAt(Instant now) {
        DomainChecks.required(now, "now");
        if (now.isBefore(startedAt)) throw new IllegalArgumentException("now precedes drying start");
        return Duration.between(startedAt, now).toMillis() * rackSpeed / (double) targetDuration.toMillis();
    }
}
