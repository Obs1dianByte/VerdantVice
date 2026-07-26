package com.verdantvice.herbalist;

import com.verdantvice.config.ConfigManager;
import com.verdantvice.database.item.ItemRegistration;
import com.verdantvice.database.item.ItemRegistryRepository;
import com.verdantvice.database.item.RegistrationStatus;
import com.verdantvice.item.CustomItemFactory;
import com.verdantvice.security.SecureItemService;
import com.verdantvice.security.SignedItemData;
import com.verdantvice.trade.EconomyService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class HerbalistPurchaseService {
    private final JavaPlugin plugin;
    private final ConfigManager configs;
    private final EconomyService economy;
    private final SecureItemService secureItems;
    private final TutorialBookService books;
    private final ItemRegistryRepository registry;
    private final Set<UUID> busy = new HashSet<>();

    public HerbalistPurchaseService(
            JavaPlugin plugin,
            ConfigManager configs,
            EconomyService economy,
            SecureItemService secureItems,
            TutorialBookService books,
            ItemRegistryRepository registry
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.secureItems = Objects.requireNonNull(secureItems, "secureItems");
        this.books = Objects.requireNonNull(books, "books");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public CompletableFuture<PurchaseResult> purchase(Player player, HerbalistOffer offer) {
        requireMainThread();
        if (!busy.add(player.getUniqueId())) {
            return CompletableFuture.completedFuture(
                    new PurchaseResult(PurchaseResult.Status.BUSY, offer, Optional.empty(), "busy"));
        }
        if (!hasCosts(player, offer)) {
            busy.remove(player.getUniqueId());
            return CompletableFuture.completedFuture(new PurchaseResult(
                    PurchaseResult.Status.INSUFFICIENT_FUNDS, offer, Optional.empty(),
                    "insufficient-items"));
        }
        CompletableFuture<ItemStack> issued = switch (offer.kind()) {
            case BOOK -> issueBook(player, offer.bookType().orElseThrow());
            case SEED, TOOL, PLANTATION_CONTRACT -> secureItems.issue(
                    player, offer.itemType(), player.getUniqueId(), offer.strainId().orElse(""));
        };
        CompletableFuture<PurchaseResult> result = new CompletableFuture<>();
        issued.whenComplete((item, failure) -> runMain(() -> {
            try {
                if (failure != null) {
                    result.complete(new PurchaseResult(
                            PurchaseResult.Status.FAILED, offer, Optional.empty(), "database-unavailable"));
                    return;
                }
                if (!hasCosts(player, offer) || !takeCosts(player, offer)) {
                    result.complete(new PurchaseResult(
                            PurchaseResult.Status.INSUFFICIENT_FUNDS, offer,
                            Optional.empty(), "insufficient-items"));
                    return;
                }
                Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
                overflow.values().forEach(stack ->
                        player.getWorld().dropItemNaturally(player.getLocation(), stack));
                result.complete(new PurchaseResult(
                        PurchaseResult.Status.PURCHASED, offer, Optional.of(item), ""));
            } finally {
                busy.remove(player.getUniqueId());
            }
        }));
        return result;
    }

    private CompletableFuture<ItemStack> issueBook(Player player, TutorialBookService.BookType type) {
        TutorialBookService.PreparedBook prepared = type == TutorialBookService.BookType.BASICS
                ? books.basics(player) : books.advanced(player, type);
        SignedItemData data = prepared.data();
        ItemRegistration registration = new ItemRegistration(
                data.uniqueInstanceId(), data.itemType().name(), data.itemId(), data.batchId(),
                data.ownerUuid(), data.quality(), null, data.createdAt(), prepared.payloadHash(),
                data.schemaVersion(), null);
        return registry.register(registration).thenCompose(result -> {
            if (result.status() == RegistrationStatus.CONFLICT) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("book-registry-conflict"));
            }
            return CompletableFuture.completedFuture(prepared.stack());
        });
    }

    private boolean hasCosts(Player player, HerbalistOffer offer) {
        if (!economy.has(player, offer.emeraldPrice())) return false;
        if (offer.kind() != HerbalistOfferKind.PLANTATION_CONTRACT) return true;
        int booksRequired = Math.max(0,
                configs.get("villagers.yml").getInt("hiring-contract.books", 1));
        int goldRequired = Math.max(0,
                configs.get("villagers.yml").getInt("hiring-contract.gold-blocks", 1));
        return count(player, Material.BOOK) >= booksRequired
                && count(player, Material.GOLD_BLOCK) >= goldRequired;
    }

    private boolean takeCosts(Player player, HerbalistOffer offer) {
        if (!economy.withdraw(player, offer.emeraldPrice())) return false;
        if (offer.kind() != HerbalistOfferKind.PLANTATION_CONTRACT) return true;
        int booksRequired = Math.max(0,
                configs.get("villagers.yml").getInt("hiring-contract.books", 1));
        int goldRequired = Math.max(0,
                configs.get("villagers.yml").getInt("hiring-contract.gold-blocks", 1));
        remove(player, Material.BOOK, booksRequired);
        remove(player, Material.GOLD_BLOCK, goldRequired);
        return true;
    }

    private static int count(Player player, Material material) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.getType() == material) total += stack.getAmount();
        }
        return total;
    }

    private static void remove(Player player, Material material, int amount) {
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType() != material) continue;
            int taken = Math.min(stack.getAmount(), remaining);
            stack.setAmount(stack.getAmount() - taken);
            contents[slot] = stack.getAmount() == 0 ? null : stack;
            remaining -= taken;
        }
        player.getInventory().setStorageContents(contents);
    }

    private void runMain(Runnable work) {
        if (plugin.getServer().isPrimaryThread()) {
            work.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, work);
        }
    }

    private void requireMainThread() {
        if (!plugin.getServer().isPrimaryThread()) {
            throw new IllegalStateException("Purchases must start on the server thread");
        }
    }
}
