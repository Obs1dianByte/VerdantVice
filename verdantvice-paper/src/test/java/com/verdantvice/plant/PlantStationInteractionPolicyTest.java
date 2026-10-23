package com.verdantvice.plant;

import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlantStationInteractionPolicyTest {
    @Test
    void occupiedPotRoutesRightClickToProductionCareInspectionAndHarvest() {
        assertEquals(PlantStationInteractionPolicy.Decision.INTERACT,
                PlantStationInteractionPolicy.decide(Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND, true, false));
        assertEquals(PlantStationInteractionPolicy.Decision.INTERACT,
                PlantStationInteractionPolicy.decide(Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND, true, true));
    }

    @Test
    void onlySignedSeedPlantsAnEmptyPot() {
        assertEquals(PlantStationInteractionPolicy.Decision.PLANT,
                PlantStationInteractionPolicy.decide(Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND, false, true));
        assertEquals(PlantStationInteractionPolicy.Decision.IGNORE,
                PlantStationInteractionPolicy.decide(Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND, false, false));
    }

    @Test
    void leftClicksAndOffHandNeverPlantOrApplyCareTwice() {
        for (boolean occupied : new boolean[] {false, true}) {
            for (boolean seed : new boolean[] {false, true}) {
                assertEquals(PlantStationInteractionPolicy.Decision.IGNORE,
                        PlantStationInteractionPolicy.decide(Action.LEFT_CLICK_BLOCK, EquipmentSlot.HAND, occupied, seed));
                assertEquals(PlantStationInteractionPolicy.Decision.IGNORE,
                        PlantStationInteractionPolicy.decide(Action.RIGHT_CLICK_BLOCK, EquipmentSlot.OFF_HAND, occupied, seed));
            }
        }
    }
}
