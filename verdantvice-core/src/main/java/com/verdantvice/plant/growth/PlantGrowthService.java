package com.verdantvice.plant.growth;

import com.verdantvice.common.DomainChecks;
import com.verdantvice.genetics.GeneticProfile;
import com.verdantvice.plant.GrowthStage;
import com.verdantvice.plant.Plant;
import com.verdantvice.plant.PlantProblem;
import com.verdantvice.strain.Strain;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * Pure timestamp-based plant simulation. It never touches the world or a scheduler.
 */
public final class PlantGrowthService {
    public PlantUpdate update(Plant plant, Strain strain, GeneticProfile genetics, Instant now,
                              GrowthPolicy policy, boolean chunkLoaded) {
        DomainChecks.required(plant, "plant");
        DomainChecks.required(strain, "strain");
        DomainChecks.required(genetics, "genetics");
        DomainChecks.required(now, "now");
        DomainChecks.required(policy, "policy");
        verifyIdentity(plant, strain, genetics);
        if (now.isBefore(plant.lastUpdatedAt())) {
            throw new IllegalArgumentException("now cannot precede lastUpdatedAt");
        }
        if (plant.growthStage() == GrowthStage.SPOILED) {
            return new PlantUpdate(plant, GrowthStage.SPOILED, Duration.ZERO, Set.of());
        }

        Duration actualElapsed = Duration.between(plant.lastUpdatedAt(), now);
        boolean elapsedAllowed = (chunkLoaded || policy.unloadedChunkGrowth())
                && (actualElapsed.compareTo(Duration.ofMinutes(5)) <= 0 || policy.offlineGrowth());
        Duration simulated = elapsedAllowed && actualElapsed.compareTo(policy.maximumElapsed()) > 0
                ? policy.maximumElapsed()
                : elapsedAllowed ? actualElapsed : Duration.ZERO;
        double hours = simulated.toMillis() / 3_600_000.0;

        double hydration = DomainChecks.clamp(plant.hydration()
                - hours * policy.hydrationLossPerHour() * Math.max(0.15, strain.waterConsumption()), 0.0, 100.0);
        double nutrition = DomainChecks.clamp(plant.nutrition() - hours * policy.nutritionLossPerHour(), 0.0, 100.0);

        EnumSet<PlantProblem> problems = detectProblems(plant, strain, hydration);
        double environmentalFactor = environmentalFactor(plant, strain, hydration, nutrition, problems);
        double progressGain = simulated.toMillis() / (double) strain.growthDuration().toMillis()
                * 100.0 * genetics.growthSpeedModifier() * environmentalFactor;
        double progress = DomainChecks.clamp(plant.growthProgress() + progressGain, 0.0, 150.0);

        double severity = 0.0;
        if (hydration < 15.0) severity += (15.0 - hydration) / 15.0;
        if (nutrition < 10.0) severity += (10.0 - nutrition) / 10.0;
        severity += plant.pestLevel() / 100.0;
        if (plant.diseaseId().isPresent()) severity += 0.75;
        severity *= Math.max(0.15, 1.0 - plant.resistance() / 125.0);

        double healthLoss = hours * policy.criticalHealthLossPerHour() * severity;
        double stressGain = hours * policy.stressGainPerHour() * (problems.size() / 3.0)
                * Math.max(0.25, strain.stressSensitivity());
        double health = DomainChecks.clamp(plant.health() - healthLoss, 0.0, 100.0);
        double stress = DomainChecks.clamp(plant.stress() + stressGain, 0.0, 100.0);
        double lastingDamage = healthLoss * 0.35 + stressGain * 0.12;
        double yield = DomainChecks.clamp(plant.yieldPotential() - lastingDamage, 0.0, 100.0);
        double quality = DomainChecks.clamp(plant.qualityPotential() - lastingDamage * 0.8, 0.0, 100.0);
        GrowthStage stage = resolveStage(progress, health, now, plant, policy);

        Plant updated = plant.withSimulation(stage, progress, health, hydration, nutrition, stress, yield,
                quality, now);
        return new PlantUpdate(updated, plant.growthStage(), simulated, problems);
    }

    public WateringResult water(Plant plant, double requestedAmount, Instant now) {
        DomainChecks.required(plant, "plant");
        DomainChecks.range(requestedAmount, 0.01, 100.0, "requestedAmount");
        DomainChecks.required(now, "now");
        if (now.isBefore(plant.lastUpdatedAt())) {
            throw new IllegalArgumentException("watering cannot happen before the latest update");
        }
        double availableCapacity = 100.0 - plant.hydration();
        double applied = Math.min(requestedAmount, availableCapacity);
        double overflow = Math.max(0.0, requestedAmount - availableCapacity);
        boolean overwatered = plant.hydration() >= 85.0 || overflow > 0.0;
        double stress = plant.stress();
        double quality = plant.qualityPotential();
        if (overwatered) {
            double penalty = Math.max(1.0, overflow * 0.15 + Math.max(0.0, plant.hydration() - 85.0) * 0.05);
            stress = DomainChecks.clamp(stress + penalty, 0.0, 100.0);
            quality = DomainChecks.clamp(quality - penalty * 0.15, 0.0, 100.0);
        }
        return new WateringResult(
                plant.withWatering(plant.hydration() + applied, stress, quality, now),
                applied,
                overwatered);
    }

    private static void verifyIdentity(Plant plant, Strain strain, GeneticProfile genetics) {
        if (!plant.strainId().equals(strain.id())) {
            throw new IllegalArgumentException("Plant strain does not match supplied strain");
        }
        if (!plant.geneticProfileId().equals(genetics.id())) {
            throw new IllegalArgumentException("Plant genetics do not match supplied profile");
        }
    }

    private static EnumSet<PlantProblem> detectProblems(Plant plant, Strain strain, double hydration) {
        EnumSet<PlantProblem> problems = EnumSet.noneOf(PlantProblem.class);
        if (hydration < 20.0) problems.add(PlantProblem.DRY_SOIL);
        if (hydration > 92.0) problems.add(PlantProblem.OVERWATERING);
        if (plant.lightLevel() + 3 < strain.lightPreference()) problems.add(PlantProblem.LOW_LIGHT);
        if (plant.lightLevel() - 3 > strain.lightPreference()) problems.add(PlantProblem.EXCESSIVE_LIGHT);
        if (plant.cleanliness() < 35.0) problems.add(PlantProblem.DIRTY_STATION);
        if (plant.pestLevel() > 20.0) problems.add(PlantProblem.FICTIONAL_PESTS);
        if (plant.diseaseId().isPresent()) problems.add(PlantProblem.FICTIONAL_MOLD);
        if (plant.stress() > 70.0) problems.add(PlantProblem.ROOT_STRESS);
        if (strain.smellIntensity() > 80.0) problems.add(PlantProblem.STRONG_SMELL);
        return problems;
    }

    private static double environmentalFactor(Plant plant, Strain strain, double hydration, double nutrition,
                                              Set<PlantProblem> problems) {
        double hydrationFactor = hydration < 15.0 ? 0.2 : hydration < 35.0 ? 0.7 : hydration > 95.0 ? 0.65 : 1.0;
        double nutritionFactor = nutrition < 10.0 ? 0.35 : nutrition < 30.0 ? 0.75 : 1.0;
        double lightDelta = Math.abs(plant.lightLevel() - strain.lightPreference());
        double lightFactor = DomainChecks.clamp(1.0 - lightDelta / 15.0, 0.35, 1.0);
        double problemFactor = DomainChecks.clamp(1.0 - problems.size() * 0.06, 0.45, 1.0);
        return hydrationFactor * nutritionFactor * lightFactor * problemFactor;
    }

    private static GrowthStage resolveStage(double progress, double health, Instant now, Plant plant,
                                            GrowthPolicy policy) {
        if (health <= 0.0 || !now.isBefore(plant.harvestWindowEnd().plus(policy.spoilGracePeriod()))) {
            return GrowthStage.SPOILED;
        }
        if (!now.isBefore(plant.harvestWindowEnd()) && progress >= 90.0) return GrowthStage.OVERRIPE;
        if (progress >= 100.0 || !now.isBefore(plant.harvestWindowStart())) return GrowthStage.READY;
        if (progress >= 65.0) return GrowthStage.MATURING;
        if (progress >= 30.0) return GrowthStage.VEGETATIVE;
        if (progress >= 12.0) return GrowthStage.SEEDLING;
        if (progress >= 2.0) return GrowthStage.SPROUT;
        return GrowthStage.SEED;
    }
}
