package com.verdantvice.contract;

import java.util.Objects;

public record ContractDeliveryResult(
        Status status,
        Contract contract,
        String reasonCode,
        boolean newlyCompleted
) {
    public ContractDeliveryResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(contract, "contract");
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
    }

    public enum Status {
        APPLIED,
        ALREADY_APPLIED,
        REJECTED
    }
}
