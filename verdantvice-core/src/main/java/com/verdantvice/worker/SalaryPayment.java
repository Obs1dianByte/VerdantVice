package com.verdantvice.worker;

import com.verdantvice.common.DomainChecks;

public record SalaryPayment(
        Worker worker,
        boolean paid,
        int emeraldsTaken,
        int remainingEmeralds,
        String resultCode
) {
    public SalaryPayment {
        DomainChecks.required(worker, "worker");
        if (emeraldsTaken < 0 || remainingEmeralds < 0) {
            throw new IllegalArgumentException("Emerald amounts must not be negative");
        }
        resultCode = DomainChecks.required(resultCode, "resultCode");
    }
}
