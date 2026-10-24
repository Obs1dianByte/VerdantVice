package com.verdantvice.worker;

import com.verdantvice.database.DatabaseManager;
import com.verdantvice.database.DatabaseType;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Performs the one-time, backup-first cleanup of legacy worker names. */
public final class WorkerNameMigrationService {
    private final DatabaseManager database;
    private final WorkerRepository workers;
    private final Logger logger;

    public WorkerNameMigrationService(
            DatabaseManager database,
            WorkerRepository workers,
            Logger logger
    ) {
        this.database = Objects.requireNonNull(database, "database");
        this.workers = Objects.requireNonNull(workers, "workers");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public CompletableFuture<MigrationReport> migrate(String fallbackName) {
        return workers.legacyNameCount(fallbackName).thenCompose(total -> {
            if (total == 0) {
                return CompletableFuture.completedFuture(
                        new MigrationReport(0, Optional.empty()));
            }
            if (database.diagnostics().type() != DatabaseType.SQLITE) {
                logger.log(Level.WARNING,
                        "Found {0} legacy worker name(s), but automatic cleanup requires "
                                + "a SQLite backup. Names remain sanitized in memory.",
                        total);
                return CompletableFuture.completedFuture(
                        new MigrationReport(0, Optional.empty()));
            }
            return database.backupNow().thenCompose(backup ->
                    workers.sanitizeLegacyNames(fallbackName, Instant.now())
                            .thenApply(changed -> {
                                logger.log(Level.INFO,
                                        "Sanitized {0} legacy worker name(s); backup: {1}",
                                        new Object[] { changed, backup.getFileName() });
                                return new MigrationReport(changed, Optional.of(backup));
                            }));
        });
    }

    public record MigrationReport(int changedRows, Optional<Path> backup) {
        public MigrationReport {
            backup = Objects.requireNonNull(backup, "backup");
            if (changedRows < 0) {
                throw new IllegalArgumentException("changedRows must not be negative");
            }
        }
    }
}
