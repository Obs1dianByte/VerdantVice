package com.verdantvice.storage;

import com.verdantvice.common.BlockPosition;
import com.verdantvice.database.DatabaseConfig;
import com.verdantvice.database.DatabaseManager;
import com.verdantvice.database.DatabaseType;
import com.verdantvice.genetics.GeneticClass;
import com.verdantvice.genetics.GeneticProfile;
import com.verdantvice.plant.GrowthStage;
import com.verdantvice.plant.Plant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlantRepositoryConcurrencyTest {
    @TempDir
    Path temporaryDirectory;

    private DatabaseManager database;
    private PlantRepository repository;

    @BeforeEach
    void startDatabase() {
        database = new DatabaseManager(config(temporaryDirectory), Logger.getAnonymousLogger());
        database.start().toCompletableFuture().join();
        repository = new PlantRepository(database);
    }

    @AfterEach
    void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void nearlySimultaneousSavesAreSerializedAndIncreaseVersion() {
        Fixture fixture = fixture();
        repository.create(fixture.plant(), fixture.genetics()).join();
        Plant first = simulated(fixture.plant(), 10.0, 1);
        Plant second = simulated(first, 20.0, 2);

        CompletableFuture<Void> firstSave = repository.save(first, "TICK_ONE");
        CompletableFuture<Void> secondSave = repository.save(second, "TICK_TWO");
        CompletableFuture.allOf(firstSave, secondSave).join();

        PlantRepository.StoredPlant stored = repository.find(first.uniqueId()).join().orElseThrow();
        assertEquals(2L, stored.rowVersion());
        assertEquals(second, stored.plant());
        for (int attempt = 0; attempt < 100 && repository.activeWriteQueues() != 0; attempt++) {
            Thread.onSpinWait();
        }
        assertEquals(0, repository.activeWriteQueues());
    }

    @Test
    void staleSaveCannotOverwriteNewerStateAndNextSaveStillRuns() {
        Fixture fixture = fixture();
        repository.create(fixture.plant(), fixture.genetics()).join();
        Plant newer = simulated(fixture.plant(), 30.0, 3);
        Plant stale = simulated(fixture.plant(), 5.0, 1);
        repository.save(newer, "NEWER").join();

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> repository.save(stale, "STALE").join()
        );
        assertInstanceOf(ConcurrentPlantModificationException.class, failure.getCause());

        Plant newest = simulated(newer, 40.0, 4);
        repository.save(newest, "RECOVERY").join();
        PlantRepository.StoredPlant stored = repository.find(newest.uniqueId()).join().orElseThrow();
        assertEquals(2L, stored.rowVersion());
        assertEquals(newest, stored.plant());
    }

    @Test
    void plantingAndSeveralSimulationTicksDoNotConflictOrSpamRetries() {
        Fixture fixture = fixture();
        repository.create(fixture.plant(), fixture.genetics()).join();
        Plant current = fixture.plant();
        for (int tick = 1; tick <= 5; tick++) {
            current = simulated(current, tick * 10.0, tick);
            repository.save(current, "GROWTH_UPDATED").join();
        }
        PlantRepository.StoredPlant stored = repository.find(current.uniqueId()).join().orElseThrow();
        assertEquals(5L, stored.rowVersion());
        assertEquals(current, stored.plant());
    }

    @Test
    void logicalOptimisticConflictIsNotRetriedByDatabaseManager() {
        AtomicInteger attempts = new AtomicInteger();
        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> database.transaction(session -> {
                    attempts.incrementAndGet();
                    throw new ConcurrentPlantModificationException("logical conflict");
                }).join()
        );
        assertInstanceOf(ConcurrentPlantModificationException.class, failure.getCause());
        assertEquals(1, attempts.get());
    }

    private Fixture fixture() {
        Instant plantedAt = Instant.parse("2026-01-01T00:00:00Z");
        UUID geneticsId = UUID.randomUUID();
        GeneticProfile genetics = new GeneticProfile(
                geneticsId,
                GeneticClass.COMMON,
                1.0,
                1.0,
                1.0,
                1.0,
                1.0,
                1.0,
                1.0,
                plantedAt
        );
        Plant plant = new Plant(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BlockPosition(UUID.randomUUID(), 10, 64, 10),
                "test_strain",
                geneticsId,
                GrowthStage.SEED,
                0.0,
                100.0,
                75.0,
                75.0,
                10,
                0.0,
                100.0,
                Optional.empty(),
                0.0,
                50.0,
                50.0,
                50.0,
                plantedAt,
                plantedAt,
                plantedAt,
                plantedAt.plus(Duration.ofHours(12)),
                plantedAt.plus(Duration.ofHours(14)),
                Optional.empty(),
                Optional.empty()
        );
        return new Fixture(plant, genetics);
    }

    private Plant simulated(Plant source, double progress, long seconds) {
        return source.withSimulation(
                GrowthStage.VEGETATIVE,
                progress,
                source.health(),
                source.hydration(),
                source.nutrition(),
                source.stress(),
                source.yieldPotential(),
                source.qualityPotential(),
                source.plantedAt().plusSeconds(seconds)
        );
    }

    private DatabaseConfig config(Path root) {
        DatabaseConfig.NetworkSettings network = new DatabaseConfig.NetworkSettings(
                "localhost",
                3306,
                "verdantvice",
                "verdantvice",
                "",
                Map.of()
        );
        return new DatabaseConfig(
                DatabaseType.SQLITE,
                root,
                root.resolve("data/verdantvice-test.db"),
                root.resolve("backups"),
                false,
                Duration.ZERO,
                2,
                Duration.ofSeconds(2),
                network,
                network,
                new DatabaseConfig.PoolSettings(
                        1,
                        2,
                        Duration.ofSeconds(2),
                        Duration.ofMinutes(5),
                        Duration.ofSeconds(1),
                        Duration.ofMinutes(1)
                ),
                new DatabaseConfig.RetrySettings(
                        3,
                        Duration.ZERO,
                        Duration.ofMillis(10)
                ),
                4,
                128,
                Duration.ofSeconds(5)
        );
    }

    private record Fixture(Plant plant, GeneticProfile genetics) {
    }
}
