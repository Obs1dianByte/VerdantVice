package com.verdantvice.trade;

import com.verdantvice.security.CustomItemType;
import com.verdantvice.security.ItemSigner;
import com.verdantvice.security.SignedItemData;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class PaperTradeService {
    private static final Set<CustomItemType> SELLABLE = EnumSet.of(
            CustomItemType.DRY_BATCH,
            CustomItemType.CURED_BATCH,
            CustomItemType.PACKAGE);

    private final ItemSigner signer;
    private final TradeRepository repository;

    public PaperTradeService(ItemSigner signer, TradeRepository repository) {
        this.signer = Objects.requireNonNull(signer, "signer");
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public CompletableFuture<PreparedSale> prepare(
            ItemStack item,
            UUID playerUuid,
            UUID herbalistUuid
    ) {
        ItemSigner.Verification verification = signer.verify(item);
        if (item.getAmount() != 1
                || !verification.valid()
                || !SELLABLE.contains(verification.data().itemType())) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("authentic-only"));
        }
        SignedItemData data = verification.data();
        return repository.prepareSale(
                data, signer.payloadHash(data), playerUuid, herbalistUuid, Instant.now());
    }

    public CompletableFuture<SaleCommitResult> commit(PreparedSale sale, String operationId) {
        return repository.commit(sale, operationId, Instant.now());
    }

    public CompletableFuture<Optional<Integer>> committedPayout(String operationId) {
        return repository.committedPayout(operationId);
    }

    public ItemSigner.Verification verify(ItemStack item) {
        return signer.verify(item);
    }
}
