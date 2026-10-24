package com.verdantvice.storage;

import com.verdantvice.common.BlockPosition;
import com.verdantvice.database.DatabaseConfig;
import com.verdantvice.database.DatabaseManager;
import com.verdantvice.database.DatabaseType;
import com.verdantvice.farm.Farm;
import com.verdantvice.farm.FarmRegion;
import com.verdantvice.farm.FarmService;
import com.verdantvice.farm.FarmSettings;
import com.verdantvice.farm.FarmStorage;
import com.verdantvice.farm.StorageLinkResult;
import com.verdantvice.farm.StorageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FarmRepositoryStorageIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    private DatabaseConfig config;
    private DatabaseManager database;
    private FarmRepository repository;
    private final FarmService service = new FarmService();

    @BeforeEach
    void start() throws Exception {
        config = sqliteConfig(temporaryDirectory);
        database = new DatabaseManager(config, quietLogger());
        database.start().toCompletableFuture().get(15, TimeUnit.SECONDS);
        repository = new FarmRepository(database);
    }

    @AfterEach
    void close() {
        if (database != null) database.close();
    }

    @Test
    void placedManagerTableKeepsItsExactPositionAfterDatabaseRestart() throws Exception {
        Farm farm = farm();
        BlockPosition table = position(farm, 2, 70, 8);
        repository.create(farm, table).get(15, TimeUnit.SECONDS);
        assertEquals(table, repository.loadAll().get(15, TimeUnit.SECONDS)
                .tablePositions().get(farm.farmId()));

        database.close();
        database = new DatabaseManager(config, quietLogger());
        database.start().toCompletableFuture().get(15, TimeUnit.SECONDS);
        repository = new FarmRepository(database);
        FarmRepository.FarmSnapshot restarted = repository.loadAll().get(15, TimeUnit.SECONDS);
        assertEquals(table, restarted.tablePositions().get(farm.farmId()));
        assertEquals(farm.region(), restarted.farms().getFirst().region());
    }

    @Test
    void managerTableOutsideSelectedFarmNeverCreatesAPartialFarm() throws Exception {
        Farm farm = farm();
        BlockPosition outside = position(farm, 11, 70, 8);
        CompletionException failure = assertThrows(CompletionException.class,
                () -> repository.create(farm, outside).join());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertEquals("manager_table_outside_farm", failure.getCause().getMessage());
        assertTrue(repository.loadAll().get(15, TimeUnit.SECONDS).farms().isEmpty());
    }

    @Test
    void managerTableInAnotherWorldNeverCreatesAPartialFarm() throws Exception {
        Farm farm = farm();
        BlockPosition wrongWorld = new BlockPosition(UUID.randomUUID(), 2, 70, 8);
        CompletionException failure = assertThrows(CompletionException.class,
                () -> repository.create(farm, wrongWorld).join());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertTrue(repository.loadAll().get(15, TimeUnit.SECONDS).farms().isEmpty());
    }

    @Test
    void linkRestartUnlinkAndRelinkRemainExactAndAtomic() throws Exception {
        Farm farm = farm();
        repository.create(farm).get(15, TimeUnit.SECONDS);
        BlockPosition position = position(farm, 5, 64, 5);
        FarmStorage salary = storage(farm, position, StorageType.SALARY_STORAGE);
        StorageLinkResult link = service.linkStorage(
                farm, salary, List.of(), List.of(position));
        repository.linkStorage(salary, link.farm().orElseThrow())
                .get(15, TimeUnit.SECONDS);

        FarmRepository.FarmSnapshot first = repository.loadAll().get(15, TimeUnit.SECONDS);
        assertEquals(1, first.storages().size());
        assertEquals(salary.storageId(), first.farms().getFirst()
                .salaryStorage().orElseThrow());

        database.close();
        database = new DatabaseManager(config, quietLogger());
        database.start().toCompletableFuture().get(15, TimeUnit.SECONDS);
        repository = new FarmRepository(database);
        FarmRepository.FarmSnapshot restarted = repository.loadAll().get(15, TimeUnit.SECONDS);
        assertEquals(1, restarted.storages().size());
        Farm restartedFarm = restarted.farms().getFirst();
        FarmStorage restartedStorage = restarted.storages().iterator().next();

        StorageLinkResult unlink = service.unlinkStorage(restartedFarm, restartedStorage);
        repository.unlinkStorage(restartedStorage, unlink.farm().orElseThrow())
                .get(15, TimeUnit.SECONDS);
        FarmRepository.FarmSnapshot removed = repository.loadAll().get(15, TimeUnit.SECONDS);
        assertTrue(removed.storages().isEmpty());
        assertTrue(removed.farms().getFirst().linkedStorage().isEmpty());
        assertTrue(removed.farms().getFirst().salaryStorage().isEmpty());

        FarmStorage replacement = storage(removed.farms().getFirst(), position,
                StorageType.GENERAL_STORAGE);
        StorageLinkResult relink = service.linkStorage(
                removed.farms().getFirst(), replacement, List.of(), List.of(position));
        repository.linkStorage(replacement, relink.farm().orElseThrow())
                .get(15, TimeUnit.SECONDS);
        assertEquals(1, repository.loadAll().get(15, TimeUnit.SECONDS).storages().size());
    }

    @Test
    void failedFarmVersionUpdateRollsBackInsertedStorage() throws Exception {
        Farm farm = farm();
        repository.create(farm).get(15, TimeUnit.SECONDS);
        database.update("UPDATE farms SET row_version=row_version+1 WHERE farm_id=?",
                farm.farmId()).get(15, TimeUnit.SECONDS);
        BlockPosition position = position(farm, 5, 64, 5);
        FarmStorage storage = storage(farm, position, StorageType.SEED_STORAGE);
        Farm updated = service.linkStorage(
                farm, storage, List.of(), List.of(position)).farm().orElseThrow();

        CompletionException failure = assertThrows(CompletionException.class,
                () -> repository.linkStorage(storage, updated).join());
        assertFalse(failure.getMessage().isBlank());
        int stored = database.read(session -> session.queryOne(
                "SELECT COUNT(*) FROM farm_storages WHERE farm_id=?",
                result -> result.getInt(1), farm.farmId()).orElseThrow())
                .get(15, TimeUnit.SECONDS);
        assertEquals(0, stored);
    }

    private static Farm farm() {
        UUID owner = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        return new FarmService().create(
                UUID.randomUUID(), owner, "Storage Farm",
                new FarmRegion(world, 0, 0, 0, 10, 255, 10),
                64, 5, FarmSettings.defaults(), List.of(), Instant.now());
    }

    private static BlockPosition position(Farm farm, int x, int y, int z) {
        return new BlockPosition(farm.region().worldUuid(), x, y, z);
    }

    private static FarmStorage storage(Farm farm, BlockPosition position, StorageType type) {
        return new FarmStorage(UUID.randomUUID(), farm.farmId(), position,
                type, true, Instant.now());
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
                FarmRepositoryStorageIntegrationTest.class.getName() + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        return logger;
    }
}
