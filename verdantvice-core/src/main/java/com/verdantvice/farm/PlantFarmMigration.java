package com.verdantvice.farm;

import com.verdantvice.plant.Plant;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

public final class PlantFarmMigration {
    private PlantFarmMigration() {
    }

    public static Optional<Plant> repair(
            Plant plant,
            Collection<Farm> farms
    ) {
        Objects.requireNonNull(plant, "plant");
        Objects.requireNonNull(farms, "farms");
        if (plant.farmId().isPresent()) {
            return Optional.empty();
        }
        return PlantFarmAssignment.resolve(plant.position(), farms)
                .map(farmId -> plant.withFarmId(Optional.of(farmId)));
    }
}
