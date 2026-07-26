package com.verdantvice.contract;

import com.verdantvice.common.DomainChecks;

public record ContractUpdate(
        Contract contract,
        boolean accepted,
        String reasonCode,
        boolean newlyCompleted
) {
    public ContractUpdate {
        DomainChecks.required(contract, "contract");
        reasonCode = DomainChecks.required(reasonCode, "reasonCode");
        if (!accepted && reasonCode.isBlank()) {
            throw new IllegalArgumentException("Rejected update requires a reason code");
        }
    }
}
