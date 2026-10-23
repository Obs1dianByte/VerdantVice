package com.verdantvice.gui;

import com.verdantvice.common.BlockPosition;
import com.verdantvice.plant.GrowthStage;
import com.verdantvice.plant.Plant;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarmScopedDataTest {
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000402");
    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000403");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000404");

    @Test
    void plantsFromAnotherFarmAreExcluded() {
        List<Plant> result = FarmScopedData.plants(
                FIRST,
                List.of(plant(Optional.of(FIRST)), plant(Optional.of(SECOND)))
        );
        assertEquals(1, result.size());
        assertEquals(Optional.of(FIRST), result.get(0).farmId());
    }

    @Test
    void unassignedPlantsAreExcludedFromFarmMenu() {
        assertTrue(FarmScopedData.plants(
                FIRST,
                List.of(plant(Optional.empty()))
        ).isEmpty());
    }

    @Test
    void selectedFarmPlantCountMatchesSelectedFarmList() {
        List<Plant> source = List.of(
                plant(Optional.of(FIRST)),
                plant(Optional.of(FIRST)),
                plant(Optional.of(SECOND))
        );
        assertEquals(2, FarmScopedData.plants(FIRST, source).size());
    }

    private Plant plant(Optional<UUID> farmId) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new Plant(
                UUID.randomUUID(), OWNER,
                new BlockPosition(WORLD, 1, 64, 1),
                "test-strain", UUID.randomUUID(), GrowthStage.SEED,
                0.0, 100.0, 75.0, 75.0, 12, 0.0, 100.0,
                Optional.empty(), 0.0, 50.0, 50.0, 50.0,
                now, now, now, now.plusSeconds(60), now.plusSeconds(120),
                farmId, Optional.empty()
        );
    }
}
