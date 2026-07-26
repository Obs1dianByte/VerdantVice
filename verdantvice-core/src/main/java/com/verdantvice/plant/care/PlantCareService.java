package com.verdantvice.plant.care;

import com.verdantvice.common.DomainChecks;
import com.verdantvice.plant.Plant;

import java.time.Instant;
import java.util.Optional;

/**
 * Pure care operations for nutrient packs, cleaning kits, treatment sprays and repair kits.
 * The Paper layer should first call the growth simulation at the action timestamp, then apply care.
 */
public final class PlantCareService {
    public CareResult feed(Plant plant, double requestedAmount, double toolEfficiency, Instant now) {
        validate(plant, requestedAmount, toolEfficiency, now);
        double effective = requestedAmount * toolEfficiency;
        double capacity = 100.0 - plant.nutrition();
        double applied = Math.min(capacity, effective);
        double excess = Math.max(0.0, effective - capacity);
        double penalty = excess * 0.18
                + (plant.nutrition() >= 90.0 ? requestedAmount * 0.04 : 0.0);
        Plant updated = plant.withCare(
                plant.health(),
                DomainChecks.clamp(plant.nutrition() + applied, 0.0, 100.0),
                DomainChecks.clamp(plant.stress() + penalty, 0.0, 100.0),
                plant.cleanliness(),
                plant.diseaseId(),
                plant.pestLevel(),
                DomainChecks.clamp(plant.yieldPotential() - penalty * 0.08, 0.0, 100.0),
                DomainChecks.clamp(plant.qualityPotential() - penalty * 0.12, 0.0, 100.0),
                now);
        return new CareResult(updated, CareAction.FEED, applied, requestedAmount,
                DomainChecks.clamp(penalty, 0.0, 100.0), applied > 0.0 && updated.nutrition() >= 30.0);
    }

    public CareResult clean(Plant plant, double requestedAmount, double toolEfficiency, Instant now) {
        validate(plant, requestedAmount, toolEfficiency, now);
        double effective = requestedAmount * toolEfficiency;
        double applied = Math.min(100.0 - plant.cleanliness(), effective);
        Plant updated = plant.withCare(
                plant.health(), plant.nutrition(),
                DomainChecks.clamp(plant.stress() - applied * 0.04, 0.0, 100.0),
                DomainChecks.clamp(plant.cleanliness() + applied, 0.0, 100.0),
                plant.diseaseId(), plant.pestLevel(), plant.yieldPotential(), plant.qualityPotential(), now);
        return new CareResult(updated, CareAction.CLEAN, applied, requestedAmount, 0.0,
                updated.cleanliness() >= 35.0);
    }

    public CareResult treat(Plant plant, double requestedAmount, double toolEfficiency, Instant now) {
        validate(plant, requestedAmount, toolEfficiency, now);
        double effective = requestedAmount * toolEfficiency;
        double diseaseNeed = plant.diseaseId().isPresent() ? 20.0 : 0.0;
        double pestNeed = plant.pestLevel();
        double useful = Math.min(effective, diseaseNeed + pestNeed);
        double remainingAfterDisease = Math.max(0.0, effective - diseaseNeed);
        Optional<String> disease = plant.diseaseId();
        if (disease.isPresent() && effective >= diseaseNeed) disease = Optional.empty();
        double pestReduction = Math.min(pestNeed, remainingAfterDisease);
        double excess = Math.max(0.0, effective - diseaseNeed - pestNeed);
        double penalty = excess * 0.22;
        Plant updated = plant.withCare(
                DomainChecks.clamp(plant.health() - penalty * 0.05, 0.0, 100.0),
                plant.nutrition(),
                DomainChecks.clamp(plant.stress() - useful * 0.05 + penalty, 0.0, 100.0),
                plant.cleanliness(),
                disease,
                DomainChecks.clamp(plant.pestLevel() - pestReduction, 0.0, 100.0),
                DomainChecks.clamp(plant.yieldPotential() - penalty * 0.05, 0.0, 100.0),
                DomainChecks.clamp(plant.qualityPotential() - penalty * 0.15, 0.0, 100.0),
                now);
        return new CareResult(updated, CareAction.TREAT, useful, requestedAmount,
                DomainChecks.clamp(penalty, 0.0, 100.0),
                updated.diseaseId().isEmpty() && updated.pestLevel() == 0.0);
    }

    public CareResult repairAndRelieveStress(Plant plant, double requestedAmount,
                                             double toolEfficiency, Instant now) {
        validate(plant, requestedAmount, toolEfficiency, now);
        double effective = requestedAmount * toolEfficiency;
        double applied = Math.min(plant.stress(), effective);
        Plant updated = plant.withCare(
                DomainChecks.clamp(plant.health() + applied * 0.05, 0.0, 100.0),
                plant.nutrition(),
                DomainChecks.clamp(plant.stress() - applied, 0.0, 100.0),
                plant.cleanliness(), plant.diseaseId(), plant.pestLevel(),
                plant.yieldPotential(), plant.qualityPotential(), now);
        return new CareResult(updated, CareAction.REPAIR_AND_RELIEVE_STRESS, applied,
                requestedAmount, 0.0, updated.stress() < 70.0);
    }

    private static void validate(Plant plant, double requestedAmount, double toolEfficiency, Instant now) {
        DomainChecks.required(plant, "plant");
        DomainChecks.range(requestedAmount, 0.01, 100.0, "requestedAmount");
        DomainChecks.range(toolEfficiency, 0.1, 2.0, "toolEfficiency");
        DomainChecks.required(now, "now");
        if (now.isBefore(plant.lastUpdatedAt())) {
            throw new IllegalArgumentException("care cannot happen before the latest simulation update");
        }
    }
}
