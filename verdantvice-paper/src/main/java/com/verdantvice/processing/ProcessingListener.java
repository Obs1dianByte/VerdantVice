package com.verdantvice.processing;

import com.verdantvice.i18n.TranslationService;
import com.verdantvice.security.ItemSigner;
import com.verdantvice.security.SecureItemService;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;

public final class ProcessingListener implements Listener {
    private final JavaPlugin plugin;
    private final ProcessingManager processing;
    private final ItemSigner signer;
    private final SecureItemService secureItems;
    private final TranslationService translations;

    public ProcessingListener(
            JavaPlugin plugin,
            ProcessingManager processing,
            ItemSigner signer,
            SecureItemService secureItems,
            TranslationService translations
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.processing = Objects.requireNonNull(processing, "processing");
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
        for (ProcessingStationType type : ProcessingStationType.values()) {
            if (verification.data().itemType() == type.itemType()) {
                if (event.getBlockPlaced().getType() != type.material()) {
                    event.setCancelled(true);
                    event.getPlayer().sendMessage(
                            translations.component(event.getPlayer(), "errors.invalid-item")
                    );
                    return;
                }
                processing.markStation(event.getBlockPlaced(), type);
                return;
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        processing.abortForPlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || event.getClickedBlock() == null
                || !ProcessingStationPolicy.opensMenu(event.getAction())) {
            return;
        }
        ProcessingManager.Station station =
                processing.station(event.getClickedBlock()).orElse(null);
        if (station == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (processing.stationBreakPending(station)) {
            player.sendMessage(translations.component(player, "errors.busy"));
            return;
        }
        if (!player.hasPermission("verdantvice.process")) {
            player.sendMessage(translations.component(player, "errors.no-permission"));
            return;
        }
        if (station.type() == ProcessingStationType.GRINDER && player.isSneaking()) {
            processing.mixHands(player).whenComplete((ignored, failure) ->
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (failure == null) {
                            return;
                        }
                        Throwable cause = unwrap(failure);
                        String key = switch (cause.getMessage() == null ? "" : cause.getMessage()) {
                            case "invalid_or_wrong_item", "insufficient_items" ->
                                    "errors.insufficient-items";
                            case "wrong_owner" -> "errors.wrong-owner";
                            case "duplicate_source_batch" -> "errors.duplicate-item";
                            default -> "errors.database-unavailable";
                        };
                        player.sendMessage(translations.component(player, key));
                        if (key.equals("errors.database-unavailable")) {
                            plugin.getLogger().log(Level.WARNING, "Mixing failed", cause);
                        }
                    }));
            return;
        }
        processing.open(player, station);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Object holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof ProcessingMenuHolder menu) {
            event.setCancelled(true);
            if (event.getRawSlot() >= 0
                    && event.getRawSlot() < event.getView().getTopInventory().getSize()) {
                processing.handleMenuClick(player, menu, event.getRawSlot());
            }
        } else if (holder instanceof RollingMenuHolder rolling) {
            event.setCancelled(true);
            if (event.getRawSlot() >= 0
                    && event.getRawSlot() < event.getView().getTopInventory().getSize()) {
                processing.handleRollingClick(player, rolling, event.getRawSlot());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        Object holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof ProcessingMenuHolder || holder instanceof RollingMenuHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player owner && processing.hasPendingDelivery(owner.getUniqueId())) {
            Bukkit.getScheduler().runTask(plugin, () -> processing.deliverPending(owner)
                    .exceptionally(failure -> {
                        plugin.getLogger().log(Level.WARNING, "Processing delivery remains pending", failure);
                        return 0;
                    }));
        }
        if (event.getPlayer() instanceof Player player
                && event.getInventory().getHolder() instanceof RollingMenuHolder rolling) {
            Bukkit.getScheduler().runTask(
                    plugin,
                    () -> processing.onRollingClosed(player, rolling)
            );
        }
    }

    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> processing.deliverPending(event.getPlayer())
                .exceptionally(failure -> {
                    plugin.getLogger().log(Level.WARNING, "Processing delivery remains pending", failure);
                    return 0;
                }));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        processing.abortForPlayer(event.getEntity());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        ProcessingManager.StationBreakResult result =
                processing.claimStationForBreak(event.getBlock());
        if (result.status() == ProcessingManager.StationBreakStatus.NOT_STATION) {
            return;
        }
        if (result.status() == ProcessingManager.StationBreakStatus.BUSY) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(
                    translations.component(event.getPlayer(), "errors.busy")
            );
            return;
        }

        ProcessingManager.Station station = result.optionalStation().orElseThrow();
        Block block = event.getBlock();
        var dropLocation = block.getLocation().add(0.5, 0.5, 0.5);
        event.setCancelled(true);
        event.setDropItems(false);
        event.setExpToDrop(0);
        Player player = event.getPlayer();
        secureItems.issue(
                player,
                station.type().itemType(),
                player.getUniqueId(),
                ""
        ).whenComplete((item, failure) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (failure != null) {
                result.close();
                plugin.getLogger().log(
                        Level.WARNING,
                        "Could not return processing station",
                        failure
                );
                player.sendMessage(
                        translations.component(player, "errors.database-unavailable")
                );
                return;
            }
            if (!processing.completeStationBreak(block, result)) {
                result.close();
                player.sendMessage(translations.component(player, "errors.busy"));
                return;
            }
            block.setType(org.bukkit.Material.AIR, false);
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
            overflow.values().forEach(stack ->
                    dropLocation.getWorld().dropItemNaturally(dropLocation, stack));
        }));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        if (secureItems.itemUsePolicy().antiDupeEnabled() && signer.verify(event.getItem()).valid()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> processing.station(block).isPresent())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> processing.station(block).isPresent())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        protectStations(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        protectStations(event.blockList());
    }

    private void protectStations(List<Block> blocks) {
        blocks.removeIf(block -> processing.station(block).isPresent());
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
