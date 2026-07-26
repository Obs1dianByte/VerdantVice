package com.verdantvice.plant.growth;

import com.verdantvice.plant.Plant;

public record WateringResult(Plant plant, double appliedAmount, boolean overwatered) {
    public WateringResult {
        java.util.Objects.requireNonNull(plant, "plant");
        if (appliedAmount < 0.0) throw new IllegalArgumentException("appliedAmount must not be negative");
    }
}
