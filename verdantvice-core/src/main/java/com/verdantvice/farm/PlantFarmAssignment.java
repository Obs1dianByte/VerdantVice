package com.verdantvice.farm;

import com.verdantvice.common.BlockPosition;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Resolves a plant position to a farm only when the match is unambiguous. */
public final class PlantFarmAssignment {
    private PlantFarmAssignment() {
    }

    public static Optional<UUID> resolve(
            BlockPosition position,
            Collection<Farm> farms
    ) {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(farms, "farms");
        UUID matched = null;
        for (Farm farm : farms) {
            if (!farm.region().contains(position)) {
                continue;
            }
            if (matched != null && !matched.equals(farm.farmId())) {
                return Optional.empty();
            }
            matched = farm.farmId();
        }
        return Optional.ofNullable(matched);
    }
}
