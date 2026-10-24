package com.verdantvice.storage;

public enum BatchAccessFailure {
    MISSING_BATCH("missing_batch"),
    INVALID_BATCH_SIGNATURE("invalid_batch_signature"),
    WRONG_OWNER("wrong_owner"),
    WRONG_BATCH_STATE("wrong_batch_state");

    private final String code;

    BatchAccessFailure(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
