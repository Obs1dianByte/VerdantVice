package com.verdantvice.genetics;

import com.verdantvice.common.DomainChecks;

import java.time.Instant;
import java.util.UUID;
import java.util.random.RandomGenerator;

public final class GeneticsService {
    private static final double MUTATION_RANGE = 0.08;

    public GeneticProfile generate(RandomGenerator random, Instant now) {
        DomainChecks.required(random, "random");
        DomainChecks.required(now, "now");
        double yield = trait(random);
        double growth = trait(random);
        double resistance = trait(random);
        double quality = trait(random);
        double smell = trait(random);
        double stability = trait(random);
        double negative = DomainChecks.clamp(2.0 - trait(random), 0.55, 1.45);
        return create(UUID.randomUUID(), yield, growth, resistance, quality, smell, stability, negative, now);
    }

    public GeneticProfile cross(UUID id, GeneticProfile first, GeneticProfile second,
                                RandomGenerator random, Instant now) {
        DomainChecks.required(id, "id");
        DomainChecks.required(first, "first");
        DomainChecks.required(second, "second");
        DomainChecks.required(random, "random");
        DomainChecks.required(now, "now");
        return create(
                id,
                inherit(first.yieldModifier(), second.yieldModifier(), random),
                inherit(first.growthSpeedModifier(), second.growthSpeedModifier(), random),
                inherit(first.resistanceModifier(), second.resistanceModifier(), random),
                inherit(first.qualityModifier(), second.qualityModifier(), random),
                inherit(first.smellModifier(), second.smellModifier(), random),
                inherit(first.stabilityModifier(), second.stabilityModifier(), random),
                inherit(first.negativeEffectModifier(), second.negativeEffectModifier(), random),
                now);
    }

    public GeneticProfile create(UUID id, double yield, double growth, double resistance, double quality,
                                 double smell, double stability, double negative, Instant now) {
        double average = (yield + growth + resistance + quality + smell + stability + (2.0 - negative)) / 7.0;
        return new GeneticProfile(id, classify(average), yield, growth, resistance, quality, smell,
                stability, negative, now);
    }

    public GeneticClass classify(double beneficialAverage) {
        DomainChecks.range(beneficialAverage, 0.0, 2.0, "beneficialAverage");
        if (beneficialAverage < 0.65) return GeneticClass.DAMAGED;
        if (beneficialAverage < 0.82) return GeneticClass.POOR;
        if (beneficialAverage < 1.03) return GeneticClass.COMMON;
        if (beneficialAverage < 1.16) return GeneticClass.GOOD;
        if (beneficialAverage < 1.29) return GeneticClass.EXCEPTIONAL;
        if (beneficialAverage < 1.42) return GeneticClass.PERFECT;
        return GeneticClass.LEGENDARY;
    }

    private static double trait(RandomGenerator random) {
        // Triangular distribution keeps ordinary seeds common while retaining meaningful extremes.
        return 0.55 + ((random.nextDouble() + random.nextDouble()) / 2.0) * 0.9;
    }

    private static double inherit(double first, double second, RandomGenerator random) {
        double dominantWeight = random.nextDouble(0.35, 0.65);
        double mutation = random.nextDouble(-MUTATION_RANGE, MUTATION_RANGE);
        return DomainChecks.clamp(first * dominantWeight + second * (1.0 - dominantWeight) + mutation,
                0.25, 2.0);
    }
}
