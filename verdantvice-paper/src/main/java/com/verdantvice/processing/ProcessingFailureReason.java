package com.verdantvice.processing;

public enum ProcessingFailureReason {
    INVALID_SIGNATURE("invalid_signature", "processing.errors.invalid-signature"),
    UNREGISTERED_ITEM("unregistered_item", "processing.errors.unregistered-item"),
    ITEM_ALREADY_USED("item_already_used", "processing.errors.item-already-used"),
    WRONG_OWNER("wrong_owner", "processing.errors.wrong-owner"),
    MISSING_BATCH("missing_batch", "processing.errors.missing-batch"),
    INVALID_BATCH_SIGNATURE("invalid_batch_signature", "processing.errors.invalid-batch-signature"),
    WRONG_BATCH_STATE("wrong_batch_state", "processing.errors.wrong-batch-state"),
    STATION_BUSY("station_busy", "processing.errors.station-busy"),
    MISSING_ROLLING_PAPER("missing_rolling_paper", "rolling.errors.missing-paper"),
    MISSING_ROLLING_FILTER("missing_rolling_filter", "rolling.errors.missing-filter"),
    MISSING_ROLLING_PRODUCT("missing_rolling_product", "rolling.errors.missing-product"),
    ROLLING_BUSY("rolling_busy", "rolling.errors.busy"),
    DATABASE_UNAVAILABLE("database_unavailable", "errors.database-unavailable");

    private final String code;
    private final String translationKey;

    ProcessingFailureReason(String code, String translationKey) {
        this.code = code;
        this.translationKey = translationKey;
    }

    public String code() {
        return code;
    }

    public String translationKey() {
        return translationKey;
    }
}
