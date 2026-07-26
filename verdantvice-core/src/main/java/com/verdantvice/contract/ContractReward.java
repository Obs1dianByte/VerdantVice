package com.verdantvice.contract;

import com.verdantvice.common.DomainChecks;

public record ContractReward(int emeralds, double economyAmount, double reputation) {
    public ContractReward {
        emeralds = DomainChecks.range(emeralds, 0, 1_000_000, "emeralds");
        economyAmount = DomainChecks.range(economyAmount, 0.0, 1_000_000_000.0, "economyAmount");
        reputation = DomainChecks.range(reputation, 0.0, 100.0, "reputation");
        if (emeralds == 0 && economyAmount == 0.0 && reputation == 0.0) {
            throw new IllegalArgumentException("Contract reward must contain at least one benefit");
        }
    }
}
