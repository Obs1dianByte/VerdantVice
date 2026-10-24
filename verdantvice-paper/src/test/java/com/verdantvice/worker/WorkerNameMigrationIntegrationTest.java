package com.verdantvice.worker;

import com.verdantvice.database.DatabaseConfig;
import com.verdantvice.database.DatabaseManager;
import com.verdantvice.database.DatabaseType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorkerNameMigrationIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    private DatabaseManager database;
    private WorkerRepository repository;

    @BeforeEach
    void start() throws Exception {
        database = new DatabaseManager(sqliteConfig(temporaryDirectory), quietLogger());
        database.start().toCompletableFuture().get(15, TimeUnit.SECONDS);
        repository = new WorkerRepository(database);
    }

    @AfterEach
    void close() {
        if (database != null) database.close();
    }

    @Test
    void migrationBacksUpDatabaseAndChangesOnlyDirtyWorkerName() throws Exception {
        UUID worker = UUID.randomUUID();
        UUID entity = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID farm = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        UUID task = UUID.randomUUID();
        Instant now = Instant.now();
        database.transaction(session -> {
            session.update("INSERT INTO players (player_uuid, created_at, updated_at, "
                            + "last_seen_at, row_version) VALUES (?, ?, ?, ?, 0)",
                    owner, now, now, now);
            session.update("INSERT INTO farms (farm_id, owner_uuid, farm_name, world_uuid, "
                            + "table_x, table_y, table_z, created_at, updated_at, row_version) "
                            + "VALUES (?, ?, ?, ?, 0, 64, 0, ?, ?, 0)",
                    farm, owner, "Farm", world, now, now);
            session.update("""
                    INSERT INTO workers (
                      worker_uuid, entity_uuid, farm_id, owner_uuid, worker_name,
                      specialization, worker_state, morale, experience, worker_level,
                      daily_wage, unpaid_days, current_task_id, schedule_json,
                      worker_data_json, created_at, updated_at, row_version
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                    """,
                    worker, entity, farm, owner, "\u00b4\u2510\u017b2Herbalist \u0141ukasz",
                    "GENERAL", "IDLE", 63.5, 987L, 7, 12, 2, task,
                    "{}", "{\"lastPaidMinecraftDay\":42}", now, now);
            return null;
        }).get(15, TimeUnit.SECONDS);

        WorkerNameMigrationService.MigrationReport report =
                new WorkerNameMigrationService(database, repository, quietLogger())
                        .migrate("Herbalist").get(15, TimeUnit.SECONDS);
        assertEquals(1, report.changedRows());
        assertTrue(report.backup().isPresent());
        assertTrue(Files.exists(report.backup().orElseThrow()));

        MigratedRow row = database.read(session -> session.queryOne("""
                        SELECT worker_uuid, entity_uuid, worker_name, morale, experience,
                               worker_level, daily_wage, unpaid_days, current_task_id,
                               worker_data_json, row_version
                        FROM workers WHERE worker_uuid=?
                        """,
                result -> new MigratedRow(
                        UUID.fromString(result.getString("worker_uuid")),
                        UUID.fromString(result.getString("entity_uuid")),
                        result.getString("worker_name"),
                        result.getDouble("morale"),
                        result.getLong("experience"),
                        result.getInt("worker_level"),
                        result.getInt("daily_wage"),
                        result.getInt("unpaid_days"),
                        UUID.fromString(result.getString("current_task_id")),
                        result.getString("worker_data_json"),
                        result.getLong("row_version")), worker).orElseThrow())
                .get(15, TimeUnit.SECONDS);

        assertEquals(worker, row.workerUuid());
        assertEquals(entity, row.entityUuid());
        assertEquals("Herbalist \u0141ukasz", row.name());
        assertEquals(63.5, row.morale());
        assertEquals(987L, row.experience());
        assertEquals(7, row.level());
        assertEquals(12, row.wage());
        assertEquals(2, row.unpaidDays());
        assertEquals(task, row.task());
        assertEquals("{\"lastPaidMinecraftDay\":42}", row.workerData());
        assertEquals(1L, row.version());
    }

    private record MigratedRow(
            UUID workerUuid, UUID entityUuid, String name, double morale,
            long experience, int level, int wage, int unpaidDays, UUID task,
            String workerData, long version) {
    }

    private static DatabaseConfig sqliteConfig(Path root) {
        DatabaseConfig.NetworkSettings network = new DatabaseConfig.NetworkSettings(
                "localhost", 3306, "verdantvice", "verdantvice", "test-only", Map.of());
        return new DatabaseConfig(
                DatabaseType.SQLITE, root, root.resolve("data/verdantvice.db"),
                root.resolve("backups"), false, Duration.ZERO, 4,
                Duration.ofSeconds(5), network, network,
                new DatabaseConfig.PoolSettings(
                        0, 4, Duration.ofSeconds(5), Duration.ofMinutes(30),
                        Duration.ofSeconds(2), Duration.ofMinutes(10)),
                new DatabaseConfig.RetrySettings(
                        3, Duration.ofMillis(10), Duration.ofMillis(100)),
                2, 128, Duration.ofSeconds(10));
    }

    private static Logger quietLogger() {
        Logger logger = Logger.getLogger(
                WorkerNameMigrationIntegrationTest.class.getName() + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        return logger;
    }
}
