package com.verdantvice.processing;

import com.verdantvice.database.DatabaseConfig;
import com.verdantvice.database.DatabaseManager;
import com.verdantvice.database.DatabaseType;
import com.verdantvice.database.SqlSession;
import com.verdantvice.security.CustomItemType;
import com.verdantvice.security.ItemConsumptionGateway;
import com.verdantvice.security.SecureItemService;
import com.verdantvice.security.SignedItemData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RollingConsumptionServiceTest {
    @TempDir
    Path temporaryDirectory;

    private DatabaseManager database;

    @BeforeEach
    void startDatabase() throws Exception {
        database = new DatabaseManager(sqliteConfig(temporaryDirectory), quietLogger());
        database.start().toCompletableFuture().get(15, TimeUnit.SECONDS);
        database.update("CREATE TABLE rolling_consumption_test (slot INTEGER NOT NULL)")
                .get(15, TimeUnit.SECONDS);
    }

    @AfterEach
    void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void threeInputsAreConsumedExactlyOnceInOneCommittedTransaction() throws Exception {
        RecordingGateway gateway = new RecordingGateway(0);
        RollingConsumptionService service = new RollingConsumptionService(database, gateway);
        UUID actor = UUID.randomUUID();

        service.consumeIdentities(
                validated(CustomItemType.ROLLING_PAPER, actor),
                validated(CustomItemType.FILTER, actor),
                validated(CustomItemType.GROUND_PRODUCT, actor),
                actor,
                UUID.randomUUID(),
                Instant.now()
        ).get(15, TimeUnit.SECONDS);

        assertEquals(3, gateway.calls.get());
        assertEquals(3, countRows());
    }

    @Test
    void failureRollsBackEveryEarlierConsumption() throws Exception {
        RecordingGateway gateway = new RecordingGateway(2);
        RollingConsumptionService service = new RollingConsumptionService(database, gateway);
        UUID actor = UUID.randomUUID();

        assertThrows(ExecutionException.class, () -> service.consumeIdentities(
                validated(CustomItemType.ROLLING_PAPER, actor),
                validated(CustomItemType.FILTER, actor),
                validated(CustomItemType.MIXTURE, actor),
                actor,
                UUID.randomUUID(),
                Instant.now()
        ).get(15, TimeUnit.SECONDS));

        assertEquals(2, gateway.calls.get());
        assertEquals(0, countRows());
    }

    private int countRows() throws Exception {
        return database.read(session -> session.queryOne(
                "SELECT COUNT(*) FROM rolling_consumption_test",
                result -> result.getInt(1)
        ).orElseThrow()).get(15, TimeUnit.SECONDS);
    }

    private SecureItemService.ValidatedIdentity validated(CustomItemType type, UUID actor) {
        UUID batchId = switch (type) {
            case GROUND_PRODUCT, MIXTURE -> UUID.randomUUID();
            default -> null;
        };
        SignedItemData data = new SignedItemData(
                type,
                type.name().toLowerCase(java.util.Locale.ROOT),
                UUID.randomUUID(),
                batchId,
                actor,
                0.0,
                "",
                Instant.now(),
                1
        );
        return new SecureItemService.ValidatedIdentity(
                data,
                "a".repeat(64)
        );
    }

    private static final class RecordingGateway implements ItemConsumptionGateway {
        private final int failAt;
        private final AtomicInteger calls = new AtomicInteger();

        private RecordingGateway(int failAt) {
            this.failAt = failAt;
        }

        @Override
        public CompletableFuture<SecureItemService.ValidatedItem> validateActive(
                org.bukkit.inventory.ItemStack item,
                Set<CustomItemType> accepted,
                UUID actor
        ) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public SecureItemService.ConsumedItem consume(
                SqlSession session,
                SecureItemService.ValidatedIdentity validated,
                Set<CustomItemType> accepted,
                UUID actor,
                String operationId,
                Instant now
        ) throws SQLException {
            int call = calls.incrementAndGet();
            if (!accepted.contains(validated.data().itemType())) {
                throw new SQLException("wrong accepted type");
            }
            session.update("INSERT INTO rolling_consumption_test(slot) VALUES (?)", call);
            if (call == failAt) {
                throw new SQLException("forced rolling failure");
            }
            return new SecureItemService.ConsumedItem(validated.data(), operationId);
        }
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
                        0, 2, Duration.ofSeconds(5), Duration.ofMinutes(30),
                        Duration.ofSeconds(2), Duration.ofMinutes(10)
                ),
                new DatabaseConfig.RetrySettings(
                        3, Duration.ofMillis(10), Duration.ofMillis(100)
                ),
                1,
                128,
                Duration.ofSeconds(10)
        );
    }

    private static Logger quietLogger() {
        Logger logger = Logger.getLogger(
                RollingConsumptionServiceTest.class.getName() + "." + UUID.randomUUID()
        );
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        return logger;
    }
}
