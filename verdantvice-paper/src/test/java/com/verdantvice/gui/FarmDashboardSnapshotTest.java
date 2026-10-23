package com.verdantvice.gui;

import com.verdantvice.common.BlockPosition;
import com.verdantvice.farm.Farm;
import com.verdantvice.farm.FarmMember;
import com.verdantvice.farm.FarmRegion;
import com.verdantvice.farm.FarmRole;
import com.verdantvice.farm.FarmSettings;
import com.verdantvice.farm.FarmStorage;
import com.verdantvice.farm.StorageType;
import com.verdantvice.plant.GrowthStage;
import com.verdantvice.plant.Plant;
import com.verdantvice.processing.ProcessingManager;
import com.verdantvice.processing.ProcessingStationType;
import com.verdantvice.worker.Worker;
import com.verdantvice.worker.WorkerManager;
import com.verdantvice.worker.WorkerRecord;
import com.verdantvice.worker.WorkerSchedule;
import com.verdantvice.worker.WorkerSpecialization;
import com.verdantvice.worker.WorkerState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarmDashboardSnapshotTest {
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000302");
    private static final UUID FARM = UUID.fromString("00000000-0000-0000-0000-000000000303");

    @Test
    void emptyFarmShowsRealZeros() {
        FarmDashboardSnapshot snapshot = snapshot(List.of(), List.of(), List.of(), List.of(), List.of());
        assertEquals(0, snapshot.plants().count());
        assertEquals(0, snapshot.workers().count());
        assertEquals(0, snapshot.storages().count());
    }

    @Test
    void onePlantIncreasesCount() {
        assertEquals(1, snapshot(
                List.of(plant(GrowthStage.SEED, false, 75, 100)),
                List.of(), List.of(), List.of(), List.of()).plants().count());
    }

    @Test
    void readyPlantIncreasesReadyCounter() {
        assertEquals(1, snapshot(
                List.of(plant(GrowthStage.READY, false, 75, 100)),
                List.of(), List.of(), List.of(), List.of()).plants().ready());
    }

    @Test
    void diseasedPlantIncreasesSickAndTreatmentCounters() {
        FarmDashboardSnapshot.PlantMetrics metrics = snapshot(
                List.of(plant(GrowthStage.SEED, true, 75, 100)),
                List.of(), List.of(), List.of(), List.of()).plants();
        assertEquals(1, metrics.sick());
        assertEquals(1, metrics.needsTreatment());
    }

    @Test
    void workerIncreasesCountAndUsesActualMorale() {
        WorkerManager.WorkerSnapshot worker = worker(43.0, 0, true, 0);
        FarmDashboardSnapshot.WorkerMetrics metrics = snapshot(
                List.of(), List.of(worker), List.of(), List.of(), List.of()).workers();
        assertEquals(1, metrics.count());
        assertEquals(43.0, metrics.averageMorale());
        assertEquals(1, metrics.working());
    }

    @Test
    void storageIncreasesCountByExactType() {
        FarmDashboardSnapshot snapshot = snapshot(
                List.of(), List.of(),
                List.of(storage(StorageType.SEED_STORAGE, true, 2, 27, 0)),
                List.of(), List.of());
        assertEquals(1, snapshot.storages().count());
        assertEquals(1L, snapshot.storages().byType().get(StorageType.SEED_STORAGE).longValue());
    }

    @Test
    void dryingRackCountersUseRuntimeStatus() {
        FarmDashboardSnapshot.ProcessingMetrics metrics = snapshot(
                List.of(), List.of(), List.of(),
                List.of(station(ProcessingStationType.DRYING_RACK,
                        ProcessingManager.StationStatus.PROCESSING)),
                List.of()).drying();
        assertEquals(1, metrics.count());
        assertEquals(1, metrics.active());
    }

    @Test
    void curingContainerCountersUseRuntimeStatus() {
        FarmDashboardSnapshot.ProcessingMetrics metrics = snapshot(
                List.of(), List.of(), List.of(), List.of(),
                List.of(station(ProcessingStationType.CURING_CONTAINER,
                        ProcessingManager.StationStatus.READY))).curing();
        assertEquals(1, metrics.count());
        assertEquals(1, metrics.ready());
    }

    @Test
    void salaryStorageAndEmeraldsAreVisible() {
        FarmDashboardSnapshot snapshot = snapshot(
                List.of(), List.of(worker(75.0, 0, false, 0)),
                List.of(storage(StorageType.SALARY_STORAGE, true, 1, 27, 18)),
                List.of(), List.of());
        assertTrue(snapshot.salary().storagePresent());
        assertTrue(snapshot.salary().storageAvailable());
        assertEquals(18, snapshot.salary().emeralds());
        assertEquals(4L, snapshot.salary().nextCost());
    }

    @Test
    void metricsChangeWithInputsInsteadOfUsingConstantZeros() {
        FarmDashboardSnapshot snapshot = snapshot(
                List.of(
                        plant(GrowthStage.READY, false, 20, 30),
                        plant(GrowthStage.SEED, true, 80, 100)
                ),
                List.of(worker(10.0, 3, false, 2)),
                List.of(storage(StorageType.GENERAL_STORAGE, false, 0, 0, 0)),
                List.of(station(ProcessingStationType.DRYING_RACK,
                        ProcessingManager.StationStatus.FREE)),
                List.of());
        assertEquals(2, snapshot.plants().count());
        assertEquals(1, snapshot.plants().needsWater());
        assertEquals(1, snapshot.workers().blocked());
        assertEquals(1, snapshot.storages().unavailable());
        assertEquals(1, snapshot.drying().free());
        assertFalse(snapshot.salary().storagePresent());
        assertFalse(snapshot.salary().storageAvailable());
    }

    private FarmDashboardSnapshot snapshot(
            List<Plant> plants,
            List<WorkerManager.WorkerSnapshot> workers,
            List<FarmDashboardSnapshot.StorageRuntimeSnapshot> storages,
            List<ProcessingManager.StationSnapshot> drying,
            List<ProcessingManager.StationSnapshot> curing
    ) {
        return FarmDashboardSnapshot.create(farm(), plants, workers, storages, drying, curing);
    }

    private Farm farm() {
        FarmMember owner = new FarmMember(
                OWNER, FarmRole.OWNER, Set.of(), Set.of(), Instant.EPOCH);
        return new Farm(
                FARM, OWNER, "test",
                new FarmRegion(WORLD, 0, 0, 0, 20, 255, 20),
                2, 64, 5, Set.of(), Set.of(), Set.of(), Optional.empty(),
                Instant.EPOCH, FarmSettings.defaults(), Map.of(OWNER, owner)
        );
    }

    private Plant plant(
            GrowthStage stage,
            boolean diseased,
            double hydration,
            double cleanliness
    ) {
        Instant planted = Instant.parse("2026-01-01T00:00:00Z");
        return new Plant(
                UUID.randomUUID(), OWNER,
                new BlockPosition(WORLD, 5, 64, 5),
                "test-strain", UUID.randomUUID(), stage,
                stage == GrowthStage.READY ? 100.0 : 10.0,
                100.0, hydration, 75.0, 12, 0.0, cleanliness,
                diseased ? Optional.of("mildew") : Optional.empty(),
                0.0, 50.0, 50.0, 50.0,
                planted, planted, planted,
                planted.plusSeconds(3600), planted.plusSeconds(4200),
                Optional.of(FARM), Optional.empty()
        );
    }

    private WorkerManager.WorkerSnapshot worker(
            double morale,
            int unpaidDays,
            boolean busy,
            int blocked
    ) {
        UUID id = UUID.randomUUID();
        Worker worker = new Worker(
                id, OWNER, FARM, WorkerSpecialization.GROWER,
                1, 0, morale, 4, unpaidDays, 0,
                busy ? WorkerState.WATERING : WorkerState.IDLE,
                Optional.empty()
        );
        WorkerRecord record = new WorkerRecord(
                worker, UUID.randomUUID(), "Worker",
                new WorkerSchedule(0, 1000, 6000, 7000, 12000, 16000),
                Optional.empty(),
                Optional.of(new BlockPosition(WORLD, 6, 64, 6)),
                Instant.EPOCH, Instant.EPOCH, 0
        );
        return new WorkerManager.WorkerSnapshot(
                record,
                busy ? Optional.of(com.verdantvice.worker.WorkerTaskType.WATER) : Optional.empty(),
                busy ? Optional.of(50) : Optional.empty(),
                Optional.empty(),
                record.lastKnownPosition(),
                true,
                busy,
                blocked
        );
    }

    private FarmDashboardSnapshot.StorageRuntimeSnapshot storage(
            StorageType type,
            boolean exists,
            int used,
            int total,
            int emeralds
    ) {
        FarmStorage storage = new FarmStorage(
                UUID.randomUUID(), FARM,
                new BlockPosition(WORLD, 7, 64, 7),
                type, true, Instant.EPOCH
        );
        return new FarmDashboardSnapshot.StorageRuntimeSnapshot(
                storage, exists, exists, used, total, emeralds);
    }

    private ProcessingManager.StationSnapshot station(
            ProcessingStationType type,
            ProcessingManager.StationStatus status
    ) {
        ProcessingManager.Station station = new ProcessingManager.Station(
                UUID.randomUUID(), type,
                new ProcessingManager.BlockKey(WORLD, 8, 64, 8)
        );
        return new ProcessingManager.StationSnapshot(
                station, status, Optional.empty(), Optional.empty(),
                Optional.empty(), 0, true
        );
    }
}
