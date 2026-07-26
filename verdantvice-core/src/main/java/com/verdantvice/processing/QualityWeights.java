package com.verdantvice.processing;

import com.verdantvice.common.DomainChecks;

public record QualityWeights(
        double genetics,
        double plantCare,
        double health,
        double harvestTiming,
        double cleanliness,
        double drying,
        double curing,
        double playerSkill,
        double workerSkill,
        double contaminationPenalty
) {
    public QualityWeights {
        genetics = weight(genetics, "genetics");
        plantCare = weight(plantCare, "plantCare");
        health = weight(health, "health");
        harvestTiming = weight(harvestTiming, "harvestTiming");
        cleanliness = weight(cleanliness, "cleanliness");
        drying = weight(drying, "drying");
        curing = weight(curing, "curing");
        playerSkill = weight(playerSkill, "playerSkill");
        workerSkill = weight(workerSkill, "workerSkill");
        contaminationPenalty = DomainChecks.range(contaminationPenalty, 0.0, 2.0,
                "contaminationPenalty");
        double total = genetics + plantCare + health + harvestTiming + cleanliness + drying + curing
                + playerSkill + workerSkill;
        if (total <= 0.0) {
            throw new IllegalArgumentException("At least one positive quality weight is required");
        }
    }

    public static QualityWeights defaults() {
        return new QualityWeights(0.18, 0.15, 0.13, 0.14, 0.08, 0.12, 0.08, 0.06, 0.06, 0.35);
    }

    public double positiveWeightTotal() {
        return genetics + plantCare + health + harvestTiming + cleanliness + drying + curing
                + playerSkill + workerSkill;
    }

    private static double weight(double value, String name) {
        return DomainChecks.range(value, 0.0, 1.0, name);
    }
}
