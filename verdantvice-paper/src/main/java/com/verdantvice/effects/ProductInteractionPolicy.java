package com.verdantvice.effects;

import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;

/** Air interactions deny block use by default, not use of the held custom item. */
public final class ProductInteractionPolicy {
    private ProductInteractionPolicy() { }

    public static boolean accepts(Action action, EquipmentSlot hand, Event.Result itemUse) {
        return hand == EquipmentSlot.HAND && itemUse != Event.Result.DENY
                && (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK);
    }
}
