package com.verdantvice.worker;

import java.util.Objects;

/** Typed salary-ledger failure safe to classify without exposing SQL. */
public final class WorkerPaymentException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final WorkerPaymentFailureReason reason;
    public WorkerPaymentException(WorkerPaymentFailureReason reason, String message) {
        super(message); this.reason = Objects.requireNonNull(reason, "reason");
    }
    public WorkerPaymentException(WorkerPaymentFailureReason reason, String message, Throwable cause) {
        super(message, cause); this.reason = Objects.requireNonNull(reason, "reason");
    }
    public WorkerPaymentFailureReason reason() { return reason; }
}
