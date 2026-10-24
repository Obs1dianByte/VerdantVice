package com.verdantvice.database;

import com.verdantvice.database.migration.MigrationManager;
import com.verdantvice.database.migration.MigrationReport;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class DatabaseManager implements AutoCloseable {
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss-SSS")
            .withZone(ZoneOffset.UTC);

    private final DatabaseConfig config;
    private final Logger logger;
    private final Object lifecycleLock = new Object();
    private final AtomicInteger threadSequence = new AtomicInteger();

    private volatile LifecycleState state = LifecycleState.NEW;
    private volatile DataSourceFactory.DataSourceHandle dataSourceHandle;
    private volatile ThreadPoolExecutor executor;
    private volatile ScheduledExecutorService backupScheduler;
    private volatile CompletableFuture<MigrationReport> startFuture;

    public DatabaseManager(DatabaseConfig config, Logger logger) {
        this.config = Objects.requireNonNull(config, "config");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public CompletionStage<MigrationReport> start() {
        synchronized (lifecycleLock) {
            if (startFuture != null) {
                return startFuture;
            }
            if (state != LifecycleState.NEW) {
                return CompletableFuture.failedFuture(
                        new DatabaseUnavailableException("Database manager cannot start from state " + state));
            }
            state = LifecycleState.STARTING;
            executor = createExecutor();
            backupScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                    namedThreadFactory("VerdantVice-DB-Backup"));
            startFuture = CompletableFuture.supplyAsync(this::initialize, executor).whenComplete((report, failure) -> {
                if (failure == null) {
                    synchronized (lifecycleLock) {
                        if (state == LifecycleState.STARTING) {
                            state = LifecycleState.RUNNING;
                            scheduleBackups();
                        }
                    }
                } else {
                    logger.log(Level.SEVERE, "VerdantVice database startup failed", unwrap(failure));
                    failStartup();
                }
            });
            return startFuture;
        }
    }

    public <T> CompletableFuture<T> read(SqlWork<T> work) {
        return submit(work, true, false);
    }

    /**
     * Runs a database-only transaction outside the server thread. Transient
     * failures before commit are retried from the beginning. A commit error is
     * surfaced as {@link AmbiguousCommitException} and is never replayed.
     */
    public <T> CompletableFuture<T> transaction(SqlWork<T> work) {
        return submit(work, true, true);
    }

    public <T> CompletableFuture<T> executeOnce(SqlWork<T> work) {
        return submit(work, false, false);
    }

    public CompletableFuture<Integer> update(String sql, Object... arguments) {
        Object[] snapshot = arguments.clone();
        return transaction(session -> session.update(sql, snapshot));
    }

    public <T> CompletableFuture<List<T>> query(
            String sql,
            RowMapper<T> mapper,
            Object... arguments) {
        Object[] snapshot = arguments.clone();
        return read(session -> session.query(sql, mapper, snapshot));
    }

    public <T> CompletableFuture<Optional<T>> queryOne(
            String sql,
            RowMapper<T> mapper,
            Object... arguments) {
        Object[] snapshot = arguments.clone();
        return read(session -> session.queryOne(sql, mapper, snapshot));
    }

    public CompletableFuture<Boolean> isHealthy() {
        return read(session -> session.exists("SELECT 1"))
                .exceptionally(failure -> false);
    }

    public CompletableFuture<Path> backupNow() {
        if (config.type() != DatabaseType.SQLITE) {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("Built-in backups are available only for SQLite"));
        }
        return submit(session -> createSqliteBackup(session, "manual"), false, false);
    }

    public DatabaseDiagnostics diagnostics() {
        ThreadPoolExecutor current = executor;
        return new DatabaseDiagnostics(
                config.type(),
                state.name(),
                state == LifecycleState.RUNNING,
                current == null ? 0 : current.getActiveCount(),
                current == null ? 0 : current.getQueue().size(),
                current == null ? 0L : current.getCompletedTaskCount());
    }

    public boolean isRunning() {
        return state == LifecycleState.RUNNING;
    }

    @Override
    public void close() {
        ThreadPoolExecutor currentExecutor;
        ScheduledExecutorService currentScheduler;
        DataSourceFactory.DataSourceHandle currentDataSource;
        synchronized (lifecycleLock) {
            if (state == LifecycleState.CLOSED || state == LifecycleState.CLOSING) {
                return;
            }
            state = LifecycleState.CLOSING;
            currentExecutor = executor;
            currentScheduler = backupScheduler;
            currentDataSource = dataSourceHandle;
        }

        if (currentScheduler != null) {
            currentScheduler.shutdownNow();
        }
        if (currentExecutor != null) {
            currentExecutor.shutdown();
            try {
                if (!currentExecutor.awaitTermination(config.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                    List<Runnable> abandoned = currentExecutor.shutdownNow();
                    logger.log(Level.WARNING, "Cancelled {0} queued database operations during shutdown",
                            abandoned.size());
                }
            } catch (InterruptedException exception) {
                currentExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        currentDataSource = dataSourceHandle;
        if (currentDataSource != null) {
            try {
                currentDataSource.close();
            } catch (DatabaseException exception) {
                logger.log(Level.WARNING, "Database pool did not close cleanly", exception);
            }
        }
        synchronized (lifecycleLock) {
            state = LifecycleState.CLOSED;
        }
    }

    private MigrationReport initialize() {
        try {
            Files.createDirectories(config.sqliteFile().getParent());
            Files.createDirectories(config.backupDirectory());
            boolean sqliteExisted = config.type() == DatabaseType.SQLITE
                    && Files.isRegularFile(config.sqliteFile())
                    && Files.size(config.sqliteFile()) > 0L;

            dataSourceHandle = DataSourceFactory.create(config);
            DataSource dataSource = dataSourceHandle.dataSource();
            verifyConnection(dataSource);
            if (sqliteExisted && config.backupOnStart()) {
                try (Connection connection = dataSource.getConnection()) {
                    createSqliteBackup(new SqlSession(connection, DatabaseType.SQLITE), "startup");
                }
            }
            return new MigrationManager(dataSource, config.type(), logger).migrate();
        } catch (IOException | SQLException exception) {
            throw new DatabaseUnavailableException("Unable to initialize database storage", exception);
        }
    }

    private void verifyConnection(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                java.sql.PreparedStatement statement = connection.prepareStatement("SELECT 1");
                java.sql.ResultSet ignored = statement.executeQuery()) {
            if (!ignored.next()) {
                throw new SQLException("Database health query returned no rows");
            }
        }
    }

    private <T> CompletableFuture<T> submit(SqlWork<T> work, boolean retry, boolean transaction) {
        Objects.requireNonNull(work, "work");
        if (state != LifecycleState.RUNNING) {
            return CompletableFuture.failedFuture(
                    new DatabaseUnavailableException("Database is not accepting work; current state is " + state));
        }
        ThreadPoolExecutor current = executor;
        try {
            return CompletableFuture.supplyAsync(
                    () -> runWithRetry(work, retry, transaction),
                    current);
        } catch (RejectedExecutionException exception) {
            return CompletableFuture.failedFuture(
                    new DatabaseUnavailableException("Database executor queue is full", exception));
        }
    }

    private <T> T runWithRetry(SqlWork<T> work, boolean retry, boolean transaction) {
        int maximumAttempts = retry ? config.retry().attempts() : 1;
        SQLException mostRecent = null;
        for (int attempt = 1; attempt <= maximumAttempts; attempt++) {
            try {
                return transaction ? runTransaction(work) : runAutoCommit(work);
            } catch (SQLException exception) {
                mostRecent = exception;
                if (SqlFailureClassifier.integrity(exception).isPresent()) {
                    throw SqlFailureClassifier.integrityException(exception);
                }
                if (!SqlFailureClassifier.transientFailure(exception)) {
                    throw new DatabaseException("Permanent database operation failure", exception);
                }
                if (attempt == maximumAttempts) break;
                long delay = retryDelayMillis(attempt);
                logger.log(Level.WARNING,
                        "Transient database failure; retrying operation ({0}/{1}) in {2} ms: {3}",
                        new Object[] { attempt + 1, maximumAttempts, delay, exception.getMessage() });
                sleep(delay);
            }
        }
        throw new DatabaseUnavailableException(
                "Database operation failed after " + maximumAttempts + " attempt(s)", mostRecent);
    }

    private <T> T runAutoCommit(SqlWork<T> work) throws SQLException {
        try (Connection connection = requireDataSource().getConnection()) {
            connection.setAutoCommit(true);
            return work.execute(new SqlSession(connection, config.type()));
        }
    }

    private <T> T runTransaction(SqlWork<T> work) throws SQLException {
        try (Connection connection = requireDataSource().getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.execute(new SqlSession(connection, config.type()));
                try {
                    connection.commit();
                } catch (SQLException exception) {
                    throw new AmbiguousCommitException(
                            "The database did not confirm whether the transaction committed", exception);
                }
                return result;
            } catch (SQLException | RuntimeException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            } finally {
                try {
                    connection.setAutoCommit(originalAutoCommit);
                } catch (SQLException exception) {
                    logger.log(Level.FINE, "Unable to restore connection auto-commit", exception);
                }
            }
        }
    }

    private DataSource requireDataSource() {
        DataSourceFactory.DataSourceHandle current = dataSourceHandle;
        if (current == null) {
            throw new DatabaseUnavailableException("Database data source is unavailable");
        }
        return current.dataSource();
    }

    private Path createSqliteBackup(SqlSession session, String reason) throws SQLException {
        try {
            Files.createDirectories(config.backupDirectory());
            String timestamp = BACKUP_TIME.format(Instant.now());
            Path destination = uniqueBackupPath("verdantvice-" + timestamp + "-" + reason, ".db");
            Path partial = destination.resolveSibling(destination.getFileName() + ".partial");
            try {
                session.update("VACUUM INTO ?", partial.toString());
                moveAtomically(partial, destination);
                pruneBackups();
                logger.log(Level.INFO, "Created SQLite backup: {0}", destination.getFileName());
                return destination;
            } catch (SQLException | IOException exception) {
                try {
                    Files.deleteIfExists(partial);
                } catch (IOException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
                if (exception instanceof SQLException sqlException) {
                    throw sqlException;
                }
                throw new DatabaseException("Unable to create SQLite backup", exception);
            }
        } catch (IOException exception) {
            throw new DatabaseException("Unable to prepare SQLite backup directory", exception);
        }
    }

    private Path uniqueBackupPath(String stem, String extension) {
        Path candidate = config.backupDirectory().resolve(stem + extension);
        int suffix = 1;
        while (Files.exists(candidate)) {
            candidate = config.backupDirectory().resolve(stem + "-" + suffix + extension);
            suffix++;
        }
        return candidate;
    }

    private void pruneBackups() throws IOException {
        List<Path> backups;
        try (java.util.stream.Stream<Path> files = Files.list(config.backupDirectory())) {
            backups = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("verdantvice-"))
                    .filter(path -> path.getFileName().toString().endsWith(".db"))
                    .sorted(Comparator.comparingLong(this::lastModified).reversed())
                    .toList();
        }
        for (int index = config.backupRetention(); index < backups.size(); index++) {
            Files.deleteIfExists(backups.get(index));
        }
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            return Long.MIN_VALUE;
        }
    }

    private static void moveAtomically(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination);
        }
    }

    private void scheduleBackups() {
        if (config.type() != DatabaseType.SQLITE || config.backupInterval().isZero()) {
            return;
        }
        long delayMillis = Math.max(1_000L, config.backupInterval().toMillis());
        backupScheduler.scheduleWithFixedDelay(() -> {
            if (state != LifecycleState.RUNNING) {
                return;
            }
            backupNow().whenComplete((path, failure) -> {
                if (failure != null) {
                    logger.log(Level.WARNING, "Scheduled SQLite backup failed", unwrap(failure));
                }
            });
        }, delayMillis, delayMillis, TimeUnit.MILLISECONDS);
    }

    private ThreadPoolExecutor createExecutor() {
        int threads = config.type() == DatabaseType.SQLITE ? 1 : config.executorThreads();
        return new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.executorQueueCapacity()),
                namedThreadFactory("VerdantVice-DB"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private ThreadFactory namedThreadFactory(String prefix) {
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + threadSequence.incrementAndGet());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler(
                    (ignored, failure) -> logger.log(Level.SEVERE, "Uncaught database worker failure", failure));
            return thread;
        };
    }

    private void failStartup() {
        synchronized (lifecycleLock) {
            if (state == LifecycleState.CLOSED) {
                return;
            }
            state = LifecycleState.CLOSING;
        }
        ScheduledExecutorService scheduler = backupScheduler;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        DataSourceFactory.DataSourceHandle handle = dataSourceHandle;
        if (handle != null) {
            try {
                handle.close();
            } catch (DatabaseException exception) {
                logger.log(Level.WARNING, "Failed to close data source after startup error", exception);
            }
        }
        ThreadPoolExecutor current = executor;
        if (current != null) {
            current.shutdown();
        }
        synchronized (lifecycleLock) {
            state = LifecycleState.CLOSED;
        }
    }

    private long retryDelayMillis(int failedAttempt) {
        long initial = config.retry().initialDelay().toMillis();
        long maximum = config.retry().maximumDelay().toMillis();
        if (initial == 0L) {
            return 0L;
        }
        int shift = Math.min(20, Math.max(0, failedAttempt - 1));
        long exponential;
        try {
            exponential = Math.multiplyExact(initial, 1L << shift);
        } catch (ArithmeticException exception) {
            exponential = maximum;
        }
        long capped = Math.min(maximum, exponential);
        long jitterBound = Math.max(1L, capped / 5L);
        long jitter = ThreadLocalRandom.current().nextLong(jitterBound);
        return Math.min(maximum, capped - (jitterBound / 2L) + jitter);
    }

    private static void sleep(long delayMillis) {
        if (delayMillis <= 0L) {
            return;
        }
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DatabaseUnavailableException("Database retry was interrupted", exception);
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private enum LifecycleState {
        NEW,
        STARTING,
        RUNNING,
        CLOSING,
        CLOSED
    }
}
