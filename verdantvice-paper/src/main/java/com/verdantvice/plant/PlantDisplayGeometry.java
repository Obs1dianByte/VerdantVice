package com.verdantvice.plant;

/** Keeps both the visible model and its click target above the solid planting pot. */
final class PlantDisplayGeometry {
    private static final double POT_TOP = 1.0;
    private static final double CLEARANCE = 0.02;

    private PlantDisplayGeometry() {}

    static double anchorY(double stationY) {
        return stationY + POT_TOP + CLEARANCE;
    }

    static float modelScale(GrowthStage stage) {
        return switch (stage) {
            case SEED -> 0.30f;
            case SPROUT -> 0.45f;
            case SEEDLING -> 0.60f;
            case VEGETATIVE -> 0.80f;
            case MATURING -> 0.95f;
            case READY -> 1.05f;
            case OVERRIPE, SPOILED -> 0.90f;
        };
    }

    static float modelLift(GrowthStage stage) {
        return modelScale(stage) / 2.0f;
    }
}
