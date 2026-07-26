package com.verdantvice.trade;

import com.verdantvice.common.DomainChecks;

public record TradeDecision(
        boolean accepted,
        String reasonCode,
        double payout,
        TradeLedger ledger
) {
    public TradeDecision {
        reasonCode = DomainChecks.required(reasonCode, "reasonCode");
        payout = DomainChecks.range(payout, 0.0, 1_000_000_000.0, "payout");
        DomainChecks.required(ledger, "ledger");
        if (!accepted && reasonCode.isBlank()) {
            throw new IllegalArgumentException("Rejected trade requires a reason code");
        }
    }
}
