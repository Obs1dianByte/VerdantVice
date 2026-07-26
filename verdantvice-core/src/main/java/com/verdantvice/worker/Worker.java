package com.verdantvice.worker;

import com.verdantvice.common.DomainChecks;

import java.util.Optional;
import java.util.UUID;

public record Worker(
        UUID workerUuid,
        UUID ownerUuid,
        UUID farmId,
        WorkerSpecialization specialization,
        int level,
        long experience,
        double morale,
        int salaryPerMinecraftDay,
        int unpaidDays,
        long lastPaidMinecraftDay,
        WorkerState state,
        Optional<UUID> activeTaskId
) {
    public Worker {
        DomainChecks.required(workerUuid, "workerUuid");
        DomainChecks.required(ownerUuid, "ownerUuid");
        DomainChecks.required(farmId, "farmId");
        DomainChecks.required(specialization, "specialization");
        level = DomainChecks.range(level, 1, 100, "level");
        if (experience < 0L) throw new IllegalArgumentException("experience must not be negative");
        morale = DomainChecks.range(morale, 0.0, 100.0, "morale");
        salaryPerMinecraftDay = DomainChecks.range(salaryPerMinecraftDay, 1, 1_000_000,
                "salaryPerMinecraftDay");
        unpaidDays = DomainChecks.range(unpaidDays, 0, 100_000, "unpaidDays");
        DomainChecks.required(state, "state");
        activeTaskId = DomainChecks.required(activeTaskId, "activeTaskId");
    }

    public static Worker hired(UUID workerUuid, UUID ownerUuid, UUID farmId,
                               WorkerSpecialization specialization, long currentMinecraftDay) {
        return new Worker(workerUuid, ownerUuid, farmId, specialization, 1, 0L, 75.0,
                4, 0, currentMinecraftDay - 1L, WorkerState.IDLE, Optional.empty());
    }

    public double workSpeedMultiplier() {
        double moraleFactor = 0.5 + morale / 200.0;
        double unpaidPenalty = unpaidDays >= 2 ? 0.7 : 1.0;
        return moraleFactor * unpaidPenalty * (1.0 + (level - 1) * 0.015);
    }

    public boolean refusesNonCriticalTasks() {
        return unpaidDays >= 3 || morale < 15.0;
    }

    public double leavingChance() {
        if (unpaidDays < 4 && morale >= 20.0) return 0.0;
        return DomainChecks.clamp((unpaidDays - 3) * 0.08 + (20.0 - morale) / 100.0, 0.0, 0.85);
    }
}
