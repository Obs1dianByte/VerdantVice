package com.verdantvice.worker;

import com.verdantvice.common.DomainChecks;

public final class WorkerProgressionService {
    public Worker grantExperience(Worker worker, long gainedExperience) {
        DomainChecks.required(worker, "worker");
        if (gainedExperience < 0L) throw new IllegalArgumentException("gainedExperience must not be negative");
        long total = Math.addExact(worker.experience(), gainedExperience);
        int level = Math.min(100, 1 + (int) Math.floor(Math.sqrt(total / 100.0)));
        return new Worker(worker.workerUuid(), worker.ownerUuid(), worker.farmId(),
                worker.specialization(), level, total, worker.morale(), worker.salaryPerMinecraftDay(),
                worker.unpaidDays(), worker.lastPaidMinecraftDay(), worker.state(), worker.activeTaskId());
    }

    public double taskEfficiency(Worker worker, WorkerTaskType taskType) {
        DomainChecks.required(worker, "worker");
        DomainChecks.required(taskType, "taskType");
        return worker.workSpeedMultiplier() * worker.specialization().bonusFor(taskType);
    }
}
