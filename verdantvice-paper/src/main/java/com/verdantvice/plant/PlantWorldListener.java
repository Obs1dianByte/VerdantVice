package com.verdantvice.plant;

import com.verdantvice.i18n.TranslationService;
import com.verdantvice.security.CustomItemType;
import com.verdantvice.security.ItemSigner;
import com.verdantvice.security.SecureItemService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;

public final class PlantWorldListener implements Listener {
    private final JavaPlugin plugin;
    private final PlantManager plants;
    private final ItemSigner signer;
    private final SecureItemService secureItems;
    private final TranslationService translations;

    public PlantWorldListener(
            JavaPlugin plugin,
            PlantManager plants,
            ItemSigner signer,
            SecureItemService secureItems,
            TranslationService translations
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.plants = Objects.requireNonNull(plants, "plants");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.secureItems = Objects.requireNonNull(secureItems, "secureItems");
        this.translations = Objects.requireNonNull(translations, "translations");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        ItemSigner.Verification verification = signer.verify(event.getItemInHand());
        if (!verification.valid()) {
            return;
        }
        if (verification.data().itemType() == CustomItemType.PLANTING_POT) {
            if (event.getBlockPlaced().getType() != Material.DECORATED_POT) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(
                        translations.component(event.getPlayer(), "errors.invalid-item")
                );
                return;
            }
            plants.markStation(event.getBlockPlaced());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (!plants.isStation(clicked)) {
            return;
        }
        ItemStack held = event.getPlayer().getInventory().getItemInMainHand();
        ItemSigner.Verification verification = signer.verify(held);
        if (!verification.valid() || verification.data().itemType() != CustomItemType.SEED) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        plants.plant(player, clicked, held.clone()).whenComplete((plant, failure) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (failure == null) {
                        return;
                    }
                    Throwable cause = unwrap(failure);
                    String key = switch (cause.getMessage() == null ? "" : cause.getMessage()) {
                        case "no_permission" -> "errors.no-permission";
                        case "farm_permission" -> "errors.farm-permission";
                        case "limit_reached", "chunk_limit" -> "errors.limit-reached";
                        case "busy" -> "errors.busy";
                        case "canceled" -> "errors.canceled";
                        case "invalid_item", "invalid_or_wrong_item", "unregistered_item" ->
                                "errors.invalid-item";
                        case "item_already_used_or_modified", "item_reservation_failed",
                             "item_consumption_failed" -> "errors.duplicate-item";
                        case "too_close" -> "errors.no-space";
                        default -> "errors.database-unavailable";
                    };
                    player.sendMessage(translations.component(
                            player,
                            key,
                            Map.of("limit", "configured")
                    ));
                    if (key.equals("errors.database-unavailable")) {
                        plugin.getLogger().log(Level.WARNING,
                                "Planting failed for " + player.getUniqueId(), cause);
                    }
                }));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!plants.isStation(block)) {
            return;
        }
        if (plants.at(block.getLocation()).isPresent()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(
                    translations.component(event.getPlayer(), "errors.busy")
            );
            return;
        }
        event.setDropItems(false);
        Player player = event.getPlayer();
        secureItems.issue(
                player,
                CustomItemType.PLANTING_POT,
                player.getUniqueId(),
                ""
        ).whenComplete((item, failure) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (failure != null) {
                plugin.getLogger().log(Level.WARNING, "Could not return a plant station", failure);
                player.sendMessage(translations.component(player, "errors.database-unavailable"));
                return;
            }
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
            overflow.values().forEach(stack ->
                    player.getWorld().dropItemNaturally(player.getLocation(), stack));
        }));
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
