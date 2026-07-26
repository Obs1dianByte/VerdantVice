package com.verdantvice.farm;

import com.verdantvice.common.DomainChecks;
import com.verdantvice.processing.QualityGrade;

public record FarmSettings(
        boolean workersMayPlant,
        boolean allowRareSeeds,
        boolean legendarySeedRequiresApproval,
        QualityGrade minimumHarvestQuality,
        int maximumPlantsPerWorker
) {
    public FarmSettings {
        DomainChecks.required(minimumHarvestQuality, "minimumHarvestQuality");
        maximumPlantsPerWorker = DomainChecks.range(maximumPlantsPerWorker, 1, 1_000,
                "maximumPlantsPerWorker");
    }

    public static FarmSettings defaults() {
        return new FarmSettings(true, false, true, QualityGrade.COMMON, 10);
    }
}
