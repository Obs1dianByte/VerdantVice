package com.verdantvice.trade;

import java.io.Serial;
import java.util.Objects;

public final class TradeValidationException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final TradeFailureReason reason;

    public TradeValidationException(TradeFailureReason reason) {
        super(Objects.requireNonNull(reason, "reason").name().toLowerCase(java.util.Locale.ROOT));
        this.reason = reason;
    }

    public TradeFailureReason reason() {
        return reason;
    }
}
