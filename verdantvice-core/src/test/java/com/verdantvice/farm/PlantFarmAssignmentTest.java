package com.verdantvice.farm;

import com.verdantvice.common.BlockPosition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlantFarmAssignmentTest {
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void positionInsideFarmReceivesFarmId() {
        Farm farm = farm(UUID.fromString("00000000-0000-0000-0000-000000000010"), 0, 10);
        assertEquals(Optional.of(farm.farmId()), PlantFarmAssignment.resolve(
                new BlockPosition(WORLD, 5, 64, 5), List.of(farm)));
    }

    @Test
    void positionOutsideEveryFarmRemainsUnassigned() {
        Farm farm = farm(UUID.fromString("00000000-0000-0000-0000-000000000010"), 0, 10);
        assertTrue(PlantFarmAssignment.resolve(
                new BlockPosition(WORLD, 20, 64, 20), List.of(farm)).isEmpty());
    }

    @Test
    void inclusiveBoundaryReceivesFarmId() {
        Farm farm = farm(UUID.fromString("00000000-0000-0000-0000-000000000010"), 0, 10);
        assertEquals(Optional.of(farm.farmId()), PlantFarmAssignment.resolve(
                new BlockPosition(WORLD, 10, 80, 10), List.of(farm)));
    }

    @Test
    void differentWorldDoesNotMatch() {
        Farm farm = farm(UUID.fromString("00000000-0000-0000-0000-000000000010"), 0, 10);
        assertTrue(PlantFarmAssignment.resolve(
                new BlockPosition(UUID.randomUUID(), 5, 64, 5), List.of(farm)).isEmpty());
    }

    @Test
    void ambiguousOverlappingRegionsAreNeverGuessed() {
        Farm first = farm(UUID.fromString("00000000-0000-0000-0000-000000000010"), 0, 10);
        Farm second = farm(UUID.fromString("00000000-0000-0000-0000-000000000011"), 5, 15);
        assertTrue(PlantFarmAssignment.resolve(
                new BlockPosition(WORLD, 7, 64, 7), List.of(first, second)).isEmpty());
    }

    private Farm farm(UUID id, int minimum, int maximum) {
        FarmMember owner = new FarmMember(
                OWNER,
                FarmRole.OWNER,
                Set.of(),
                Set.of(),
                Instant.EPOCH
        );
        return new Farm(
                id,
                OWNER,
                "test",
                new FarmRegion(WORLD, minimum, 0, minimum, maximum, 255, maximum),
                1,
                64,
                5,
                Set.of(),
                Set.of(),
                Set.of(),
                Optional.empty(),
                Instant.EPOCH,
                FarmSettings.defaults(),
                Map.of(OWNER, owner)
        );
    }
}
