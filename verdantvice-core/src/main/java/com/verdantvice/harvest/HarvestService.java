package com.verdantvice.harvest;

import com.verdantvice.common.DomainChecks;
import com.verdantvice.genetics.GeneticProfile;
import com.verdantvice.plant.GrowthStage;
import com.verdantvice.plant.Plant;
import com.verdantvice.processing.ProductBatch;
import com.verdantvice.strain.Strain;

import java.time.Instant;
import java.util.UUID;

public final class HarvestService {
    private final HarvestWindowService windowService;

    public HarvestService(HarvestWindowService windowService) {
        this.windowService = DomainChecks.required(windowService, "windowService");
    }

    public ProductBatch harvest(UUID batchId, Plant plant, Strain strain, GeneticProfile genetics, Instant now) {
        DomainChecks.required(batchId, "batchId");
        DomainChecks.required(plant, "plant");
        DomainChecks.required(strain, "strain");
        DomainChecks.required(genetics, "genetics");
        DomainChecks.required(now, "now");
        if (plant.growthStage() == GrowthStage.SPOILED || plant.health() <= 0.0) {
            throw new IllegalStateException("Spoiled plants cannot produce a batch");
        }
        if (!plant.strainId().equals(strain.id()) || !plant.geneticProfileId().equals(genetics.id())) {
            throw new IllegalArgumentException("Plant, strain and genetics do not match");
        }
        HarvestTiming timing = windowService.assess(plant, now);
        double weight = Math.max(0.01, plant.yieldPotential() * timing.yieldMultiplier());
        double healthFactor = 0.65 + plant.health() / 100.0 * 0.35;
        double quality = DomainChecks.clamp(
                plant.qualityPotential() * timing.qualityMultiplier() * healthFactor
                        * genetics.qualityModifier(),
                0.0, 100.0);
        double contamination = DomainChecks.clamp(
                (100.0 - plant.cleanliness()) * 0.25 + plant.pestLevel() * 0.2
                        + (plant.diseaseId().isPresent() ? 12.0 : 0.0),
                0.0, 100.0);
        return ProductBatch.fresh(batchId, plant.ownerUuid(), strain.id(), genetics.id(), weight,
                quality, contamination, timing, now);
    }
}
