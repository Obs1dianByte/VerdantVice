package com.verdantvice.plant.growth;

import com.verdantvice.plant.GrowthStage;
import com.verdantvice.plant.Plant;
import com.verdantvice.plant.PlantProblem;

import java.time.Duration;
import java.util.Set;

public record PlantUpdate(
        Plant plant,
        GrowthStage previousStage,
        Duration simulatedElapsed,
        Set<PlantProblem> detectedProblems
) {
    public PlantUpdate {
        java.util.Objects.requireNonNull(plant, "plant");
        java.util.Objects.requireNonNull(previousStage, "previousStage");
        java.util.Objects.requireNonNull(simulatedElapsed, "simulatedElapsed");
        detectedProblems = Set.copyOf(java.util.Objects.requireNonNull(detectedProblems, "detectedProblems"));
    }

    public boolean stageChanged() {
        return previousStage != plant.growthStage();
    }
}
