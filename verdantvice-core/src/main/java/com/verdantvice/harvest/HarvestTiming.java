package com.verdantvice.harvest;

public enum HarvestTiming {
    TOO_EARLY(0.25, 0.35),
    EARLY(0.72, 0.80),
    OPTIMAL(1.0, 1.0),
    PERFECT(1.08, 1.08),
    LATE(0.92, 0.88),
    OVERRIPE(0.68, 0.55);

    private final double yieldMultiplier;
    private final double qualityMultiplier;

    HarvestTiming(double yieldMultiplier, double qualityMultiplier) {
        this.yieldMultiplier = yieldMultiplier;
        this.qualityMultiplier = qualityMultiplier;
    }

    public double yieldMultiplier() {
        return yieldMultiplier;
    }

    public double qualityMultiplier() {
        return qualityMultiplier;
    }
}
