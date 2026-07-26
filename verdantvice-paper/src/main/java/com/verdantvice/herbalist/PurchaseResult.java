package com.verdantvice.herbalist;

import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.Optional;

public record PurchaseResult(Status status, HerbalistOffer offer, Optional<ItemStack> delivered, String reasonCode) {
    public PurchaseResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(offer, "offer");
        delivered = Objects.requireNonNull(delivered, "delivered").map(ItemStack::clone);
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
    }

    @Override
    public Optional<ItemStack> delivered() {
        return delivered.map(ItemStack::clone);
    }

    public enum Status {
        PURCHASED,
        INSUFFICIENT_FUNDS,
        BUSY,
        FAILED
    }
}
