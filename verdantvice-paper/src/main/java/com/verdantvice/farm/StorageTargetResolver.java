package com.verdantvice.farm;

import com.verdantvice.common.BlockPosition;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.inventory.InventoryHolder;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/** Resolves Bukkit containers without treating the two halves of a double chest as separate storage. */
public final class StorageTargetResolver {
    private StorageTargetResolver() {
    }

    public static Optional<StorageTarget> resolve(Block block) {
        if (block == null || !(block.getState() instanceof Container state)) {
            return Optional.empty();
        }
        Set<BlockPosition> positions = new LinkedHashSet<>();
        positions.add(position(block));
        if (state instanceof Chest chest
                && chest.getInventory().getHolder() instanceof DoubleChest doubleChest) {
            addChest(positions, doubleChest.getLeftSide());
            addChest(positions, doubleChest.getRightSide());
        }
        return Optional.of(StorageTarget.of(positions));
    }

    private static void addChest(Set<BlockPosition> positions, InventoryHolder holder) {
        if (holder instanceof Chest chest) {
            positions.add(position(chest.getBlock()));
        }
    }

    private static BlockPosition position(Block block) {
        return new BlockPosition(
                block.getWorld().getUID(),
                block.getX(),
                block.getY(),
                block.getZ()
        );
    }
}
