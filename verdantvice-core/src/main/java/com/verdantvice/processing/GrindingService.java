package com.verdantvice.processing;

import com.verdantvice.common.DomainChecks;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

public final class GrindingService {
    public GroundProduct grind(UUID productId, ProductBatch batch, GrinderParameters grinder,
                               double requestedWeight, double playerSkill, RandomGenerator random,
                               Instant now) {
        DomainChecks.required(productId, "productId");
        DomainChecks.required(batch, "batch");
        DomainChecks.required(grinder, "grinder");
        DomainChecks.range(requestedWeight, 0.01, grinder.capacity(), "requestedWeight");
        DomainChecks.range(playerSkill, 0.0, 100.0, "playerSkill");
        DomainChecks.required(random, "random");
        DomainChecks.required(now, "now");
        if (batch.state() != BatchState.DRIED && batch.state() != BatchState.CURED) {
            throw new IllegalStateException("Only dried or cured product can be ground");
        }
        if (requestedWeight > batch.dryWeight()) {
            throw new IllegalArgumentException("requestedWeight exceeds batch dry weight");
        }
        double conditionPenalty = (100.0 - grinder.durability()) / 100.0;
        double stochasticLoss = random.nextDouble() < grinder.lossRisk() * conditionPenalty
                ? random.nextDouble(0.02, 0.12) : 0.0;
        double effectiveEfficiency = grinder.efficiency()
                + (1.0 - grinder.efficiency()) * (playerSkill / 100.0) * 0.8;
        double retained = requestedWeight * effectiveEfficiency;
        retained *= 1.0 - stochasticLoss;
        retained = Math.max(0.001, retained);
        double qualityPenalty = conditionPenalty * 4.0 + stochasticLoss * 20.0;
        double quality = DomainChecks.clamp(batch.qualityScore() - qualityPenalty, 0.0, 100.0);
        double stability = DomainChecks.clamp(0.65 + grinder.efficiency() * 0.25
                + playerSkill / 1000.0 - conditionPenalty * 0.2, 0.0, 1.0);
        return new GroundProduct(productId, batch.ownerUuid(), Set.of(batch.batchId()),
                batch.strainComposition(), retained, quality, QualityGrade.fromScore(quality), stability, now);
    }
}
