package com.verdantvice.plant.growth;

import com.verdantvice.common.DomainChecks;

import java.time.Duration;

public record GrowthPolicy(
        boolean offlineGrowth,
        boolean unloadedChunkGrowth,
        Duration maximumElapsed,
        double hydrationLossPerHour,
        double nutritionLossPerHour,
        double criticalHealthLossPerHour,
        double stressGainPerHour,
        Duration spoilGracePeriod
) {
    public GrowthPolicy {
        maximumElapsed = DomainChecks.positive(maximumElapsed, "maximumElapsed");
        hydrationLossPerHour = DomainChecks.range(hydrationLossPerHour, 0.0, 100.0, "hydrationLossPerHour");
        nutritionLossPerHour = DomainChecks.range(nutritionLossPerHour, 0.0, 100.0, "nutritionLossPerHour");
        criticalHealthLossPerHour = DomainChecks.range(criticalHealthLossPerHour, 0.0, 100.0,
                "criticalHealthLossPerHour");
        stressGainPerHour = DomainChecks.range(stressGainPerHour, 0.0, 100.0, "stressGainPerHour");
        spoilGracePeriod = DomainChecks.positive(spoilGracePeriod, "spoilGracePeriod");
    }

    public static GrowthPolicy defaults() {
        return new GrowthPolicy(true, true, Duration.ofDays(7), 2.5, 0.7, 4.0, 2.0,
                Duration.ofHours(12));
    }
}
