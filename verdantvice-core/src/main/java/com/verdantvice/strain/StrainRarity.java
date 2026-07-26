package com.verdantvice.strain;

public enum StrainRarity {
    BASIC(1.0),
    UNCOMMON(1.2),
    RARE(1.55),
    LEGENDARY(2.1);

    private final double marketMultiplier;

    StrainRarity(double marketMultiplier) {
        this.marketMultiplier = marketMultiplier;
    }

    public double marketMultiplier() {
        return marketMultiplier;
    }
}
