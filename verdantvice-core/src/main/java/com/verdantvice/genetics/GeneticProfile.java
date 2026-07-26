package com.verdantvice.genetics;

import com.verdantvice.common.DomainChecks;

import java.time.Instant;
import java.util.UUID;

public record GeneticProfile(
        UUID id,
        GeneticClass geneticClass,
        double yieldModifier,
        double growthSpeedModifier,
        double resistanceModifier,
        double qualityModifier,
        double smellModifier,
        double stabilityModifier,
        double negativeEffectModifier,
        Instant createdAt
) {
    public GeneticProfile {
        DomainChecks.required(id, "id");
        DomainChecks.required(geneticClass, "geneticClass");
        yieldModifier = modifier(yieldModifier, "yieldModifier");
        growthSpeedModifier = modifier(growthSpeedModifier, "growthSpeedModifier");
        resistanceModifier = modifier(resistanceModifier, "resistanceModifier");
        qualityModifier = modifier(qualityModifier, "qualityModifier");
        smellModifier = modifier(smellModifier, "smellModifier");
        stabilityModifier = modifier(stabilityModifier, "stabilityModifier");
        negativeEffectModifier = modifier(negativeEffectModifier, "negativeEffectModifier");
        DomainChecks.required(createdAt, "createdAt");
    }

    private static double modifier(double value, String name) {
        return DomainChecks.range(value, 0.25, 2.0, name);
    }

    public double beneficialAverage() {
        return (yieldModifier + growthSpeedModifier + resistanceModifier + qualityModifier
                + smellModifier + stabilityModifier + (2.0 - negativeEffectModifier)) / 7.0;
    }
}
