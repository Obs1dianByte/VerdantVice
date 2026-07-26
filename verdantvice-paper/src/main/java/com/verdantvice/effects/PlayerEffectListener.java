package com.verdantvice.effects;

import com.verdantvice.security.CustomItemType;
import com.verdantvice.security.ItemSigner;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Objects;

public final class PlayerEffectListener implements Listener {
    private final PlayerEffectManager effects;
    private final ItemSigner signer;

    public PlayerEffectListener(PlayerEffectManager effects, ItemSigner signer) {
        this.effects = Objects.requireNonNull(effects, "effects");
        this.signer = Objects.requireNonNull(signer, "signer");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        ItemSigner.Verification verification = signer.verify(event.getItem());
        if (!verification.valid()
                || verification.data().itemType() != CustomItemType.JOINT) {
            return;
        }
        event.setCancelled(true);
        effects.use(event.getPlayer(), Objects.requireNonNull(event.getItem()).clone());
    }
}
