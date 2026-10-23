package com.verdantvice.plant;

import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;

/** Pot clicks remain usable even when a small plant model is difficult to target. */
final class PlantStationInteractionPolicy {
    private PlantStationInteractionPolicy() {}

    static Decision decide(Action action, EquipmentSlot hand, boolean occupied, boolean signedSeed) {
        if (action != Action.RIGHT_CLICK_BLOCK || hand != EquipmentSlot.HAND) return Decision.IGNORE;
        if (occupied) return Decision.INTERACT;
        return signedSeed ? Decision.PLANT : Decision.IGNORE;
    }

    enum Decision { IGNORE, INTERACT, PLANT }
}
