package com.verdantvice.database;

import com.verdantvice.database.item.CasStatus;
import com.verdantvice.database.item.ItemRegistration;
import com.verdantvice.database.item.ItemRegistryRepository;
import com.verdantvice.database.item.ItemRegistryState;
import com.verdantvice.database.item.RegistrationStatus;
import com.verdantvice.database.migration.MigrationCatalog;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.mariadb.MariaDBContainer;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Exercises the actual production pool, dialect, migrations and atomic registry. */
class NetworkDatabaseIntegrationTest {
    @TempDir Path temporaryDirectory;

    @ParameterizedTest(name = "{0}: migrations, rollback, concurrent consumption and restart")
    @EnumSource(value = DatabaseType.class, names = {"MYSQL", "MARIADB"})
    void productionRepositoriesWorkOnNetworkDatabase(DatabaseType type) throws Exception {
        boolean dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
        if (Boolean.getBoolean("verdantvice.network.requireDocker")) {
            assertTrue(dockerAvailable, "CI requires actual Docker database assertions");
        }
        assumeTrue(dockerAvailable,
                "Docker is unavailable; network database assertions were not executed");
        try (JdbcDatabaseContainer<?> container = type == DatabaseType.MYSQL
                ? new MySQLContainer("mysql:8.4")
                : new MariaDBContainer("mariadb:11.4")) {
            container.start();
            DatabaseConfig config = config(type, container);
            UUID owner = UUID.randomUUID();
            UUID instance = UUID.randomUUID();
            String signature = "a".repeat(64);
            Instant now = Instant.ofEpochMilli(Instant.now().toEpochMilli());
            ItemRegistration item = registration(instance, owner, signature, now);
            int schema = MigrationCatalog.forDatabase(type).size();
            try (DatabaseManager database = database(config)) {
                assertEquals(schema, database.start().toCompletableFuture()
                        .get(60, TimeUnit.SECONDS).currentVersion());
                ItemRegistryRepository registry = new ItemRegistryRepository(database);
                assertEquals(RegistrationStatus.REGISTERED,
                        registry.register(item).get(15, TimeUnit.SECONDS).status());
                assertEquals(RegistrationStatus.ALREADY_REGISTERED,
                        registry.register(item).get(15, TimeUnit.SECONDS).status());

                UUID rolledBack = UUID.randomUUID();
                assertThrows(CompletionException.class, () -> database.transaction(session -> {
                    registry.register(session, registration(rolledBack, owner, signature, now));
                    throw new SQLException("Explicit transaction rollback", "45000");
                }).join());
                assertTrue(registry.find(rolledBack).get(15, TimeUnit.SECONDS).isEmpty());

                var attempts = new ArrayList<CompletableFuture<com.verdantvice.database.item.ItemCasResult>>();
                var tokens = new ArrayList<UUID>();
                for (int index = 0; index < 8; index++) {
                    UUID token = UUID.randomUUID();
                    tokens.add(token);
                    attempts.add(registry.reserve(instance, 0, signature, token,
                            owner.toString(), now.plusSeconds(60), "race:" + index));
                }
                CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new))
                        .get(30, TimeUnit.SECONDS);
                assertEquals(1, attempts.stream()
                        .filter(future -> future.join().status() == CasStatus.APPLIED).count());
                int winner = 0;
                while (attempts.get(winner).join().status() != CasStatus.APPLIED) winner++;
                var reserved = attempts.get(winner).join().record().orElseThrow();
                var consumed = registry.consume(instance, reserved.version(), signature,
                        tokens.get(winner), "consume:winner", now.plusSeconds(1))
                        .get(15, TimeUnit.SECONDS);
                assertEquals(CasStatus.APPLIED, consumed.status());
                assertEquals(ItemRegistryState.CONSUMED, consumed.record().orElseThrow().state());
                assertEquals(CasStatus.REJECTED, registry.reserve(instance,
                        consumed.record().orElseThrow().version(), signature, UUID.randomUUID(),
                        owner.toString(), now.plusSeconds(60), "copied:item")
                        .get(15, TimeUnit.SECONDS).status());
            }

            try (DatabaseManager restarted = database(config)) {
                var report = restarted.start().toCompletableFuture().get(60, TimeUnit.SECONDS);
                assertEquals(schema, report.currentVersion());
                assertTrue(report.appliedVersions().isEmpty());
                var durable = new ItemRegistryRepository(restarted).find(instance)
                        .get(15, TimeUnit.SECONDS).orElseThrow();
                assertEquals(ItemRegistryState.CONSUMED, durable.state());
                assertEquals("consume:winner", durable.consumedByOperation());
                assertEquals(now, durable.createdAt());
            }
        }
    }

    private DatabaseConfig config(DatabaseType type, JdbcDatabaseContainer<?> container) {
        Path root = temporaryDirectory.resolve(type.name());
        var network = new DatabaseConfig.NetworkSettings(container.getHost(),
                container.getMappedPort(3306), container.getDatabaseName(),
                container.getUsername(), container.getPassword(),
                Map.of("allowPublicKeyRetrieval", "true", "useSSL", "false"));
        return new DatabaseConfig(type, root, root.resolve("data/unused.db"),
                root.resolve("backups"), false, Duration.ZERO, 2, Duration.ofSeconds(5),
                network, network,
                new DatabaseConfig.PoolSettings(0, 4, Duration.ofSeconds(10),
                        Duration.ofMinutes(30), Duration.ofSeconds(3), Duration.ofMinutes(10)),
                new DatabaseConfig.RetrySettings(3, Duration.ofMillis(25), Duration.ofMillis(200)),
                4, 128, Duration.ofSeconds(15));
    }

    private DatabaseManager database(DatabaseConfig config) {
        Logger logger = Logger.getLogger(getClass().getName() + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        return new DatabaseManager(config, logger);
    }

    private static ItemRegistration registration(UUID instance, UUID owner, String signature, Instant now) {
        return new ItemRegistration(instance, "SEED", "seed", null, owner, 0.0,
                "berry_fog", now, signature, 1, null);
    }
}
