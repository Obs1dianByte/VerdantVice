package com.verdantvice.worker;

import com.verdantvice.common.DomainChecks;

public final class MoraleService {
    public Worker applyDailyFactors(Worker worker, MoraleFactors factors) {
        DomainChecks.required(worker, "worker");
        DomainChecks.required(factors, "factors");
        double delta = 0.0;
        delta += factors.paid() ? 4.0 : -8.0;
        delta += factors.hasBed() ? 2.0 : -5.0;
        delta += factors.hasFood() ? 2.0 : -4.0;
        delta += factors.safe() ? 2.0 : -5.0;
        delta += factors.receivedBreak() ? 1.5 : -2.5;
        if (factors.assignedTasks() > factors.reasonableTaskLimit()) {
            delta -= Math.min(12.0, (factors.assignedTasks() - factors.reasonableTaskLimit()) * 0.75);
        } else {
            delta += 1.0;
        }
        if (factors.monstersNearby()) delta -= 5.0;
        if (factors.missingTools()) delta -= 4.0;
        if (factors.nightWork()) delta -= 3.0;
        delta -= Math.min(10.0, factors.blockedTasks() * 1.5);
        if (factors.coworkerDied()) delta -= 12.0;
        return copy(worker, DomainChecks.clamp(worker.morale() + delta, 0.0, 100.0),
                worker.unpaidDays(), worker.lastPaidMinecraftDay(), worker.state());
    }

    static Worker copy(Worker worker, double morale, int unpaidDays, long lastPaidDay, WorkerState state) {
        return new Worker(worker.workerUuid(), worker.ownerUuid(), worker.farmId(),
                worker.specialization(), worker.level(), worker.experience(), morale,
                worker.salaryPerMinecraftDay(), unpaidDays, lastPaidDay, state, worker.activeTaskId());
    }
}
