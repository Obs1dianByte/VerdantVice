package com.verdantvice.worker;

/** Stable diagnostics for salary-ledger failures. */
public enum WorkerPaymentFailureReason {
    MISSING_WORKER_PARENT, MISSING_PAYER_PARENT, MISSING_FARM_PARENT,
    STALE_RUNTIME, CONSTRAINT_VIOLATION, DATABASE_UNAVAILABLE
}
