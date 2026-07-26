package com.verdantvice.contract;

public record StoredContract(Contract contract, boolean rewardClaimed, String rewardOperationId, long version) {
    public StoredContract {
        java.util.Objects.requireNonNull(contract, "contract");
        if (version < 0L) throw new IllegalArgumentException("version cannot be negative");
    }
}
