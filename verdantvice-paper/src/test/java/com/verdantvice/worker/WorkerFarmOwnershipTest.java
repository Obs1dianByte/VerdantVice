package com.verdantvice.worker;

import com.verdantvice.farm.Farm;
import com.verdantvice.farm.FarmRegion;
import com.verdantvice.farm.FarmService;
import com.verdantvice.farm.FarmSettings;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class WorkerFarmOwnershipTest {
    @Test
    void ownerHiredWorkerUsesTheOwnersFarmAssets() {
        Farm farm = farm();
        Worker worker = worker(farm.ownerUuid(), farm.farmId());
        assertEquals(farm.ownerUuid(), WorkerFarmOwnership.assetOwner(farm, worker));
    }

    @Test
    void managerHiredWorkerUsesFarmAssetsWithoutChangingItsPayer() {
        Farm farm = farm();
        UUID manager = UUID.randomUUID();
        Worker worker = worker(manager, farm.farmId());
        assertEquals(farm.ownerUuid(), WorkerFarmOwnership.assetOwner(farm, worker));
        assertEquals(manager, worker.ownerUuid());
    }

    @Test
    void anotherFarmsWorkerCannotResolveThisFarmsAssetIdentity() {
        Farm farm = farm();
        Worker worker = worker(farm.ownerUuid(), UUID.randomUUID());
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> WorkerFarmOwnership.assetOwner(farm, worker));
        assertEquals("worker_farm_mismatch", failure.getMessage());
    }

    private static Worker worker(UUID payer, UUID farmId) {
        return Worker.hired(UUID.randomUUID(), payer, farmId,
                WorkerSpecialization.values()[0], 0L);
    }

    private static Farm farm() {
        return new FarmService().create(UUID.randomUUID(), UUID.randomUUID(), "Player Farm",
                new FarmRegion(UUID.randomUUID(), 0, 60, 0, 16, 80, 16),
                64, 5, FarmSettings.defaults(), List.of(), Instant.now());
    }
}
