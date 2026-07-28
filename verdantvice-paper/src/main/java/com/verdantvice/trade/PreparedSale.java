package com.verdantvice.trade;

import com.verdantvice.processing.ProductBatch;
import com.verdantvice.security.CustomItemType;
import com.verdantvice.security.SignedItemData;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record PreparedSale(
        UUID quoteId,
        UUID playerUuid,
        UUID herbalistUuid,
        SignedItemData itemData,
        UUID itemInstanceId,
        String itemPayloadHash,
        ProductBatch batch,
        double quantity,
        double unitPrice,
        int payout,
        Map<String, Long> marketVersions,
        Instant issuedAt,
        Instant expiresAt
) {
    public PreparedSale {
        Objects.requireNonNull(quoteId, "quoteId");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(herbalistUuid, "herbalistUuid");
        Objects.requireNonNull(itemData, "itemData");
        Objects.requireNonNull(itemInstanceId, "itemInstanceId");
        Objects.requireNonNull(itemPayloadHash, "itemPayloadHash");
        Objects.requireNonNull(batch, "batch");
        if (!itemData.uniqueInstanceId().equals(itemInstanceId)
                || !Objects.equals(itemData.batchId(), batch.batchId())) {
            throw new IllegalArgumentException("Sale identity does not match its batch");
        }
        if (quantity <= 0.0 || unitPrice <= 0.0 || payout < 1) {
            throw new IllegalArgumentException("Sale quote values must be positive");
        }
        marketVersions = Map.copyOf(Objects.requireNonNull(marketVersions, "marketVersions"));
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public CustomItemType itemType() {
        return itemData.itemType();
    }
}
