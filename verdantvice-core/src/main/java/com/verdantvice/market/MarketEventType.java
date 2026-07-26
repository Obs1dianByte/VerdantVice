package com.verdantvice.market;

public enum MarketEventType {
    NONE(1.0),
    HIGH_DEMAND(1.35),
    OVERSUPPLY(0.72),
    RARE_SEED_SHORTAGE(1.22),
    PREMIUM_PRODUCT_WEEK(1.18),
    MARKET_INSPECTION(0.82);

    private final double defaultMultiplier;

    MarketEventType(double defaultMultiplier) {
        this.defaultMultiplier = defaultMultiplier;
    }

    public double defaultMultiplier() {
        return defaultMultiplier;
    }
}
