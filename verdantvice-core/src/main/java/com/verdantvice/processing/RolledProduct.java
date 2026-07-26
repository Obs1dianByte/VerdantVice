package com.verdantvice.processing;

import com.verdantvice.common.DomainChecks;

import java.time.Instant;
import java.util.UUID;

public record RolledProduct(
        UUID productId,
        UUID ownerUuid,
        UUID mixtureId,
        RollingResult result,
        double consumedWeight,
        double retainedWeight,
        double durationMultiplier,
        double potencyMultiplier,
        double burnQuality,
        double extinguishChance,
        double valueMultiplier,
        Instant createdAt
) {
    public RolledProduct {
        DomainChecks.required(productId, "productId");
        DomainChecks.required(ownerUuid, "ownerUuid");
        DomainChecks.required(mixtureId, "mixtureId");
        DomainChecks.required(result, "result");
        consumedWeight = DomainChecks.range(consumedWeight, 0.0, 100.0, "consumedWeight");
        retainedWeight = DomainChecks.range(retainedWeight, 0.0, consumedWeight, "retainedWeight");
        durationMultiplier = DomainChecks.range(durationMultiplier, 0.0, 2.0, "durationMultiplier");
        potencyMultiplier = DomainChecks.range(potencyMultiplier, 0.0, 2.0, "potencyMultiplier");
        burnQuality = DomainChecks.range(burnQuality, 0.0, 1.0, "burnQuality");
        extinguishChance = DomainChecks.range(extinguishChance, 0.0, 1.0, "extinguishChance");
        valueMultiplier = DomainChecks.range(valueMultiplier, 0.0, 3.0, "valueMultiplier");
        DomainChecks.required(createdAt, "createdAt");
    }
}
