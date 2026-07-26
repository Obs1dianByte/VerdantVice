package com.verdantvice.processing;

import com.verdantvice.common.DomainChecks;

public record GrinderParameters(
        int level,
        double capacity,
        double durability,
        double efficiency,
        double lossRisk
) {
    public GrinderParameters {
        level = DomainChecks.range(level, 1, 100, "level");
        capacity = DomainChecks.positive(capacity, "capacity");
        durability = DomainChecks.range(durability, 0.0, 100.0, "durability");
        efficiency = DomainChecks.range(efficiency, 0.5, 1.0, "efficiency");
        lossRisk = DomainChecks.range(lossRisk, 0.0, 1.0, "lossRisk");
    }
}
