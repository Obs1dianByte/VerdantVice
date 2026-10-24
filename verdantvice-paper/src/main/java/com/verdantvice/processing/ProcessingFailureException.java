package com.verdantvice.processing;

import java.util.Objects;

public final class ProcessingFailureException extends RuntimeException {
    private final ProcessingFailureReason reason;

    public ProcessingFailureException(ProcessingFailureReason reason) {
        super(Objects.requireNonNull(reason, "reason").code());
        this.reason = reason;
    }

    public ProcessingFailureReason reason() {
        return reason;
    }
}
