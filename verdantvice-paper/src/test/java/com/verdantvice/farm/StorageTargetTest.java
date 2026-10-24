package com.verdantvice.farm;

import com.verdantvice.common.BlockPosition;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class StorageTargetTest {
    @Test
    void eitherHalfOfDoubleChestResolvesToOneDeterministicAnchor() {
        UUID world = UUID.randomUUID();
        BlockPosition left = new BlockPosition(world, 10, 64, 20);
        BlockPosition right = new BlockPosition(world, 11, 64, 20);
        StorageTarget fromLeft = StorageTarget.of(Set.of(left, right));
        StorageTarget fromRight = StorageTarget.of(Set.of(right, left));
        assertEquals(left, fromLeft.anchor());
        assertEquals(fromLeft, fromRight);
        assertEquals(2, fromLeft.positions().size());
    }
}
