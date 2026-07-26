package com.verdantvice.plant.care;

import com.verdantvice.common.DomainChecks;
import com.verdantvice.plant.Plant;

public record CareResult(
        Plant plant,
        CareAction action,
        double appliedAmount,
        double consumedResource,
        double penalty,
        boolean problemResolved
) {
    public CareResult {
        DomainChecks.required(plant, "plant");
        DomainChecks.required(action, "action");
        appliedAmount = DomainChecks.range(appliedAmount, 0.0, 1_000.0, "appliedAmount");
        consumedResource = DomainChecks.range(consumedResource, 0.0, 1_000.0, "consumedResource");
        penalty = DomainChecks.range(penalty, 0.0, 100.0, "penalty");
    }
}
