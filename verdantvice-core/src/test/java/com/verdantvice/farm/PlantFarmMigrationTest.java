package com.verdantvice.farm;

import com.verdantvice.common.BlockPosition;
import com.verdantvice.plant.GrowthStage;
import com.verdantvice.plant.Plant;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlantFarmMigrationTest {
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID FARM_ID = UUID.fromString("00000000-0000-0000-0000-000000000103");

    @Test
    void missingFarmIdIsAssignedByPosition() {
        Plant repaired = PlantFarmMigration.repair(plant(5, Optional.empty()), List.of(farm()))
                .orElseThrow();
        assertEquals(Optional.of(FARM_ID), repaired.farmId());
    }

    @Test
    void migrationIsIdempotent() {
        Plant repaired = PlantFarmMigration.repair(plant(5, Optional.empty()), List.of(farm()))
                .orElseThrow();
        assertTrue(PlantFarmMigration.repair(repaired, List.of(farm())).isEmpty());
    }

    @Test
    void ownerUuidIsNeverChanged() {
        Plant source = plant(5, Optional.empty());
        Plant repaired = PlantFarmMigration.repair(source, List.of(farm())).orElseThrow();
        assertEquals(source.ownerUuid(), repaired.ownerUuid());
    }

    @Test
    void plantOutsideFarmIsNotGuessed() {
        assertTrue(PlantFarmMigration.repair(
                plant(50, Optional.empty()), List.of(farm())).isEmpty());
    }

    private Plant plant(int coordinate, Optional<UUID> farmId) {
        Instant planted = Instant.parse("2026-01-01T00:00:00Z");
        return new Plant(
                UUID.randomUUID(), OWNER,
                new BlockPosition(WORLD, coordinate, 64, coordinate),
                "test-strain", UUID.randomUUID(), GrowthStage.SEED,
                0.0, 100.0, 75.0, 75.0, 12, 0.0, 100.0,
                Optional.empty(), 0.0, 50.0, 50.0, 50.0,
                planted, planted, planted,
                planted.plusSeconds(3600), planted.plusSeconds(4200),
                farmId, Optional.empty()
        );
    }

    private Farm farm() {
        FarmMember owner = new FarmMember(
                OWNER, FarmRole.OWNER, Set.of(), Set.of(), Instant.EPOCH);
        return new Farm(
                FARM_ID, OWNER, "test",
                new FarmRegion(WORLD, 0, 0, 0, 10, 255, 10),
                1, 64, 5, Set.of(), Set.of(), Set.of(), Optional.empty(),
                Instant.EPOCH, FarmSettings.defaults(), Map.of(OWNER, owner)
        );
    }
}
