package com.verdantvice.worker;

import com.verdantvice.herbalist.HerbalistListener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorkerInteractionRoutingTest {
    @Test
    void hiringContractRunsBeforeTheMerchantMenuConsumesTheInteraction() throws Exception {
        EventHandler hiring = WorkerListener.class.getMethod(
                "onContractUse", PlayerInteractEntityEvent.class)
                .getAnnotation(EventHandler.class);
        EventHandler merchant = HerbalistListener.class.getMethod(
                "onInteract", PlayerInteractEntityEvent.class)
                .getAnnotation(EventHandler.class);
        assertTrue(hiring.priority().getSlot() < merchant.priority().getSlot(),
                "The merchant cancels its event; hiring must run before it");
        assertTrue(hiring.ignoreCancelled(),
                "Hiring must not override another plugin's cancelled interaction");
        assertTrue(merchant.ignoreCancelled(),
                "A hiring interaction must not also open the merchant menu");
    }
}
