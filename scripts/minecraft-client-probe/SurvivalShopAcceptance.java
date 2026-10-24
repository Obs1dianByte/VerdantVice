package com.verdantvice.clientprobe;

import com.verdantvice.VerdantVicePlugin;
import com.verdantvice.herbalist.HerbalistMenuHolder;
import com.verdantvice.herbalist.HerbalistMenuType;
import com.verdantvice.herbalist.HerbalistOffer;
import com.verdantvice.herbalist.HerbalistOfferCatalog;
import com.verdantvice.herbalist.HerbalistTier;
import com.verdantvice.security.CustomItemType;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Production buy-menu/action and purchase-service acceptance using a connected non-OP player.
 * Merchants and vanilla emerald costs are fixtures, not claimed survival gathering/progression.
 * Actions are SERVER_API calls against naturally opened menu holders, not synthetic click events.
 */
final class SurvivalShopAcceptance {
    private final JavaPlugin probe;
    private final VerdantVicePlugin target;
    private final BiConsumer<Boolean, String> check;
    private final Consumer<String> event;
    private Villager merchant;
    private UUID merchantId;
    private HerbalistTier fixtureTier;

    SurvivalShopAcceptance(JavaPlugin probe, VerdantVicePlugin target,
            BiConsumer<Boolean, String> check, Consumer<String> event) {
        this.probe = probe;
        this.target = target;
        this.check = check;
        this.event = event;
    }

    CompletableFuture<ItemStack> buy(Player player, CustomItemType type, String strain,
            HerbalistTier tier) {
        check.accept(!player.isOp() && player.getGameMode() == GameMode.SURVIVAL
                        && !player.hasPermission("verdantvice.admin"),
                "survival shop purchase has no operator/admin/creative bypass: " + type);
        HerbalistOffer offer = new HerbalistOfferCatalog(target.strains(), target.configs())
                .available(tier).stream().filter(candidate -> candidate.itemType() == type
                        && candidate.strainId().orElse("").equals(strain)).findFirst().orElseThrow(() ->
                        new IllegalStateException("No survival acquisition route at " + tier + ": " + type + "/" + strain));
        Set<UUID> before = identities(player);
        int booksBefore = materialCount(player, Material.BOOK);
        int goldBefore = materialCount(player, Material.GOLD_BLOCK);
        return prepareMerchant(player, tier).thenCompose(ignored -> main(() -> {
            check.accept(target.economy().providerName().equals("Emerald"),
                    "isolated survival purchase uses the default vanilla-emerald economy");
            target.economy().deposit(player, offer.emeraldPrice());
            if (type == CustomItemType.PLANTATION_CONTRACT) {
                addHiringFixture(player, Material.BOOK, "books");
                addHiringFixture(player, Material.GOLD_BLOCK, "gold-blocks");
                event.accept("FIXTURE configured vanilla books and gold blocks for normal contract purchase");
            }
            int emeralds = emeralds(player);
            event.accept("FIXTURE vanilla emeralds for exact configured shop price=" + offer.emeraldPrice());
            player.closeInventory();
            target.herbalists().handleInteraction(player, merchant);
            return emeralds;
        })).thenCompose(balance -> await(() -> currentHolder(player, HerbalistMenuType.ROOT) != null,
                "production herbalist root menu opens for a non-OP survival player").thenCompose(ignored -> main(() -> {
            target.herbalists().handleMenuAction(player, currentHolder(player, HerbalistMenuType.ROOT), "open:buy");
            return balance;
        }))).thenCompose(balance -> await(() -> currentHolder(player, HerbalistMenuType.BUY) != null,
                "production herbalist buy menu opens for a non-OP survival player").thenCompose(ignored -> main(() -> {
            HerbalistMenuHolder holder = currentHolder(player, HerbalistMenuType.BUY);
            boolean visible = false;
            for (int slot = 0; slot < holder.getInventory().getSize(); slot++) {
                if (("purchase:" + offer.id()).equals(holder.action(slot))) visible = true;
            }
            check.accept(visible, "required survival offer is visible and actionable in the real menu: " + offer.id());
            event.accept("SERVER_API survival buy-menu action " + offer.id() + " tier=" + tier);
            target.herbalists().handleMenuAction(player, holder, "purchase:" + offer.id());
            return new PurchaseWindow(balance, holder.sessionUuid());
        }))).thenCompose(window -> await(() -> newlyPurchased(player, before, type, strain) != null
                        && currentHolder(player, HerbalistMenuType.BUY) != null
                        && !currentHolder(player, HerbalistMenuType.BUY).sessionUuid().equals(window.session()),
                "production purchase delivers signed item to actual survival inventory: " + offer.id())
                .thenCompose(ignored -> main(() -> {
                    check.accept(emeralds(player) == window.balance() - offer.emeraldPrice(),
                            "survival purchase debits the exact configured emerald price: " + offer.id());
                    if (type == CustomItemType.PLANTATION_CONTRACT) {
                        check.accept(materialCount(player, Material.BOOK) == booksBefore
                                        && materialCount(player, Material.GOLD_BLOCK) == goldBefore,
                                "normal contract purchase consumes exactly its configured vanilla books and gold blocks");
                    }
                    return newlyPurchased(player, before, type, strain);
                }))).thenCompose(stack -> target.secureItems().validateActive(stack, Set.of(type),
                        player.getUniqueId()).thenApply(validated -> {
                    check.accept(validated.data().itemType() == type,
                            "purchased survival item has an active production registry record: " + offer.id());
                    return stack;
                }));
    }

    private CompletableFuture<Void> prepareMerchant(Player player, HerbalistTier tier) {
        if (merchant != null && merchant.isValid() && fixtureTier == tier) {
            return CompletableFuture.completedFuture(null);
        }
        return main(() -> {
            check.accept(Bukkit.getIp().equals("127.0.0.1") && target.getServer().getWorldContainer()
                            .toPath().toAbsolutePath().normalize().toString().replace('\\', '/')
                            .contains("/build/minecraft-client/"),
                    "survival acquisition fixtures are restricted to the disposable loopback test world");
            if (merchant != null && merchant.isValid()) merchant.remove();
            merchant = player.getWorld().spawn(player.getLocation().clone().add(2, 0, 2), Villager.class);
            merchant.setAI(false);
            merchant.setInvulnerable(true);
            merchant.setProfession(Villager.Profession.FARMER);
            fixtureTier = tier;
            event.accept("FIXTURE herbalist tier=" + tier + "; no natural merchant levelling or emerald gathering is claimed");
            return target.herbalists().forceCreate(merchant, true, Optional.of(tier));
        }).thenCompose(future -> future).thenCompose(evaluation -> main(() -> {
            merchantId = evaluation.herbalist().orElseThrow().herbalistUuid();
            return null;
        }));
    }

    private HerbalistMenuHolder currentHolder(Player player, HerbalistMenuType type) {
        if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof HerbalistMenuHolder holder
                && holder.type() == type && holder.playerUuid().equals(player.getUniqueId())
                && holder.herbalistUuid().equals(merchantId)) return holder;
        return null;
    }

    private Set<UUID> identities(Player player) {
        Set<UUID> result = new HashSet<>();
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType().isAir()) continue;
            var verified = target.itemSigner().verify(item);
            if (verified.valid()) result.add(verified.data().uniqueInstanceId());
        }
        return result;
    }

    private ItemStack newlyPurchased(Player player, Set<UUID> previous, CustomItemType type, String strain) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType().isAir()) continue;
            var verified = target.itemSigner().verify(item);
            if (verified.valid() && !previous.contains(verified.data().uniqueInstanceId())
                    && verified.data().itemType() == type && verified.data().strain().equals(strain)) return item.clone();
        }
        return null;
    }

    private int emeralds(Player player) {
        return materialCount(player, Material.EMERALD);
    }

    private int materialCount(Player player, Material material) {
        int result = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && item.getType() == material) result += item.getAmount();
        }
        return result;
    }

    private void addHiringFixture(Player player, Material material, String key) {
        int amount = Math.max(0, target.configs().get("villagers.yml").getInt("hiring-contract." + key, 1));
        if (amount > 0) check.accept(player.getInventory().addItem(new ItemStack(material, amount)).isEmpty(),
                "fixture vanilla hiring materials fit the real survival inventory: " + material);
    }

    private CompletableFuture<Void> await(BooleanSupplier condition, String description) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        long deadline = System.nanoTime() + 10_000_000_000L;
        Runnable poll = new Runnable() {
            @Override public void run() {
                try {
                    if (condition.getAsBoolean()) {
                        check.accept(true, description);
                        future.complete(null);
                    } else if (System.nanoTime() > deadline) {
                        future.completeExceptionally(new IllegalStateException("Timed out: " + description));
                    } else Bukkit.getScheduler().runTaskLater(probe, this, 2L);
                } catch (Throwable failure) { future.completeExceptionally(failure); }
            }
        };
        Bukkit.getScheduler().runTask(probe, poll);
        return future;
    }

    private <T> CompletableFuture<T> main(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Runnable action = () -> {
            try { future.complete(supplier.get()); }
            catch (Throwable failure) { future.completeExceptionally(failure); }
        };
        if (Bukkit.isPrimaryThread()) action.run(); else Bukkit.getScheduler().runTask(probe, action);
        return future;
    }

    private record PurchaseWindow(int balance, UUID session) {}
}
