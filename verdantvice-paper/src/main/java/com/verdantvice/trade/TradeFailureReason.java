package com.verdantvice.trade;

public enum TradeFailureReason {
    EMPTY_HAND("trade.empty-hand"),
    INVALID_AMOUNT("trade.invalid-amount"),
    INVALID_SIGNATURE("trade.authentic-only"),
    UNREGISTERED_ITEM("trade.unregistered-item"),
    ITEM_ALREADY_CONSUMED("trade.already-used"),
    ITEM_RESERVED("trade.item-reserved"),
    SIGNATURE_REGISTRY_MISMATCH("trade.authentic-only"),
    WRONG_OWNER("errors.wrong-owner"),
    MISSING_BATCH("trade.missing-batch"),
    BATCH_STATE_MISMATCH("trade.batch-state-mismatch"),
    UNSUPPORTED_SALE_STATE("trade.unsupported-batch-state"),
    FRESH_BATCH_REQUIRES_DRYING("trade.fresh-batch-requires-drying");

    private final String translationKey;

    TradeFailureReason(String translationKey) {
        this.translationKey = translationKey;
    }

    public String translationKey() {
        return translationKey;
    }
}
