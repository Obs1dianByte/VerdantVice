package com.verdantvice.storage;

import java.util.Objects;

public final class BatchAccessException extends RuntimeException {
    private final BatchAccessFailure reason;

    public BatchAccessException(BatchAccessFailure reason) {
        super(Objects.requireNonNull(reason, "reason").code());
        this.reason = reason;
    }

    public BatchAccessFailure reason() {
        return reason;
    }
}
