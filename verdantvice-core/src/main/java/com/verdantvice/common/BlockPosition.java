package com.verdantvice.common;

import java.util.UUID;

import static com.verdantvice.common.DomainChecks.required;

/**
 * Bukkit-independent persistent block coordinate.
 */
public record BlockPosition(UUID worldUuid, int x, int y, int z) {
    public BlockPosition {
        required(worldUuid, "worldUuid");
    }

    public long chunkKey() {
        int chunkX = Math.floorDiv(x, 16);
        int chunkZ = Math.floorDiv(z, 16);
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }

    public double squaredDistance(BlockPosition other) {
        required(other, "other");
        if (!worldUuid.equals(other.worldUuid)) {
            return Double.POSITIVE_INFINITY;
        }
        long dx = (long) x - other.x;
        long dy = (long) y - other.y;
        long dz = (long) z - other.z;
        return (double) dx * dx + (double) dy * dy + (double) dz * dz;
    }
}
