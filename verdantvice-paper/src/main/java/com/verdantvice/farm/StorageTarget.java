package com.verdantvice.farm;

import com.verdantvice.common.BlockPosition;

import java.util.Comparator;
import java.util.Objects;
import java.util.Set;

/**
 * Canonical representation of one physical container. A double chest contains
 * both block positions but is persisted under one deterministic anchor.
 */
public record StorageTarget(BlockPosition anchor, Set<BlockPosition> positions) {
    private static final Comparator<BlockPosition> ORDER = Comparator
            .comparing(BlockPosition::worldUuid)
            .thenComparingInt(BlockPosition::x)
            .thenComparingInt(BlockPosition::y)
            .thenComparingInt(BlockPosition::z);

    public StorageTarget {
        Objects.requireNonNull(anchor, "anchor");
        positions = Set.copyOf(Objects.requireNonNull(positions, "positions"));
        if (positions.isEmpty() || !positions.contains(anchor)) {
            throw new IllegalArgumentException("Storage target must contain its anchor");
        }
    }

    public static StorageTarget of(Set<BlockPosition> positions) {
        Set<BlockPosition> snapshot = Set.copyOf(Objects.requireNonNull(positions, "positions"));
        BlockPosition anchor = snapshot.stream().min(ORDER)
                .orElseThrow(() -> new IllegalArgumentException("Storage target is empty"));
        return new StorageTarget(anchor, snapshot);
    }
}
