package com.verdantvice.database.journal;

public record JournalExecution(Status status, String resultJson) {
    public enum Status {
        EXECUTED,
        REPLAYED
    }
}
