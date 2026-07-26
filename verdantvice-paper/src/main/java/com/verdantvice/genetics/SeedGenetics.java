package com.verdantvice.genetics;

import com.verdantvice.security.SignedItemData;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

public final class SeedGenetics {
    public static final String YIELD_MODIFIER = "yieldModifier";
    public static final String GROWTH_SPEED_MODIFIER = "growthSpeedModifier";
    public static final String RESISTANCE_MODIFIER = "resistanceModifier";
    public static final String QUALITY_MODIFIER = "qualityModifier";
    public static final String SMELL_MODIFIER = "smellModifier";
    public static final String STABILITY_MODIFIER = "stabilityModifier";
    public static final String NEGATIVE_EFFECT_MODIFIER = "negativeEffectModifier";

    private SeedGenetics() {
    }

    public static GeneticProfile baseProfile(GeneticsService service, SignedItemData seed) {
        long randomSeed = seed.uniqueInstanceId().getMostSignificantBits()
                ^ seed.uniqueInstanceId().getLeastSignificantBits();
        GeneticProfile generated = service.generate(
                new java.util.SplittableRandom(randomSeed),
                seed.createdAt()
        );
        return new GeneticProfile(
                profileId(seed),
                generated.geneticClass(),
                generated.yieldModifier(),
                generated.growthSpeedModifier(),
                generated.resistanceModifier(),
                generated.qualityModifier(),
                generated.smellModifier(),
                generated.stabilityModifier(),
                generated.negativeEffectModifier(),
                generated.createdAt()
        );
    }

    public static UUID profileId(SignedItemData seed) {
        return UUID.nameUUIDFromBytes(
                ("verdantvice:genetics:" + seed.uniqueInstanceId())
                        .getBytes(StandardCharsets.UTF_8)
        );
    }

    public static Map<String, Double> dominantTraits(GeneticProfile profile) {
        return Map.of(
                YIELD_MODIFIER, profile.yieldModifier(),
                GROWTH_SPEED_MODIFIER, profile.growthSpeedModifier(),
                RESISTANCE_MODIFIER, profile.resistanceModifier(),
                QUALITY_MODIFIER, profile.qualityModifier(),
                SMELL_MODIFIER, profile.smellModifier(),
                STABILITY_MODIFIER, profile.stabilityModifier(),
                NEGATIVE_EFFECT_MODIFIER, profile.negativeEffectModifier()
        );
    }
}
