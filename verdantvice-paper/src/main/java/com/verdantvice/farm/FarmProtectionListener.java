package com.verdantvice.farm;

import com.verdantvice.i18n.TranslationService;
import com.verdantvice.security.CustomItemType;
import com.verdantvice.security.ItemSigner;
import com.verdantvice.security.SecureItemService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class FarmProtectionListener implements Listener {
    private final JavaPlugin plugin;
    private final FarmManager farms;
    private final ItemSigner signer;
    private final SecureItemService secureItems;
    private final TranslationService translations;

    public FarmProtectionListener(
            JavaPlugin plugin,
            FarmManager farms,
            ItemSigner signer,
            SecureItemService secureItems,
            TranslationService translations
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.farms = Objects.requireNonNull(farms, "farms");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.secureItems = Objects.requireNonNull(secureItems, "secureItems");
        this.translations = Objects.requireNonNull(translations, "translations");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onManagerTablePlace(BlockPlaceEvent event) {
        ItemStack snapshot = event.getItemInHand().clone();
        ItemSigner.Verification verification = signer.verify(snapshot);
        if (!verification.valid()
                || verification.data().itemType() != CustomItemType.FARM_MANAGER_TABLE) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!player.hasPermission("verdantvice.farm.create")
                && !player.hasPermission("verdantvice.admin")) {
            player.sendMessage(translations.component(player, "errors.no-permission"));
            return;
        }
        Block table = event.getBlockPlaced();
        UUID instanceId = verification.data().uniqueInstanceId();
        String operationId = "farm-table:" + UUID.randomUUID();
        secureItems.consume(
                snapshot,
                Set.of(CustomItemType.FARM_MANAGER_TABLE),
                player.getUniqueId(),
                operationId
        ).whenComplete((consumed, consumeFailure) -> runMain(() -> {
            if (consumeFailure != null) {
                player.sendMessage(translations.component(player, "errors.invalid-item"));
                return;
            }
            if (!removeInstance(player, instanceId)) {
                restoreManagerTable(player);
                player.sendMessage(translations.component(player, "errors.invalid-item"));
                return;
            }
            String name = player.getName() + " Farm";
            farms.createAt(player, name, table).whenComplete((farm, createFailure) ->
                    runMain(() -> {
                        if (createFailure != null) {
                            restoreManagerTable(player);
                            player.sendMessage(translations.component(
                                    player,
                                    "errors.protected-region"
                            ));
                            return;
                        }
                        player.sendMessage(translations.component(
                                player,
                                "success.farm-created",
                                Map.of("farm", farm.name())
                        ));
                    }));
        }));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSelection(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null) {
            return;
        }
        ItemSigner.Verification verification = signer.verify(event.getItem());
        if (!verification.valid()
                || verification.data().itemType() != CustomItemType.SELECTION_WAND) {
            return;
        }
        int point = event.getAction().isLeftClick() ? 1 : 2;
        farms.setSelection(event.getPlayer(), point, event.getClickedBlock().getLocation());
        event.setCancelled(true);
        event.getPlayer().sendMessage(translations.component(
                event.getPlayer(),
                point == 1
                        ? "farm.selection.first-point"
                        : "farm.selection.second-point",
                Map.of("position",
                        event.getClickedBlock().getX() + ", "
                                + event.getClickedBlock().getY() + ", "
                                + event.getClickedBlock().getZ())
        ));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Location location = event.getBlock().getLocation();
        if (farms.storageAt(location).isPresent()
                && !farms.can(event.getPlayer(), location, FarmPermission.MANAGE_STORAGE)) {
            deny(event.getPlayer());
            event.setCancelled(true);
            return;
        }
        if (farms.isManagerTable(location)) {
            event.setCancelled(true);
            if (!farms.can(event.getPlayer(), location, FarmPermission.DELETE)) {
                deny(event.getPlayer());
            } else {
                event.getPlayer().sendMessage(
                        translations.component(
                                event.getPlayer(),
                                "commands.farm.delete.usage"
                        )
                );
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Location location = event.getInventory().getLocation();
        if (location != null && farms.storageAt(location).isPresent()
                && !farms.can(player, location, FarmPermission.ACCESS_STORAGE)) {
            event.setCancelled(true);
            deny(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (affectsProtectedBlock(event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (affectsProtectedBlock(event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    private boolean affectsProtectedBlock(
            List<Block> moved,
            org.bukkit.block.BlockFace direction
    ) {
        for (Block block : moved) {
            if (protectedBlock(block.getLocation())
                    || protectedBlock(block.getRelative(direction).getLocation())) {
                return true;
            }
        }
        return false;
    }

    private boolean protectedBlock(Location location) {
        return farms.storageAt(location).isPresent() || farms.isManagerTable(location);
    }

    private void deny(Player player) {
        player.sendMessage(translations.component(player, "errors.farm-permission"));
    }

    private boolean removeInstance(Player player, UUID instanceId) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack candidate = contents[slot];
            if (candidate == null || candidate.getType().isAir()) {
                continue;
            }
            ItemSigner.Verification check = signer.verify(candidate);
            if (check.valid()
                    && check.data().uniqueInstanceId().equals(instanceId)) {
                if (candidate.getAmount() <= 1) {
                    player.getInventory().setItem(slot, null);
                } else {
                    candidate.setAmount(candidate.getAmount() - 1);
                    player.getInventory().setItem(slot, candidate);
                }
                return true;
            }
        }
        return false;
    }

    private void restoreManagerTable(Player player) {
        secureItems.issue(
                player,
                CustomItemType.FARM_MANAGER_TABLE,
                player.getUniqueId(),
                ""
        ).whenComplete((replacement, failure) -> {
            if (failure != null) {
                plugin.getLogger().log(
                        java.util.logging.Level.SEVERE,
                        "Could not restore a consumed farm manager table",
                        failure
                );
                return;
            }
            runMain(() -> {
                Map<Integer, ItemStack> overflow =
                        player.getInventory().addItem(replacement);
                overflow.values().forEach(item ->
                        player.getWorld().dropItemNaturally(player.getLocation(), item));
            });
        });
    }

    private void runMain(Runnable work) {
        if (Bukkit.isPrimaryThread()) {
            work.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, work);
        }
    }
}
