package com.verdantvice.contract;

import java.util.Objects;

public record ContractClaimResult(
        Status status,
        Contract contract,
        ContractReward reward,
        String reasonCode
) {
    public ContractClaimResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(reward, "reward");
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
    }

    public enum Status {
        CLAIMED,
        ALREADY_CLAIMED,
        REJECTED
    }
}
