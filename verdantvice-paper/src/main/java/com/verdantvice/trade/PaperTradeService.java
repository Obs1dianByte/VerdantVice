package com.verdantvice.trade;

import com.verdantvice.security.ItemSigner;
import com.verdantvice.security.SignedItemData;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class PaperTradeService {
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
        if (item == null || item.getType().isAir()) {
            return failed(TradeFailureReason.EMPTY_HAND);
        }
        if (item.getAmount() != 1) {
            return failed(TradeFailureReason.INVALID_AMOUNT);
        }
        ItemSigner.Verification verification = signer.verify(item);
        if (!verification.valid()) {
            return failed(TradeFailureReason.INVALID_SIGNATURE);
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

    private static <T> CompletableFuture<T> failed(TradeFailureReason reason) {
        return CompletableFuture.failedFuture(new TradeValidationException(reason));
    }
}
