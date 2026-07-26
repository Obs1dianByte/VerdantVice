package com.verdantvice.processing;

import com.verdantvice.common.DomainChecks;

public record QualityInputs(
        double genetics,
        double plantCare,
        double health,
        double harvestTiming,
        double cleanliness,
        double drying,
        double curing,
        double playerSkill,
        double workerSkill,
        double contamination
) {
    public QualityInputs {
        genetics = score(genetics, "genetics");
        plantCare = score(plantCare, "plantCare");
        health = score(health, "health");
        harvestTiming = score(harvestTiming, "harvestTiming");
        cleanliness = score(cleanliness, "cleanliness");
        drying = score(drying, "drying");
        curing = score(curing, "curing");
        playerSkill = score(playerSkill, "playerSkill");
        workerSkill = score(workerSkill, "workerSkill");
        contamination = score(contamination, "contamination");
    }

    private static double score(double value, String name) {
        return DomainChecks.range(value, 0.0, 100.0, name);
    }
}
