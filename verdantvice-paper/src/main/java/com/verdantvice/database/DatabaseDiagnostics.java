package com.verdantvice.database;

public record DatabaseDiagnostics(
        DatabaseType type,
        String lifecycleState,
        boolean acceptingWork,
        int activeExecutorThreads,
        int queuedOperations,
        long completedOperations
) {
}
