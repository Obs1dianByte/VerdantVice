package com.verdantvice.security;

import com.verdantvice.i18n.TranslationService;
import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.util.Objects;

public final class ItemSecurityListener implements Listener {
    private final ItemSigner signer;
    private final TranslationService translations;

    public ItemSecurityListener(ItemSigner signer, TranslationService translations) {
        this.signer = Objects.requireNonNull(signer, "signer");
        this.translations = Objects.requireNonNull(translations, "translations");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        if (invalidMarked(current) || invalidMarked(cursor)) {
            event.setCancelled(true);
            if (invalidMarked(current)) {
                event.setCurrentItem(null);
            }
            if (invalidMarked(cursor)) {
                event.getView().setCursor(null);
            }
            event.getWhoClicked().sendMessage(
                    translations.component(event.getWhoClicked(), "errors.invalid-signature")
            );
            return;
        }
        boolean signed = signer.verify(current).valid() || signer.verify(cursor).valid();
        if (!signed) {
            return;
        }
        if (event.getClick() == ClickType.DOUBLE_CLICK
                || event.getClick() == ClickType.CREATIVE
                || event.getClick() == ClickType.NUMBER_KEY
                || event.getAction() == InventoryAction.COLLECT_TO_CURSOR
                || event.getAction() == InventoryAction.CLONE_STACK
                || event.getAction() == InventoryAction.HOTBAR_MOVE_AND_READD) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCreative(InventoryCreativeEvent event) {
        if (signer.hasVerdantData(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (signer.verify(event.getOldCursor()).valid()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlaceShulker(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (!(item.getItemMeta() instanceof BlockStateMeta meta)
                || !(meta.getBlockState() instanceof Container container)) {
            return;
        }
        for (ItemStack content : container.getInventory().getContents()) {
            if (signer.hasVerdantData(content)) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(
                        translations.component(event.getPlayer(), "errors.item-locked")
                );
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (containsCustom(event.getInventory().getMatrix())) {
            event.getInventory().setResult(new ItemStack(Material.AIR));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCraft(CraftItemEvent event) {
        if (containsCustom(event.getInventory().getMatrix())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onAnvil(PrepareAnvilEvent event) {
        if (containsCustom(event.getInventory().getContents())) {
            event.setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onSmithing(PrepareSmithingEvent event) {
        if (containsCustom(event.getInventory().getContents())) {
            event.setResult(null);
        }
    }

    private boolean containsCustom(ItemStack[] items) {
        for (ItemStack item : items) {
            if (signer.hasVerdantData(item)) {
                return true;
            }
        }
        return false;
    }

    private boolean invalidMarked(ItemStack item) {
        return signer.hasVerdantData(item) && !signer.verify(item).valid();
    }
}
