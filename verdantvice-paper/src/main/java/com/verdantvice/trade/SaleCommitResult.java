package com.verdantvice.trade;

import java.util.Objects;

public record SaleCommitResult(Status status, int payout, String reasonCode) {
    public SaleCommitResult {
        status = Objects.requireNonNull(status, "status");
        if (payout < 0) throw new IllegalArgumentException("payout cannot be negative");
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
    }

    public boolean committed() {
        return status == Status.COMMITTED || status == Status.ALREADY_COMMITTED;
    }

    public enum Status {
        COMMITTED,
        ALREADY_COMMITTED,
        PRICE_CHANGED,
        REJECTED
    }
}
