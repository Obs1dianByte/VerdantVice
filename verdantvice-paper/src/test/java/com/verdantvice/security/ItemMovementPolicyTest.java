package com.verdantvice.security;

import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemMovementPolicyTest {
    private final ItemMovementPolicy policy = new ItemMovementPolicy();
    private final UUID firstId = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private final UUID secondId = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private final ItemMovementPolicy.Identity first = ItemMovementPolicy.Identity.valid(firstId);
    private final ItemMovementPolicy.Identity second = ItemMovementPolicy.Identity.valid(secondId);
    private final ItemMovementPolicy.Identity empty = ItemMovementPolicy.Identity.unmarked();

    @Test
    void ordinaryLeftClickMoveIsAllowed() {
        assertFalse(click(InventoryAction.PICKUP_ALL, ClickType.LEFT, first, empty, empty, 1, 1));
    }

    @Test
    void ordinaryRightClickMoveIsAllowed() {
        assertFalse(click(InventoryAction.PICKUP_HALF, ClickType.RIGHT, first, empty, empty, 1, 1));
    }

    @Test
    void shiftClickToAnotherInventoryIsAllowed() {
        assertFalse(click(InventoryAction.MOVE_TO_OTHER_INVENTORY, ClickType.SHIFT_LEFT,
                first, empty, empty, 1, 1));
    }

    @Test
    void legalHotbarSwapIsAllowed() {
        assertFalse(click(InventoryAction.HOTBAR_SWAP, ClickType.NUMBER_KEY,
                first, empty, second, 2, 1));
    }

    @Test
    void legalHotbarMoveAndReaddIsAllowed() {
        assertFalse(click(InventoryAction.HOTBAR_MOVE_AND_READD, ClickType.NUMBER_KEY,
                first, empty, second, 2, 1));
    }

    @Test
    void legalSingleTargetDragIsAllowed() {
        assertFalse(policy.blocksDrag(first, 1, true, 0));
    }

    @Test
    void legalCreativeMoveWithoutExistingCopyIsAllowed() {
        assertFalse(policy.blocksCreative(InventoryAction.PLACE_ALL, ClickType.CREATIVE,
                empty, first, 0, false));
    }

    @Test
    void creativeMiddleClickCloneIsBlocked() {
        assertTrue(policy.blocksCreative(InventoryAction.CLONE_STACK, ClickType.MIDDLE,
                first, first, 1, false));
    }

    @Test
    void cloneStackActionIsBlocked() {
        assertTrue(click(InventoryAction.CLONE_STACK, ClickType.MIDDLE,
                first, empty, empty, 1, 1));
    }

    @Test
    void invalidSignedItemIsBlocked() {
        assertTrue(click(InventoryAction.PICKUP_ALL, ClickType.LEFT,
                ItemMovementPolicy.Identity.invalidMarked(), empty, empty, 0, 0));
    }

    @Test
    void duplicateInstanceCannotBeCombinedOrMoved() {
        assertTrue(click(InventoryAction.SWAP_WITH_CURSOR, ClickType.LEFT,
                first, first, empty, 2, 2));
    }

    @Test
    void dragCannotSplitUniqueItemAcrossSeveralSlots() {
        assertTrue(policy.blocksDrag(first, 2, true, 0));
    }

    @Test
    void collectToCursorIsBlockedWhenSeveralSignedItemsWouldBeCollected() {
        assertTrue(click(InventoryAction.COLLECT_TO_CURSOR, ClickType.DOUBLE_CLICK,
                first, empty, empty, 2, 1));
    }

    @Test
    void shulkerCloneContainingSignedItemIsBlocked() {
        assertTrue(click(InventoryAction.CLONE_STACK, ClickType.MIDDLE,
                empty, empty, empty, 0, 0, true));
    }

    private boolean click(
            InventoryAction action, ClickType click,
            ItemMovementPolicy.Identity current,
            ItemMovementPolicy.Identity cursor,
            ItemMovementPolicy.Identity hotbar,
            int signed, int matching
    ) {
        return click(action, click, current, cursor, hotbar, signed, matching, false);
    }

    private boolean click(
            InventoryAction action, ClickType click,
            ItemMovementPolicy.Identity current,
            ItemMovementPolicy.Identity cursor,
            ItemMovementPolicy.Identity hotbar,
            int signed, int matching, boolean nested
    ) {
        return policy.blocksClick(action, click, current, cursor, hotbar,
                signed, matching, nested);
    }
}
