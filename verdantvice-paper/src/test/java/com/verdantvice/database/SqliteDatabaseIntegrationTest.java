package com.verdantvice.database;

import com.verdantvice.database.item.CasStatus;
import com.verdantvice.database.item.ItemRegistration;
import com.verdantvice.database.item.ItemRegistryRepository;
import com.verdantvice.database.item.ItemRegistryState;
import com.verdantvice.database.item.RegistrationStatus;
import com.verdantvice.database.journal.JournalExecution;
import com.verdantvice.database.journal.OperationJournalRepository;
import com.verdantvice.database.journal.OperationKey;
import com.verdantvice.database.migration.MigrationIntegrityException;
import com.verdantvice.database.migration.MigrationReport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SqliteDatabaseIntegrationTest {
    private static final Set<String> REQUIRED_TABLES = Set.of(
            "players",
            "player_skills",
            "player_tolerance",
            "farms",
            "farm_members",
            "farm_storages",
            "plants",
            "plant_events",
            "genetic_profiles",
            "custom_strains",
            "product_batches",
            "drying_jobs",
            "curing_jobs",
            "herbalists",
            "workers",
            "worker_tasks",
            "worker_payments",
            "worker_harvest_deliveries",
            "processing_deliveries",
            "contracts",
            "market_state",
            "trade_history",
            "notifications",
            "item_registry",
            "operation_journal",
            "vv_schema_history"
    );

    @TempDir
    Path temporaryDirectory;

    private DatabaseManager database;

    @AfterEach
    void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void freshSqliteMigratesSupportsAtomicRepositoriesBacksUpAndRejectsChecksumTampering() throws Exception {
        DatabaseConfig config = sqliteConfig(temporaryDirectory);
        database = new DatabaseManager(config, quietLogger());

        MigrationReport firstStart = database.start().toCompletableFuture().get(15, TimeUnit.SECONDS);
        assertEquals(5, firstStart.currentVersion());
        assertEquals(java.util.List.of(1, 2, 3, 4, 5), firstStart.appliedVersions());
        assertTrue(database.isRunning());

        Set<String> tables = Set.copyOf(database.read(session -> session.query(
                "SELECT name FROM sqlite_master WHERE type = ? AND name NOT LIKE ?",
                resultSet -> resultSet.getString(1),
                "table",
                "sqlite_%"
        )).get(15, TimeUnit.SECONDS));
        assertTrue(tables.containsAll(REQUIRED_TABLES),
                () -> "Missing tables: " + difference(REQUIRED_TABLES, tables));

        int successfulMigrations = database.read(session -> session.queryOne(
                "SELECT COUNT(*) FROM vv_schema_history WHERE success = ?",
                resultSet -> resultSet.getInt(1),
                1
        ).orElseThrow()).get(15, TimeUnit.SECONDS);
        assertEquals(5, successfulMigrations);

        ItemRegistryRepository items = new ItemRegistryRepository(database);
        UUID instanceId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String signature = "a".repeat(64);
        ItemRegistration registration = new ItemRegistration(
                instanceId,
                "PRODUCT",
                "verdantvice:premium_batch",
                UUID.randomUUID(),
                ownerId,
                91.25D,
                "verdant_vice",
                createdAt,
                signature,
                1,
                "{\"source\":\"integration-test\"}"
        );

        var registered = items.register(registration).get(15, TimeUnit.SECONDS);
        assertEquals(RegistrationStatus.REGISTERED, registered.status());
        assertEquals(ItemRegistryState.ACTIVE, registered.record().state());
        assertEquals(0L, registered.record().version());
        assertEquals(RegistrationStatus.ALREADY_REGISTERED,
                items.register(registration).get(15, TimeUnit.SECONDS).status());

        UUID reservationToken = UUID.randomUUID();
        var reserved = items.reserve(
                instanceId,
                0L,
                signature,
                reservationToken,
                ownerId.toString(),
                Instant.now().plus(Duration.ofMinutes(5)),
                "trade:reserve:" + instanceId
        ).get(15, TimeUnit.SECONDS);
        assertEquals(CasStatus.APPLIED, reserved.status());
        assertNotNull(reserved.current());
        assertEquals(ItemRegistryState.RESERVED, reserved.current().state());
        assertEquals(1L, reserved.current().version());

        String consumeOperation = "trade:consume:" + instanceId;
        Instant consumedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        var consumed = items.consume(
                instanceId,
                1L,
                signature,
                reservationToken,
                consumeOperation,
                consumedAt
        ).get(15, TimeUnit.SECONDS);
        assertEquals(CasStatus.APPLIED, consumed.status());
        assertEquals(ItemRegistryState.CONSUMED, consumed.current().state());
        assertEquals(2L, consumed.current().version());

        var replayedConsume = items.consume(
                instanceId,
                1L,
                signature,
                reservationToken,
                consumeOperation,
                consumedAt
        ).get(15, TimeUnit.SECONDS);
        assertEquals(CasStatus.ALREADY_APPLIED, replayedConsume.status());
        assertEquals(2L, replayedConsume.current().version());

        OperationJournalRepository journal = new OperationJournalRepository(database);
        OperationKey journalKey = OperationKey.forPayload(
                "contract:reward:" + UUID.randomUUID(),
                "CONTRACT_REWARD",
                ownerId,
                "player:" + ownerId,
                "{\"reward\":4,\"currency\":\"EMERALD\"}"
        );
        AtomicInteger executions = new AtomicInteger();
        var firstExecution = journal.executeTransactionally(
                journalKey,
                Duration.ofMinutes(1),
                session -> {
                    executions.incrementAndGet();
                    assertTrue(session.exists(
                            "SELECT 1 FROM item_registry WHERE instance_id = ? AND registry_state = ?",
                            instanceId,
                            ItemRegistryState.CONSUMED
                    ));
                    return "{\"granted\":true}";
                }
        ).get(15, TimeUnit.SECONDS);
        assertEquals(JournalExecution.Status.EXECUTED, firstExecution.status());

        var replayedExecution = journal.executeTransactionally(
                journalKey,
                Duration.ofMinutes(1),
                session -> {
                    executions.incrementAndGet();
                    return "{\"mustNotRun\":true}";
                }
        ).get(15, TimeUnit.SECONDS);
        assertEquals(JournalExecution.Status.REPLAYED, replayedExecution.status());
        assertEquals("{\"granted\":true}", replayedExecution.resultJson());
        assertEquals(1, executions.get());

        Path backup = database.backupNow().get(15, TimeUnit.SECONDS);
        assertTrue(Files.isRegularFile(backup));
        assertTrue(Files.size(backup) > 0L);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + backup);
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM vv_schema_history WHERE success = ?"
             )) {
            statement.setInt(1, 1);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                assertEquals(5, resultSet.getInt(1));
            }
        }

        database.close();
        database = new DatabaseManager(config, quietLogger());
        MigrationReport idempotentRestart =
                database.start().toCompletableFuture().get(15, TimeUnit.SECONDS);
        assertEquals(5, idempotentRestart.currentVersion());
        assertTrue(idempotentRestart.appliedVersions().isEmpty());

        database.update(
                "UPDATE vv_schema_history SET checksum = ? WHERE version = ?",
                "0".repeat(64),
                1
        ).get(15, TimeUnit.SECONDS);
        database.close();
        database = new DatabaseManager(config, quietLogger());

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> database.start().toCompletableFuture().join()
        );
        assertInstanceOf(MigrationIntegrityException.class, rootCause(failure));
        assertTrue(rootCause(failure).getMessage().contains("Checksum mismatch"));
    }

    private static DatabaseConfig sqliteConfig(Path root) {
        DatabaseConfig.NetworkSettings unusedNetwork = new DatabaseConfig.NetworkSettings(
                "localhost",
                3306,
                "verdantvice",
                "verdantvice",
                "test-only",
                Map.of()
        );
        return new DatabaseConfig(
                DatabaseType.SQLITE,
                root,
                root.resolve("data/verdantvice.db"),
                root.resolve("backups"),
                false,
                Duration.ZERO,
                4,
                Duration.ofSeconds(5),
                unusedNetwork,
                unusedNetwork,
                new DatabaseConfig.PoolSettings(
                        0,
                        2,
                        Duration.ofSeconds(5),
                        Duration.ofMinutes(30),
                        Duration.ofSeconds(2),
                        Duration.ofMinutes(10)
                ),
                new DatabaseConfig.RetrySettings(
                        3,
                        Duration.ofMillis(10),
                        Duration.ofMillis(100)
                ),
                1,
                128,
                Duration.ofSeconds(10)
        );
    }

    private static Logger quietLogger() {
        Logger logger = Logger.getLogger(
                SqliteDatabaseIntegrationTest.class.getName() + "." + UUID.randomUUID()
        );
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        return logger;
    }

    private static Set<String> difference(Set<String> expected, Set<String> actual) {
        java.util.HashSet<String> result = new java.util.HashSet<>(expected);
        result.removeAll(actual);
        return Set.copyOf(result);
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
