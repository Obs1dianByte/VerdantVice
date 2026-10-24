package com.verdantvice.gui;

import com.verdantvice.plant.Plant;
import com.verdantvice.worker.WorkerManager;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class FarmScopedData {
    private FarmScopedData() {
    }

    public static List<Plant> plants(UUID farmId, Collection<Plant> plants) {
        Objects.requireNonNull(farmId, "farmId");
        Objects.requireNonNull(plants, "plants");
        return plants.stream()
                .filter(plant -> plant.farmId().filter(farmId::equals).isPresent())
                .toList();
    }

    public static List<WorkerManager.WorkerSnapshot> workers(
            UUID farmId,
            Collection<WorkerManager.WorkerSnapshot> workers
    ) {
        Objects.requireNonNull(farmId, "farmId");
        Objects.requireNonNull(workers, "workers");
        return workers.stream()
                .filter(snapshot -> snapshot.record().worker().farmId().equals(farmId))
                .toList();
    }
}
