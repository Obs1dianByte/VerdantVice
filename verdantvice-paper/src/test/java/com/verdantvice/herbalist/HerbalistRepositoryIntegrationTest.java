package com.verdantvice.herbalist;

import com.verdantvice.database.DatabaseConfig;
import com.verdantvice.database.DatabaseManager;
import com.verdantvice.database.DatabaseType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HerbalistRepositoryIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    private DatabaseConfig databaseConfig;
    private DatabaseManager database;
    private HerbalistRepository repository;
    private YamlConfiguration tiers;

    @BeforeEach
    void startDatabase() throws Exception {
        databaseConfig = sqliteConfig(temporaryDirectory);
        database = new DatabaseManager(databaseConfig, quietLogger());
        database.start().toCompletableFuture().get(15, TimeUnit.SECONDS);
        repository = new HerbalistRepository(database);
        tiers = thresholds();
    }

    @AfterEach
    void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void naturalLimitForceDuplicateAndWorkerRemainDistinct() throws Exception {
        UUID world = UUID.randomUUID();
        Instant now = Instant.now();

        HerbalistRepository.CreationResult first = repository.createIfBelowLimit(
                UUID.randomUUID(), UUID.randomUUID(), world,
                0, 64, 0, 32, 1, 0, now
        ).get(15, TimeUnit.SECONDS);
        assertEquals(HerbalistRepository.CreationStatus.CREATED, first.status());

        UUID limitedEntity = UUID.randomUUID();
        HerbalistRepository.CreationResult limited = repository.createIfBelowLimit(
                UUID.randomUUID(), limitedEntity, world,
                1, 64, 1, 32, 1, 0, now
        ).get(15, TimeUnit.SECONDS);
        assertEquals(HerbalistRepository.CreationStatus.VILLAGE_LIMIT, limited.status());

        HerbalistRepository.CreationResult forced = repository.createIgnoringVillageLimit(
                UUID.randomUUID(), limitedEntity, world,
                1, 64, 1, 32, 1, 0,
                HerbalistTier.NOVICE, tiers, now
        ).get(15, TimeUnit.SECONDS);
        assertEquals(HerbalistRepository.CreationStatus.CREATED_FORCED, forced.status());

        HerbalistRepository.CreationResult duplicate = repository.createIgnoringVillageLimit(
                UUID.randomUUID(), limitedEntity, world,
                1, 64, 1, 32, 1, 0,
                HerbalistTier.NOVICE, tiers, now
        ).get(15, TimeUnit.SECONDS);
        assertEquals(HerbalistRepository.CreationStatus.ALREADY_EXISTS, duplicate.status());
        assertEquals(
                forced.optionalRecord().orElseThrow().herbalistUuid(),
                duplicate.optionalRecord().orElseThrow().herbalistUuid()
        );

        UUID workerEntity = UUID.randomUUID();
        insertWorker(workerEntity, world, now);
        HerbalistRepository.CreationResult worker = repository.createIgnoringVillageLimit(
                UUID.randomUUID(), workerEntity, world,
                5, 64, 5, 32, 1, 0,
                HerbalistTier.NOVICE, tiers, now
        ).get(15, TimeUnit.SECONDS);
        assertEquals(HerbalistRepository.CreationStatus.WORKER, worker.status());
    }

    @Test
    void configuredExpertTierNeverDowngradesAndConcurrentProgressIsSummed() throws Exception {
        tiers.set("levels.VERDANT_EXPERT", 777L);
        UUID herbalistId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        Instant now = Instant.now();

        HerbalistRecord created = repository.createIgnoringVillageLimit(
                herbalistId, entityId, world,
                0, 64, 0, 32, 0, 10,
                HerbalistTier.VERDANT_EXPERT, tiers, now
        ).get(15, TimeUnit.SECONDS).optionalRecord().orElseThrow();
        assertEquals(HerbalistTier.VERDANT_EXPERT, created.tier());
        assertEquals(777L, created.experience());
        assertEquals(10, created.reputation());

        HerbalistRecord afterOne = repository.addProgress(
                herbalistId, 1L, 1, tiers, now.plusSeconds(1)
        ).get(15, TimeUnit.SECONDS);
        assertEquals(HerbalistTier.VERDANT_EXPERT, afterOne.tier());
        assertEquals(778L, afterOne.experience());

        int concurrentPurchases = 100;
        List<CompletableFuture<HerbalistRecord>> progress = new ArrayList<>();
        for (int index = 0; index < concurrentPurchases; index++) {
            progress.add(repository.addProgress(
                    herbalistId, 1L, 1, tiers, now.plusSeconds(2L + index)
            ));
        }
        CompletableFuture.allOf(progress.toArray(CompletableFuture[]::new))
                .get(30, TimeUnit.SECONDS);

        HerbalistRecord updated = repository.find(herbalistId)
                .get(15, TimeUnit.SECONDS).orElseThrow();
        assertEquals(878L, updated.experience());
        assertEquals(HerbalistTier.VERDANT_EXPERT, updated.tier());
        assertEquals(100, updated.reputation());
        assertEquals(101L, updated.version());

        tiers.set("levels.VERDANT_EXPERT", 10_000L);
        HerbalistRecord afterChangedThreshold = repository.addProgress(
                herbalistId, 1L, 0, tiers, now.plusSeconds(200)
        ).get(15, TimeUnit.SECONDS);
        assertEquals(HerbalistTier.VERDANT_EXPERT, afterChangedThreshold.tier());
        assertEquals(879L, afterChangedThreshold.experience());

        database.close();
        database = new DatabaseManager(databaseConfig, quietLogger());
        database.start().toCompletableFuture().get(15, TimeUnit.SECONDS);
        repository = new HerbalistRepository(database);

        HerbalistRecord afterRestart = repository.find(herbalistId)
                .get(15, TimeUnit.SECONDS).orElseThrow();
        assertEquals(HerbalistTier.VERDANT_EXPERT, afterRestart.tier());
        assertEquals(879L, afterRestart.experience());
    }

    @Test
    void administrativeTierChangePreservesIdentityReputationAndHigherExperience() throws Exception {
        UUID herbalistId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        Instant now = Instant.now();
        HerbalistRecord created = repository.createIgnoringVillageLimit(
                herbalistId, entityId, world,
                9, 70, 9, 32, 0, 42, now
        ).get(15, TimeUnit.SECONDS).optionalRecord().orElseThrow();

        HerbalistRecord expert = repository.setTierAtLeast(
                herbalistId, HerbalistTier.VERDANT_EXPERT, tiers, now.plusSeconds(1)
        ).get(15, TimeUnit.SECONDS);
        assertEquals(herbalistId, expert.herbalistUuid());
        assertEquals(entityId, expert.entityUuid());
        assertEquals(world, expert.worldUuid());
        assertEquals(42, expert.reputation());
        assertEquals(HerbalistTier.VERDANT_EXPERT, expert.tier());
        assertEquals(220L, expert.experience());

        HerbalistRecord attemptedDowngrade = repository.setTierAtLeast(
                herbalistId, HerbalistTier.NOVICE, tiers, now.plusSeconds(2)
        ).get(15, TimeUnit.SECONDS);
        assertEquals(HerbalistTier.VERDANT_EXPERT, attemptedDowngrade.tier());
        assertEquals(220L, attemptedDowngrade.experience());
        assertTrue(attemptedDowngrade.version() > created.version());
    }

    private void insertWorker(UUID entityId, UUID worldId, Instant now) throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID farmId = UUID.randomUUID();
        database.transaction(session -> {
            session.update("""
                            INSERT INTO players (
                              player_uuid, created_at, updated_at, last_seen_at, row_version
                            ) VALUES (?, ?, ?, ?, 0)
                            """,
                    ownerId, now, now, now);
            session.update("""
                            INSERT INTO farms (
                              farm_id, owner_uuid, farm_name, world_uuid,
                              table_x, table_y, table_z, created_at, updated_at, row_version
                            ) VALUES (?, ?, ?, ?, 0, 64, 0, ?, ?, 0)
                            """,
                    farmId, ownerId, "Integration Farm", worldId, now, now);
            session.update("""
                            INSERT INTO workers (
                              worker_uuid, entity_uuid, farm_id, owner_uuid, worker_name,
                              specialization, worker_state, schedule_json, created_at,
                              updated_at, row_version
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                            """,
                    UUID.randomUUID(), entityId, farmId, ownerId, "Worker",
                    "GENERAL", "IDLE", "{}", now, now);
            return null;
        }).get(15, TimeUnit.SECONDS);
    }

    private static YamlConfiguration thresholds() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("levels.NOVICE", 0L);
        configuration.set("levels.APPRENTICE", 15L);
        configuration.set("levels.JOURNEYMAN", 40L);
        configuration.set("levels.EXPERT", 80L);
        configuration.set("levels.MASTER", 140L);
        configuration.set("levels.VERDANT_EXPERT", 220L);
        return configuration;
    }

    private static DatabaseConfig sqliteConfig(Path root) {
        DatabaseConfig.NetworkSettings unusedNetwork = new DatabaseConfig.NetworkSettings(
                "localhost", 3306, "verdantvice", "verdantvice", "test-only", Map.of()
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
                        0, 4, Duration.ofSeconds(5), Duration.ofMinutes(30),
                        Duration.ofSeconds(2), Duration.ofMinutes(10)
                ),
                new DatabaseConfig.RetrySettings(
                        3, Duration.ofMillis(10), Duration.ofMillis(100)
                ),
                2,
                128,
                Duration.ofSeconds(10)
        );
    }

    private static Logger quietLogger() {
        Logger logger = Logger.getLogger(
                HerbalistRepositoryIntegrationTest.class.getName() + "." + UUID.randomUUID()
        );
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        return logger;
    }
}
