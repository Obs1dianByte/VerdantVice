package com.verdantvice.worker;

import com.verdantvice.common.DomainChecks;

public final class SalaryService {
    public SalaryPayment processDay(Worker worker, long minecraftDay, int availableEmeralds) {
        DomainChecks.required(worker, "worker");
        if (availableEmeralds < 0) throw new IllegalArgumentException("availableEmeralds must not be negative");
        if (minecraftDay <= worker.lastPaidMinecraftDay()) {
            return new SalaryPayment(worker, true, 0, availableEmeralds, "not-due");
        }
        if (availableEmeralds >= worker.salaryPerMinecraftDay()) {
            double morale = DomainChecks.clamp(worker.morale() + 4.0, 0.0, 100.0);
            Worker paid = MoraleService.copy(worker, morale, 0, minecraftDay, WorkerState.IDLE);
            return new SalaryPayment(paid, true, worker.salaryPerMinecraftDay(),
                    availableEmeralds - worker.salaryPerMinecraftDay(), "paid");
        }
        int unpaidDays = worker.unpaidDays() + 1;
        double morale = DomainChecks.clamp(worker.morale() - (6.0 + unpaidDays * 2.0), 0.0, 100.0);
        WorkerState state = unpaidDays >= 2 ? WorkerState.WAITING_FOR_PAYMENT : worker.state();
        Worker unpaid = MoraleService.copy(worker, morale, unpaidDays,
                worker.lastPaidMinecraftDay(), state);
        return new SalaryPayment(unpaid, false, 0, availableEmeralds, "insufficient-funds");
    }
}
