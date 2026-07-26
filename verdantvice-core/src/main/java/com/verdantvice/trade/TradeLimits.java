package com.verdantvice.trade;

import com.verdantvice.common.DomainChecks;

public record TradeLimits(int maximumBatches, double maximumWeight, double maximumPayout) {
    public TradeLimits {
        maximumBatches = DomainChecks.range(maximumBatches, 1, 1_000_000, "maximumBatches");
        maximumWeight = DomainChecks.positive(maximumWeight, "maximumWeight");
        maximumPayout = DomainChecks.positive(maximumPayout, "maximumPayout");
    }
}
