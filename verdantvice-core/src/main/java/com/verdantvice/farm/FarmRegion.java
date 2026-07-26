package com.verdantvice.farm;

import com.verdantvice.common.BlockPosition;
import com.verdantvice.common.DomainChecks;

import java.util.UUID;

public record FarmRegion(
        UUID worldUuid,
        int minimumX,
        int minimumY,
        int minimumZ,
        int maximumX,
        int maximumY,
        int maximumZ
) {
    public FarmRegion {
        DomainChecks.required(worldUuid, "worldUuid");
        if (maximumX < minimumX || maximumY < minimumY || maximumZ < minimumZ) {
            throw new IllegalArgumentException("Region maximums must not precede minimums");
        }
    }

    public static FarmRegion between(BlockPosition first, BlockPosition second) {
        DomainChecks.required(first, "first");
        DomainChecks.required(second, "second");
        if (!first.worldUuid().equals(second.worldUuid())) {
            throw new IllegalArgumentException("Farm corners must be in the same world");
        }
        return new FarmRegion(first.worldUuid(),
                Math.min(first.x(), second.x()), Math.min(first.y(), second.y()), Math.min(first.z(), second.z()),
                Math.max(first.x(), second.x()), Math.max(first.y(), second.y()), Math.max(first.z(), second.z()));
    }

    public boolean contains(BlockPosition position) {
        DomainChecks.required(position, "position");
        return worldUuid.equals(position.worldUuid())
                && position.x() >= minimumX && position.x() <= maximumX
                && position.y() >= minimumY && position.y() <= maximumY
                && position.z() >= minimumZ && position.z() <= maximumZ;
    }

    public boolean overlaps(FarmRegion other) {
        DomainChecks.required(other, "other");
        return worldUuid.equals(other.worldUuid())
                && minimumX <= other.maximumX && maximumX >= other.minimumX
                && minimumY <= other.maximumY && maximumY >= other.minimumY
                && minimumZ <= other.maximumZ && maximumZ >= other.minimumZ;
    }

    public long blockVolume() {
        long x = (long) maximumX - minimumX + 1L;
        long y = (long) maximumY - minimumY + 1L;
        long z = (long) maximumZ - minimumZ + 1L;
        return Math.multiplyExact(Math.multiplyExact(x, y), z);
    }
}
