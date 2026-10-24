package com.verdantvice.effects;

import org.bukkit.block.BlockFace;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ProductInteractionPolicyTest {
    @Test
    void nativeAirEventIsCancelledForBlockUseButAllowsCustomItemUse() throws Exception {
        // No fake server or event dispatch: exercise the real API's air-event defaults.
        var event = new PlayerInteractEvent(null, Action.RIGHT_CLICK_AIR, null, null,
                BlockFace.SELF, EquipmentSlot.HAND);
        assertTrue(event.isCancelled());
        assertEquals(Event.Result.DEFAULT, event.useItemInHand());
        assertTrue(ProductInteractionPolicy.accepts(event.getAction(), event.getHand(), event.useItemInHand()));
        var handler = PlayerEffectListener.class.getMethod("onUse", PlayerInteractEvent.class)
                .getAnnotation(EventHandler.class);
        assertFalse(handler.ignoreCancelled(), "The listener must receive native air events");
    }

    @Test
    void explicitProtectionCancellationStillPreventsUse() {
        var event = new PlayerInteractEvent(null, Action.RIGHT_CLICK_AIR, null, null,
                BlockFace.SELF, EquipmentSlot.HAND);
        event.setCancelled(true);
        assertEquals(Event.Result.DENY, event.useItemInHand());
        assertFalse(ProductInteractionPolicy.accepts(event.getAction(), event.getHand(), event.useItemInHand()));
    }

    @Test
    void offHandAndLeftClicksDoNotConsumeProducts() {
        assertFalse(ProductInteractionPolicy.accepts(Action.RIGHT_CLICK_AIR, EquipmentSlot.OFF_HAND, Event.Result.ALLOW));
        assertFalse(ProductInteractionPolicy.accepts(Action.LEFT_CLICK_AIR, EquipmentSlot.HAND, Event.Result.DEFAULT));
        assertFalse(ProductInteractionPolicy.accepts(Action.LEFT_CLICK_BLOCK, EquipmentSlot.HAND, Event.Result.ALLOW));
        assertTrue(ProductInteractionPolicy.accepts(Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND, Event.Result.DEFAULT));
    }
}
