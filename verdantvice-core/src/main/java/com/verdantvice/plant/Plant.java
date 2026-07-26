package com.verdantvice.plant;

import com.verdantvice.common.BlockPosition;
import com.verdantvice.common.DomainChecks;
import com.verdantvice.genetics.GeneticProfile;
import com.verdantvice.strain.Strain;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable persisted plant aggregate. Mutation services always return a new value.
 */
public record Plant(
        UUID uniqueId,
        UUID ownerUuid,
        BlockPosition position,
        String strainId,
        UUID geneticProfileId,
        GrowthStage growthStage,
        double growthProgress,
        double health,
        double hydration,
        double nutrition,
        int lightLevel,
        double stress,
        double cleanliness,
        Optional<String> diseaseId,
        double pestLevel,
        double resistance,
        double yieldPotential,
        double qualityPotential,
        Instant plantedAt,
        Instant lastUpdatedAt,
        Instant lastWateredAt,
        Instant harvestWindowStart,
        Instant harvestWindowEnd,
        Optional<UUID> farmId,
        Optional<UUID> workerAssigned
) {
    public Plant {
        DomainChecks.required(uniqueId, "uniqueId");
        DomainChecks.required(ownerUuid, "ownerUuid");
        DomainChecks.required(position, "position");
        strainId = DomainChecks.identifier(strainId, "strainId");
        DomainChecks.required(geneticProfileId, "geneticProfileId");
        DomainChecks.required(growthStage, "growthStage");
        growthProgress = DomainChecks.range(growthProgress, 0.0, 150.0, "growthProgress");
        health = percentage(health, "health");
        hydration = percentage(hydration, "hydration");
        nutrition = percentage(nutrition, "nutrition");
        lightLevel = DomainChecks.range(lightLevel, 0, 15, "lightLevel");
        stress = percentage(stress, "stress");
        cleanliness = percentage(cleanliness, "cleanliness");
        diseaseId = DomainChecks.required(diseaseId, "diseaseId")
                .map(value -> DomainChecks.identifier(value, "diseaseId"));
        pestLevel = percentage(pestLevel, "pestLevel");
        resistance = percentage(resistance, "resistance");
        yieldPotential = percentage(yieldPotential, "yieldPotential");
        qualityPotential = percentage(qualityPotential, "qualityPotential");
        DomainChecks.required(plantedAt, "plantedAt");
        DomainChecks.required(lastUpdatedAt, "lastUpdatedAt");
        DomainChecks.required(lastWateredAt, "lastWateredAt");
        DomainChecks.ordered(plantedAt, lastUpdatedAt, "plant update");
        DomainChecks.ordered(plantedAt, lastWateredAt, "plant watering");
        DomainChecks.ordered(harvestWindowStart, harvestWindowEnd, "harvest window");
        if (harvestWindowStart.isBefore(plantedAt)) {
            throw new IllegalArgumentException("harvest window cannot start before planting");
        }
        farmId = DomainChecks.required(farmId, "farmId");
        workerAssigned = DomainChecks.required(workerAssigned, "workerAssigned");
    }

    public static Plant planted(UUID id, UUID owner, BlockPosition position, Strain strain,
                                GeneticProfile genetics, Instant plantedAt, Optional<UUID> farmId) {
        DomainChecks.required(strain, "strain");
        DomainChecks.required(genetics, "genetics");
        long adjustedMillis = Math.max(1L,
                Math.round(strain.growthDuration().toMillis() / genetics.growthSpeedModifier()));
        Duration adjustedGrowth = Duration.ofMillis(adjustedMillis);
        Instant windowStart = plantedAt.plus(adjustedGrowth);
        Instant windowEnd = windowStart.plusMillis(Math.max(1L, adjustedMillis / 5L));
        return new Plant(
                id, owner, position, strain.id(), genetics.id(), GrowthStage.SEED,
                0.0, 100.0, 75.0, 75.0, (int) Math.round(strain.lightPreference()),
                0.0, 100.0, Optional.empty(), 0.0,
                DomainChecks.clamp(strain.diseaseResistance() * genetics.resistanceModifier(), 0.0, 100.0),
                DomainChecks.clamp(strain.baseYield() * genetics.yieldModifier(), 0.0, 100.0),
                DomainChecks.clamp(strain.qualityPotential() * genetics.qualityModifier(), 0.0, 100.0),
                plantedAt, plantedAt, plantedAt, windowStart, windowEnd,
                DomainChecks.required(farmId, "farmId"), Optional.empty());
    }

    public Plant withSimulation(GrowthStage stage, double progress, double newHealth, double newHydration,
                                double newNutrition, double newStress, double newYieldPotential,
                                double newQualityPotential, Instant updatedAt) {
        return new Plant(uniqueId, ownerUuid, position, strainId, geneticProfileId, stage, progress,
                newHealth, newHydration, newNutrition, lightLevel, newStress, cleanliness, diseaseId,
                pestLevel, resistance, newYieldPotential, newQualityPotential, plantedAt, updatedAt,
                lastWateredAt, harvestWindowStart, harvestWindowEnd, farmId, workerAssigned);
    }

    public Plant withWatering(double newHydration, double newStress, double newQualityPotential, Instant wateredAt) {
        return new Plant(uniqueId, ownerUuid, position, strainId, geneticProfileId, growthStage,
                growthProgress, health, newHydration, nutrition, lightLevel, newStress, cleanliness,
                diseaseId, pestLevel, resistance, yieldPotential, newQualityPotential, plantedAt,
                max(lastUpdatedAt, wateredAt), wateredAt, harvestWindowStart, harvestWindowEnd, farmId,
                workerAssigned);
    }

    public Plant withEnvironment(int newLightLevel, double newCleanliness, Optional<String> newDiseaseId,
                                 double newPestLevel) {
        return new Plant(uniqueId, ownerUuid, position, strainId, geneticProfileId, growthStage,
                growthProgress, health, hydration, nutrition, newLightLevel, stress, newCleanliness,
                newDiseaseId, newPestLevel, resistance, yieldPotential, qualityPotential, plantedAt,
                lastUpdatedAt, lastWateredAt, harvestWindowStart, harvestWindowEnd, farmId, workerAssigned);
    }

    public Plant withCare(double newHealth, double newNutrition, double newStress, double newCleanliness,
                          Optional<String> newDiseaseId, double newPestLevel,
                          double newYieldPotential, double newQualityPotential, Instant caredAt) {
        DomainChecks.required(caredAt, "caredAt");
        if (caredAt.isBefore(lastUpdatedAt)) {
            throw new IllegalArgumentException("care cannot happen before the latest simulation update");
        }
        return new Plant(uniqueId, ownerUuid, position, strainId, geneticProfileId, growthStage,
                growthProgress, newHealth, hydration, newNutrition, lightLevel, newStress, newCleanliness,
                newDiseaseId, newPestLevel, resistance, newYieldPotential, newQualityPotential, plantedAt,
                caredAt, lastWateredAt, harvestWindowStart, harvestWindowEnd, farmId, workerAssigned);
    }

    public Plant assignWorker(Optional<UUID> workerUuid) {
        return new Plant(uniqueId, ownerUuid, position, strainId, geneticProfileId, growthStage,
                growthProgress, health, hydration, nutrition, lightLevel, stress, cleanliness, diseaseId,
                pestLevel, resistance, yieldPotential, qualityPotential, plantedAt, lastUpdatedAt,
                lastWateredAt, harvestWindowStart, harvestWindowEnd, farmId,
                DomainChecks.required(workerUuid, "workerUuid"));
    }

    private static double percentage(double value, String name) {
        return DomainChecks.range(value, 0.0, 100.0, name);
    }

    private static Instant max(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }
}
