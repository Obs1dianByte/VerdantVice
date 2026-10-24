package com.verdantvice.gui;

import com.verdantvice.farm.Farm;
import com.verdantvice.farm.FarmStorage;
import com.verdantvice.farm.StorageType;
import com.verdantvice.plant.GrowthStage;
import com.verdantvice.plant.Plant;
import com.verdantvice.processing.ProcessingManager;
import com.verdantvice.worker.WorkerManager;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public record FarmDashboardSnapshot(
        PlantMetrics plants,
        WorkerMetrics workers,
        StorageMetrics storages,
        ProcessingMetrics drying,
        ProcessingMetrics curing,
        SalaryMetrics salary
) {
    public FarmDashboardSnapshot {
        Objects.requireNonNull(plants, "plants");
        Objects.requireNonNull(workers, "workers");
        Objects.requireNonNull(storages, "storages");
        Objects.requireNonNull(drying, "drying");
        Objects.requireNonNull(curing, "curing");
        Objects.requireNonNull(salary, "salary");
    }

    public static FarmDashboardSnapshot create(
            Farm farm,
            Collection<Plant> farmPlants,
            Collection<WorkerManager.WorkerSnapshot> farmWorkers,
            Collection<StorageRuntimeSnapshot> farmStorages,
            Collection<ProcessingManager.StationSnapshot> dryingStations,
            Collection<ProcessingManager.StationSnapshot> curingStations
    ) {
        Objects.requireNonNull(farm, "farm");
        Objects.requireNonNull(farmPlants, "farmPlants");
        Objects.requireNonNull(farmWorkers, "farmWorkers");
        Objects.requireNonNull(farmStorages, "farmStorages");
        Objects.requireNonNull(dryingStations, "dryingStations");
        Objects.requireNonNull(curingStations, "curingStations");

        long ready = farmPlants.stream()
                .filter(plant -> plant.growthStage() == GrowthStage.READY)
                .count();
        long sick = farmPlants.stream()
                .filter(plant -> plant.diseaseId().isPresent() || plant.pestLevel() >= 15.0)
                .count();
        long needsWater = farmPlants.stream()
                .filter(plant -> plant.hydration() <= 30.0)
                .count();
        long needsTreatment = farmPlants.stream()
                .filter(plant -> plant.diseaseId().isPresent() || plant.pestLevel() >= 15.0)
                .count();
        long needsCleaning = farmPlants.stream()
                .filter(plant -> plant.cleanliness() <= 40.0)
                .count();

        long working = farmWorkers.stream()
                .filter(worker -> worker.busy() || worker.currentTask().isPresent())
                .count();
        long blocked = farmWorkers.stream()
                .filter(worker -> worker.blockedCount() > 0
                        || worker.record().worker().refusesNonCriticalTasks())
                .count();
        long unpaid = farmWorkers.stream()
                .filter(worker -> worker.record().worker().unpaidDays() > 0)
                .count();
        double averageMorale = farmWorkers.stream()
                .mapToDouble(worker -> worker.record().worker().morale())
                .average()
                .orElse(0.0);

        EnumMap<StorageType, Long> byType = new EnumMap<>(StorageType.class);
        for (StorageType type : StorageType.values()) {
            byType.put(type, 0L);
        }
        for (StorageRuntimeSnapshot storage : farmStorages) {
            byType.merge(storage.storage().type(), 1L, Long::sum);
        }
        long unavailable = farmStorages.stream()
                .filter(storage -> !storage.exists() || !storage.workerAccessible())
                .count();
        boolean salaryPresent = farmStorages.stream()
                .anyMatch(storage -> storage.storage().type() == StorageType.SALARY_STORAGE);
        boolean salaryAvailable = farmStorages.stream()
                .anyMatch(storage -> storage.storage().type() == StorageType.SALARY_STORAGE
                        && storage.exists());
        int emeralds = farmStorages.stream()
                .filter(storage -> storage.storage().type() == StorageType.SALARY_STORAGE)
                .filter(StorageRuntimeSnapshot::exists)
                .mapToInt(StorageRuntimeSnapshot::emeralds)
                .sum();
        long salaryCost = farmWorkers.stream()
                .mapToLong(worker -> worker.record().worker().salaryPerMinecraftDay())
                .sum();

        return new FarmDashboardSnapshot(
                new PlantMetrics(
                        farmPlants.size(), ready, sick, needsWater, needsTreatment,
                        needsCleaning, farm.plantLimit()
                ),
                new WorkerMetrics(
                        farmWorkers.size(), farm.workerLimit(), working,
                        Math.max(0L, farmWorkers.size() - working), unpaid,
                        averageMorale, blocked
                ),
                new StorageMetrics(
                        farmStorages.size(), Map.copyOf(byType), salaryPresent, unavailable
                ),
                processing(dryingStations),
                processing(curingStations),
                new SalaryMetrics(
                        salaryPresent, salaryAvailable, emeralds, salaryCost,
                        Math.max(0L, farmWorkers.size() - unpaid), unpaid
                )
        );
    }

    private static ProcessingMetrics processing(
            Collection<ProcessingManager.StationSnapshot> stations
    ) {
        long active = stations.stream()
                .filter(station -> station.status() == ProcessingManager.StationStatus.PROCESSING)
                .count();
        long ready = stations.stream()
                .filter(station -> station.status() == ProcessingManager.StationStatus.READY)
                .count();
        long free = stations.stream()
                .filter(station -> station.status() == ProcessingManager.StationStatus.FREE)
                .count();
        return new ProcessingMetrics(stations.size(), active, ready, free);
    }

    public record PlantMetrics(
            long count,
            long ready,
            long sick,
            long needsWater,
            long needsTreatment,
            long needsCleaning,
            long limit
    ) {
    }

    public record WorkerMetrics(
            long count,
            long limit,
            long working,
            long idle,
            long unpaid,
            double averageMorale,
            long blocked
    ) {
    }

    public record StorageMetrics(
            long count,
            Map<StorageType, Long> byType,
            boolean salaryPresent,
            long unavailable
    ) {
        public StorageMetrics {
            byType = Map.copyOf(byType);
        }
    }

    public record ProcessingMetrics(long count, long active, long ready, long free) {
    }

    public record SalaryMetrics(
            boolean storagePresent,
            boolean storageAvailable,
            int emeralds,
            long nextCost,
            long paidWorkers,
            long unpaidWorkers
    ) {
    }

    public record StorageRuntimeSnapshot(
            FarmStorage storage,
            boolean exists,
            boolean workerAccessible,
            int occupiedSlots,
            int totalSlots,
            int emeralds
    ) {
        public StorageRuntimeSnapshot {
            Objects.requireNonNull(storage, "storage");
            occupiedSlots = Math.max(0, occupiedSlots);
            totalSlots = Math.max(0, totalSlots);
            emeralds = Math.max(0, emeralds);
        }
    }
}
