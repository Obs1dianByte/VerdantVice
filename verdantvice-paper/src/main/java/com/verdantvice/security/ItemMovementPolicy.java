package com.verdantvice.security;

import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;

import java.util.Objects;
import java.util.UUID;

/** Pure decision layer used by ItemSecurityListener. */
public final class ItemMovementPolicy {
    public boolean blocksClick(
            InventoryAction action,
            ClickType click,
            Identity current,
            Identity cursor,
            Identity hotbar,
            int signedItemsInView,
            int matchingCopiesInView,
            boolean nestedSignedContainer
    ) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(click, "click");
        current = safe(current);
        cursor = safe(cursor);
        hotbar = safe(hotbar);
        if (current.invalid() || cursor.invalid() || hotbar.invalid()) return true;
        boolean involved = current.valid() || cursor.valid() || hotbar.valid()
                || current.marked() || cursor.marked() || hotbar.marked()
                || nestedSignedContainer;
        if (!involved) return false;
        if (action == InventoryAction.CLONE_STACK || click == ClickType.MIDDLE) return true;
        if (action == InventoryAction.COLLECT_TO_CURSOR || click == ClickType.DOUBLE_CLICK) {
            return signedItemsInView > 1;
        }
        if (click == ClickType.NUMBER_KEY
                || action == InventoryAction.HOTBAR_MOVE_AND_READD) {
            return sameInstance(current, hotbar)
                    || sameInstance(cursor, hotbar)
                    || matchingCopiesInView > 1;
        }
        return sameInstance(current, cursor) || matchingCopiesInView > 1;
    }

    public boolean blocksCreative(
            InventoryAction action,
            ClickType click,
            Identity current,
            Identity cursor,
            int matchingCopiesInView,
            boolean nestedSignedContainer
    ) {
        current = safe(current);
        cursor = safe(cursor);
        if (current.invalid() || cursor.invalid()) return true;
        boolean involved = current.marked() || cursor.marked() || nestedSignedContainer;
        if (!involved) return false;
        if (action == InventoryAction.CLONE_STACK || click == ClickType.MIDDLE) return true;
        if (sameInstance(current, cursor)) return true;
        return cursor.valid() && matchingCopiesInView > 0;
    }

    public boolean blocksDrag(
            Identity oldCursor,
            int targetCount,
            boolean allTargetsKeepIdentityAndAmountOne,
            int matchingCopiesInView
    ) {
        oldCursor = safe(oldCursor);
        if (oldCursor.invalid()) return true;
        if (!oldCursor.valid()) return false;
        return targetCount != 1
                || !allTargetsKeepIdentityAndAmountOne
                || matchingCopiesInView > 0;
    }

    private static boolean sameInstance(Identity first, Identity second) {
        return first.valid() && second.valid() && Objects.equals(first.instanceId(), second.instanceId());
    }

    private static Identity safe(Identity identity) {
        return identity == null ? Identity.unmarked() : identity;
    }

    public record Identity(boolean marked, boolean valid, UUID instanceId) {
        public Identity {
            if (valid && instanceId == null) {
                throw new IllegalArgumentException("valid identity requires an instance id");
            }
        }

        public boolean invalid() {
            return marked && !valid;
        }

        public static Identity unmarked() {
            return new Identity(false, false, null);
        }

        public static Identity invalidMarked() {
            return new Identity(true, false, null);
        }

        public static Identity valid(UUID instanceId) {
            return new Identity(true, true, Objects.requireNonNull(instanceId, "instanceId"));
        }
    }
}
