package com.verdantvice.strain;

import com.verdantvice.common.DomainChecks;
import com.verdantvice.effects.EffectProfile;

import java.time.Duration;

/**
 * Fully configuration-backed fictional plant variety.
 */
public record Strain(
        String id,
        String displayName,
        StrainRarity rarity,
        Duration growthDuration,
        double difficulty,
        double baseYield,
        double qualityPotential,
        double diseaseResistance,
        double waterConsumption,
        double lightPreference,
        double stressSensitivity,
        double smellIntensity,
        EffectProfile effectProfile,
        EffectProfile negativeEffectProfile,
        int seedPrice,
        double baseMarketPrice,
        String visualModel,
        String particleProfile
) {
    public Strain {
        id = DomainChecks.identifier(id, "id");
        displayName = DomainChecks.nonBlank(displayName, "displayName");
        rarity = DomainChecks.required(rarity, "rarity");
        growthDuration = DomainChecks.positive(growthDuration, "growthDuration");
        difficulty = DomainChecks.range(difficulty, 0.0, 100.0, "difficulty");
        baseYield = DomainChecks.positive(baseYield, "baseYield");
        qualityPotential = DomainChecks.range(qualityPotential, 0.0, 100.0, "qualityPotential");
        diseaseResistance = DomainChecks.range(diseaseResistance, 0.0, 100.0, "diseaseResistance");
        waterConsumption = DomainChecks.range(waterConsumption, 0.0, 10.0, "waterConsumption");
        lightPreference = DomainChecks.range(lightPreference, 0.0, 15.0, "lightPreference");
        stressSensitivity = DomainChecks.range(stressSensitivity, 0.0, 2.0, "stressSensitivity");
        smellIntensity = DomainChecks.range(smellIntensity, 0.0, 100.0, "smellIntensity");
        effectProfile = DomainChecks.required(effectProfile, "effectProfile");
        negativeEffectProfile = DomainChecks.required(negativeEffectProfile, "negativeEffectProfile");
        DomainChecks.range(seedPrice, 0, 1_000_000, "seedPrice");
        baseMarketPrice = DomainChecks.positive(baseMarketPrice, "baseMarketPrice");
        visualModel = DomainChecks.nonBlank(visualModel, "visualModel");
        particleProfile = DomainChecks.nonBlank(particleProfile, "particleProfile");
    }
}
