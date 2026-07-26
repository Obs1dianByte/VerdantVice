package com.verdantvice.harvest;

import com.verdantvice.common.DomainChecks;
import com.verdantvice.plant.GrowthStage;
import com.verdantvice.plant.Plant;

import java.time.Duration;
import java.time.Instant;

public final class HarvestWindowService {
    public HarvestTiming assess(Plant plant, Instant now) {
        DomainChecks.required(plant, "plant");
        DomainChecks.required(now, "now");
        if (plant.growthStage() == GrowthStage.SPOILED || plant.growthStage() == GrowthStage.OVERRIPE
                || !now.isBefore(plant.harvestWindowEnd())) {
            return HarvestTiming.OVERRIPE;
        }
        if (now.isBefore(plant.harvestWindowStart())) {
            return plant.growthProgress() < 65.0 ? HarvestTiming.TOO_EARLY : HarvestTiming.EARLY;
        }
        Duration window = Duration.between(plant.harvestWindowStart(), plant.harvestWindowEnd());
        double fraction = Duration.between(plant.harvestWindowStart(), now).toMillis()
                / (double) Math.max(1L, window.toMillis());
        if (fraction < 0.25) return HarvestTiming.OPTIMAL;
        if (fraction <= 0.65) return HarvestTiming.PERFECT;
        return HarvestTiming.LATE;
    }

    public Duration timeUntilWindow(Plant plant, Instant now) {
        DomainChecks.required(plant, "plant");
        DomainChecks.required(now, "now");
        if (!now.isBefore(plant.harvestWindowStart())) return Duration.ZERO;
        return Duration.between(now, plant.harvestWindowStart());
    }
}
