package com.verdantvice.worker;

public enum WorkerTaskStatus {
    QUEUED,
    RESERVED,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    BLOCKED,
    CANCELLED
}
