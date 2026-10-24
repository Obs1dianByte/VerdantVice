package com.verdantvice.worker;

import com.verdantvice.farm.Farm;

import java.util.Objects;
import java.util.UUID;

/** Separates the worker's hirer/payer from the owner of the farm's assets. */
public final class WorkerFarmOwnership {
    private WorkerFarmOwnership() {
    }

    public static UUID assetOwner(Farm farm, Worker worker) {
        Objects.requireNonNull(farm, "farm");
        Objects.requireNonNull(worker, "worker");
        if (!farm.farmId().equals(worker.farmId())) {
            throw new IllegalArgumentException("worker_farm_mismatch");
        }
        return farm.ownerUuid();
    }
}
