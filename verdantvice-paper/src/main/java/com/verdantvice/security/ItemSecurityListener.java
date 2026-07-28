package com.verdantvice.security;

import com.verdantvice.i18n.TranslationService;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class ItemSecurityListener implements Listener {
    private final ItemSigner signer;
    private final TranslationService translations;
    private final ItemMovementPolicy movement = new ItemMovementPolicy();

    public ItemSecurityListener(ItemSigner signer, TranslationService translations) {
        this.signer = Objects.requireNonNull(signer, "signer");
        this.translations = Objects.requireNonNull(translations, "translations");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        ItemStack hotbar = event.getHotbarButton() >= 0
                ? event.getWhoClicked().getInventory().getItem(event.getHotbarButton())
                : null;
        ItemMovementPolicy.Identity currentIdentity = identity(current);
        ItemMovementPolicy.Identity cursorIdentity = identity(cursor);
        ItemMovementPolicy.Identity hotbarIdentity = identity(hotbar);
        UUID involvedId = firstId(currentIdentity, cursorIdentity, hotbarIdentity);
        boolean blocked = movement.blocksClick(
                event.getAction(), event.getClick(), currentIdentity, cursorIdentity, hotbarIdentity,
                signedCount(event.getView().getTopInventory(), event.getView().getBottomInventory()),
                count(event.getView().getTopInventory(), event.getView().getBottomInventory(), involvedId),
                containsSignedNested(current) || containsSignedNested(cursor) || containsSignedNested(hotbar));
        if (!blocked) return;
        event.setCancelled(true);
        if (currentIdentity.invalid() || cursorIdentity.invalid() || hotbarIdentity.invalid()) {
            event.getWhoClicked().sendMessage(
                    translations.component(event.getWhoClicked(), "errors.invalid-signature"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCreative(InventoryCreativeEvent event) {
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        ItemMovementPolicy.Identity currentIdentity = identity(current);
        ItemMovementPolicy.Identity cursorIdentity = identity(cursor);
        UUID cursorId = cursorIdentity.instanceId();
        if (movement.blocksCreative(
                event.getAction(), event.getClick(), currentIdentity, cursorIdentity,
                count(event.getView().getTopInventory(), event.getView().getBottomInventory(), cursorId),
                containsSignedNested(current) || containsSignedNested(cursor))) {
            event.setCancelled(true);
            if (currentIdentity.invalid() || cursorIdentity.invalid()) {
                event.getWhoClicked().sendMessage(
                        translations.component(event.getWhoClicked(), "errors.invalid-signature"));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        ItemMovementPolicy.Identity old = identity(event.getOldCursor());
        boolean same = event.getNewItems().values().stream().allMatch(item -> {
            ItemMovementPolicy.Identity target = identity(item);
            return item.getAmount() == 1 && target.valid()
                    && Objects.equals(old.instanceId(), target.instanceId());
        });
        int copies = count(event.getView().getTopInventory(), event.getView().getBottomInventory(),
                old.instanceId());
        if (movement.blocksDrag(old, event.getNewItems().size(), same, copies)) {
            event.setCancelled(true);
            if (old.invalid()) {
                event.getWhoClicked().sendMessage(
                        translations.component(event.getWhoClicked(), "errors.invalid-signature"));
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
        if (containsCustom(event.getInventory().getMatrix())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onAnvil(PrepareAnvilEvent event) {
        if (containsCustom(event.getInventory().getContents())) event.setResult(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onSmithing(PrepareSmithingEvent event) {
        if (containsCustom(event.getInventory().getContents())) event.setResult(null);
    }

    private ItemMovementPolicy.Identity identity(ItemStack item) {
        if (!signer.hasVerdantData(item)) return ItemMovementPolicy.Identity.unmarked();
        ItemSigner.Verification verification = signer.verify(item);
        return verification.valid()
                ? ItemMovementPolicy.Identity.valid(verification.data().uniqueInstanceId())
                : ItemMovementPolicy.Identity.invalidMarked();
    }

    private int signedCount(Inventory... inventories) {
        int result = 0;
        for (Inventory inventory : inventories) {
            if (inventory == null) continue;
            for (ItemStack item : inventory.getContents()) {
                if (identity(item).valid()) result++;
            }
        }
        return result;
    }

    private int count(Inventory first, Inventory second, UUID instanceId) {
        if (instanceId == null) return 0;
        int result = 0;
        for (Inventory inventory : new Inventory[]{first, second}) {
            if (inventory == null) continue;
            for (ItemStack item : inventory.getContents()) {
                ItemMovementPolicy.Identity identity = identity(item);
                if (identity.valid() && instanceId.equals(identity.instanceId())) result++;
            }
        }
        return result;
    }

    private static UUID firstId(ItemMovementPolicy.Identity... identities) {
        for (ItemMovementPolicy.Identity identity : identities) {
            if (identity != null && identity.valid()) return identity.instanceId();
        }
        return null;
    }

    private boolean containsCustom(ItemStack[] items) {
        for (ItemStack item : items) if (signer.hasVerdantData(item)) return true;
        return false;
    }

    private boolean containsSignedNested(ItemStack item) {
        if (item == null || !(item.getItemMeta() instanceof BlockStateMeta meta)) return false;
        if (!(meta.getBlockState() instanceof org.bukkit.block.Container container)) return false;
        for (ItemStack nested : container.getInventory().getContents()) {
            if (signer.hasVerdantData(nested)) return true;
        }
        return false;
    }
}
