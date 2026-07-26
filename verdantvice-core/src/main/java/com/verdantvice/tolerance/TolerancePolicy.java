package com.verdantvice.tolerance;

import com.verdantvice.common.DomainChecks;

public record TolerancePolicy(
        boolean enabled,
        double decayPerDay,
        double baseGainPerUse,
        double maximumGainPerUse,
        double potencyReductionAtMaximum,
        double durationReductionAtMaximum,
        double negativeRiskReductionAtMaximum
) {
    public TolerancePolicy {
        decayPerDay = DomainChecks.range(decayPerDay, 0.0, 100.0, "decayPerDay");
        baseGainPerUse = DomainChecks.range(baseGainPerUse, 0.0, 100.0, "baseGainPerUse");
        maximumGainPerUse = DomainChecks.range(maximumGainPerUse, baseGainPerUse, 100.0,
                "maximumGainPerUse");
        potencyReductionAtMaximum = fraction(potencyReductionAtMaximum, "potencyReductionAtMaximum");
        durationReductionAtMaximum = fraction(durationReductionAtMaximum, "durationReductionAtMaximum");
        negativeRiskReductionAtMaximum = fraction(negativeRiskReductionAtMaximum,
                "negativeRiskReductionAtMaximum");
    }

    public static TolerancePolicy defaults() {
        return new TolerancePolicy(true, 4.0, 2.0, 8.0, 0.65, 0.50, 0.45);
    }

    private static double fraction(double value, String name) {
        return DomainChecks.range(value, 0.0, 0.95, name);
    }
}
