package com.verdantvice.worker;

import com.verdantvice.database.DatabaseConfig;
import com.verdantvice.database.DatabaseManager;
import com.verdantvice.database.DatabaseType;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class WorkerReliabilityTestSupport {
    private WorkerReliabilityTestSupport() {
    }

    public static DatabaseManager start(Path root) throws Exception {
        DatabaseManager database = new DatabaseManager(config(root), quietLogger());
        database.start().toCompletableFuture().get(15, TimeUnit.SECONDS);
        return database;
    }

    public static ParentGraph insertParentGraph(DatabaseManager database) throws Exception {
        UUID owner = UUID.randomUUID();
        UUID farm = UUID.randomUUID();
        UUID worker = UUID.randomUUID();
        UUID entity = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        UUID storage = UUID.randomUUID();
        Instant now = Instant.now();
        database.transaction(session -> {
            session.update("INSERT INTO players (player_uuid, locale, created_at, updated_at, "
                            + "last_seen_at, row_version) VALUES (?, 'en_US', ?, ?, ?, 0)",
                    owner, now, now, now);
            session.update("INSERT INTO farms (farm_id, owner_uuid, farm_name, world_uuid, "
                            + "table_x, table_y, table_z, created_at, updated_at, row_version) "
                            + "VALUES (?, ?, 'Reliability Farm', ?, 0, 64, 0, ?, ?, 0)",
                    farm, owner, world, now, now);
            session.update("""
                    INSERT INTO workers (
                      worker_uuid, entity_uuid, farm_id, owner_uuid, worker_name,
                      specialization, worker_state, morale, experience, worker_level,
                      daily_wage, unpaid_days, current_task_id, schedule_json,
                      worker_data_json, created_at, updated_at, row_version
                    ) VALUES (?, ?, ?, ?, 'Worker', 'HARVESTER', 'IDLE', 75, 0, 1,
                              4, 0, NULL, '{}', '{"lastPaidMinecraftDay":-1}', ?, ?, 0)
                    """,
                    worker, entity, farm, owner, now, now);
            session.update("""
                    INSERT INTO farm_storages (
                      storage_id, farm_id, storage_type, world_uuid,
                      block_x, block_y, block_z, created_at, updated_at, row_version
                    ) VALUES (?, ?, 'FRESH_HARVEST_STORAGE', ?, 1, 64, 1, ?, ?, 0)
                    """,
                    storage, farm, world, now, now);
            return null;
        }).get(15, TimeUnit.SECONDS);
        return new ParentGraph(owner, farm, worker, entity, world, storage, now);
    }

    public static UUID insertBatch(DatabaseManager database, ParentGraph graph) throws Exception {
        UUID batch = UUID.randomUUID();
        Instant now = graph.now().plusSeconds(1);
        database.transaction(session -> {
            session.update("""
                    INSERT INTO product_batches (
                      batch_id, owner_uuid, strain_composition_json, genetic_origin_json,
                      fresh_weight, dry_weight, quality_score, quality_grade,
                      contamination, freshness, harvest_timing, drying_data_json,
                      curing_data_json, batch_state, created_at, updated_at,
                      signature, schema_version, row_version
                    ) VALUES (?, ?, '{}', '{}', 12.5, NULL, 72.0, 'PREMIUM',
                              0.0, 100.0, 'OPTIMAL', NULL, NULL, 'FRESH', ?, ?, ?, 1, 0)
                    """,
                    batch, graph.owner(), now, now, "a".repeat(64));
            return null;
        }).get(15, TimeUnit.SECONDS);
        return batch;
    }

    public static DatabaseConfig config(Path root) {
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

    public static Logger quietLogger() {
        Logger logger = Logger.getLogger(
                WorkerReliabilityTestSupport.class.getName() + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        return logger;
    }

    public record ParentGraph(
            UUID owner,
            UUID farm,
            UUID worker,
            UUID entity,
            UUID world,
            UUID storage,
            Instant now
    ) {
    }
}
