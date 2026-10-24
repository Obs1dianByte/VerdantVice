package com.verdantvice.security;

import com.verdantvice.database.DatabaseConfig;
import com.verdantvice.database.DatabaseManager;
import com.verdantvice.database.DatabaseType;
import com.verdantvice.database.item.CasStatus;
import com.verdantvice.database.item.ItemRegistration;
import com.verdantvice.database.item.ItemRegistryRepository;
import com.verdantvice.database.item.ItemRegistryState;
import com.verdantvice.processing.ProcessingFailureException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Real SQLite bookkeeping remains transactional with duplicate policing disabled. */
final class ItemUsePolicyIntegrationTest {
    private static final String HASH = "a".repeat(64);
    private static final UUID OWNER = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");
    @TempDir Path directory;
    private DatabaseManager database;
    private ItemRegistryRepository registry;
    private SignedItemData data;

    @BeforeEach
    void open() throws Exception {
        var network = new DatabaseConfig.NetworkSettings("localhost", 3306, "unused", "unused", "", Map.of());
        var config = new DatabaseConfig(DatabaseType.SQLITE, directory,
                directory.resolve("data/items.db"), directory.resolve("backups"), false,
                Duration.ZERO, 2, Duration.ofSeconds(5), network, network,
                new DatabaseConfig.PoolSettings(0, 4, Duration.ofSeconds(5), Duration.ofMinutes(30),
                        Duration.ofSeconds(2), Duration.ofMinutes(10)),
                new DatabaseConfig.RetrySettings(3, Duration.ofMillis(10), Duration.ofMillis(100)),
                1, 128, Duration.ofSeconds(10));
        Logger logger = Logger.getLogger(getClass().getName() + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        database = new DatabaseManager(config, logger);
        await(database.start().toCompletableFuture());
        registry = new ItemRegistryRepository(database);
        data = new SignedItemData(CustomItemType.SEED, "seed", UUID.randomUUID(), null,
                OWNER, 0, "berry_fog", NOW, 1);
    }

    @AfterEach
    void close() {
        if (database != null) database.close();
    }

    @Test
    void bundledAndExistingConfigurationsDisablePolicingByDefaultAndSupportReload() throws Exception {
        try (var reader = new InputStreamReader(getClass().getResourceAsStream("/config.yml"),
                StandardCharsets.UTF_8)) {
            var bundled = YamlConfiguration.loadConfiguration(reader);
            assertTrue(bundled.contains("security.anti-dupe-enabled"));
            assertFalse(bundled.getBoolean("security.anti-dupe-enabled"));
        }
        var existing = new YamlConfiguration();
        var policy = new ItemUsePolicy(() -> existing.getBoolean("security.anti-dupe-enabled", false));
        assertFalse(policy.antiDupeEnabled());
        existing.set("security.anti-dupe-enabled", true);
        assertTrue(policy.antiDupeEnabled());
        existing.set("security.anti-dupe-enabled", false);
        assertFalse(policy.antiDupeEnabled());
    }

    @Test
    void missingRowDoesNotBypassOwnershipWhenPolicingIsDisabled() {
        assertThrows(ProcessingFailureException.class, () -> new ItemUsePolicy(false)
                .validateRecord(null, data, HASH, UUID.randomUUID()));
    }

    @Test
    void disabledModeRegistersAnAuthenticPhysicalInputMissingFromRegistry() throws Exception {
        consume(new ItemUsePolicy(false), "first");
        var row = await(registry.find(data.uniqueInstanceId())).orElseThrow();
        assertEquals(ItemRegistryState.CONSUMED, row.state());
        assertEquals("first", row.consumedByOperation());
    }

    @Test
    void disabledModeAllowsAnotherPhysicalCopyButStillRecordsConsumption() throws Exception {
        consume(new ItemUsePolicy(false), "first");
        var consumed = await(registry.find(data.uniqueInstanceId())).orElseThrow();
        var policy = new ItemUsePolicy(false);
        assertDoesNotThrow(() -> policy.validateRecord(consumed, data, HASH, OWNER));
        consume(policy, "second");
        var row = await(registry.find(data.uniqueInstanceId())).orElseThrow();
        assertEquals(ItemRegistryState.CONSUMED, row.state());
        assertEquals("second", row.consumedByOperation());
        assertEquals(5, row.version());
        assertNull(row.reservationToken());
        assertTrue(recorded(policy, "first"), "Earlier transaction evidence survives physical copy reuse");
        assertTrue(recorded(policy, "second"));
        assertFalse(recorded(policy, "unrelated"));
        var batches = new com.verdantvice.storage.BatchService(
                new com.verdantvice.storage.BatchRepository(database),
                new com.verdantvice.processing.BatchAuthenticityService(), new byte[32]);
        var contracts = new com.verdantvice.contract.PaperContractRepository(database, registry, batches, policy);
        assertTrue(await(contracts.deliveryApplied(data.uniqueInstanceId(), "first")),
                "Contract recovery must recognize an older, archived operation");
        assertTrue(await(contracts.deliveryApplied(data.uniqueInstanceId(), "second")));
    }

    @Test
    void enabledModeRejectsConsumedAndUnregisteredInputs() throws Exception {
        var strict = new ItemUsePolicy(true);
        assertThrows(ProcessingFailureException.class, () -> strict.validateRecord(null, data, HASH, OWNER));
        assertThrows(CompletionException.class, () -> consume(strict, "missing"));
        consume(new ItemUsePolicy(false), "first");
        var consumed = await(registry.find(data.uniqueInstanceId())).orElseThrow();
        assertThrows(ProcessingFailureException.class,
                () -> strict.validateRecord(consumed, data, HASH, OWNER));
        assertThrows(CompletionException.class, () -> consume(strict, "second"));
        assertEquals("first", await(registry.find(data.uniqueInstanceId())).orElseThrow().consumedByOperation());
    }

    @Test
    void disabledModePreservesAuthenticityAndOwnership() throws Exception {
        consume(new ItemUsePolicy(false), "first");
        var consumed = await(registry.find(data.uniqueInstanceId())).orElseThrow();
        var permissive = new ItemUsePolicy(false);
        assertThrows(ProcessingFailureException.class,
                () -> permissive.validateRecord(consumed, data, "b".repeat(64), OWNER));
        assertThrows(ProcessingFailureException.class,
                () -> permissive.validateRecord(consumed, data, HASH, UUID.randomUUID()));
        assertThrows(CompletionException.class, () -> await(database.transaction(session ->
                permissive.prepareConsumption(session, registry, data, "b".repeat(64), OWNER, NOW))));
        assertEquals("first", await(registry.find(data.uniqueInstanceId())).orElseThrow().consumedByOperation());
    }

    @Test
    void reactivationRollsBackIfTheGameplayTransactionFails() throws Exception {
        consume(new ItemUsePolicy(false), "first");
        var before = await(registry.find(data.uniqueInstanceId())).orElseThrow();
        assertThrows(CompletionException.class, () -> database.transaction(session -> {
            new ItemUsePolicy(false).prepareConsumption(session, registry, data, HASH, OWNER, NOW);
            throw new IllegalStateException("injected gameplay failure");
        }).join());
        assertEquals(before, await(registry.find(data.uniqueInstanceId())).orElseThrow());
        assertEquals(0L, await(database.queryOne(
                "SELECT COUNT(*) AS total FROM operation_journal WHERE operation_type='ITEM_USE_RECEIPT'",
                row -> row.getLong("total"))).orElseThrow());
    }

    @Test
    void liveRollingReceiptCannotBeOverwrittenInDisabledMode() throws Exception {
        consume(new ItemUsePolicy(false), "roll:pending");
        await(database.update("UPDATE item_registry SET metadata_json=? WHERE instance_id=?",
                "{\"rollingSession\":true}", data.uniqueInstanceId()));
        var before = await(registry.find(data.uniqueInstanceId())).orElseThrow();
        assertThrows(CompletionException.class, () -> consume(new ItemUsePolicy(false), "second"));
        assertEquals(before, await(registry.find(data.uniqueInstanceId())).orElseThrow());
    }

    @Test
    void anotherLiveReservationIsNotInterruptedInDisabledMode() throws Exception {
        await(registry.register(new ItemRegistration(data.uniqueInstanceId(), "SEED", "seed", null,
                OWNER, 0.0, "berry_fog", NOW, HASH, 1, null)));
        Instant now = Instant.now();
        await(registry.reserve(data.uniqueInstanceId(), 0, HASH, UUID.randomUUID(), OWNER.toString(),
                now.plusSeconds(60), "in-flight"));
        assertThrows(CompletionException.class, () -> consume(new ItemUsePolicy(false), "second"));
        assertEquals(ItemRegistryState.RESERVED, await(registry.find(data.uniqueInstanceId())).orElseThrow().state());
    }

    private void consume(ItemUsePolicy policy, String operation) throws Exception {
        await(database.transaction(session -> {
            Instant now = Instant.now();
            var record = policy.prepareConsumption(session, registry, data, HASH, OWNER, now);
            UUID token = UUID.randomUUID();
            var reserved = registry.reserve(session, record.instanceId(), record.version(), HASH,
                    token, OWNER.toString(), now.plusSeconds(30), operation, now);
            assertEquals(CasStatus.APPLIED, reserved.status());
            var consumed = registry.consume(session, record.instanceId(), reserved.current().version(),
                    HASH, token, operation, now);
            assertEquals(CasStatus.APPLIED, consumed.status());
            return null;
        }));
    }

    private boolean recorded(ItemUsePolicy policy, String operation) throws Exception {
        return await(database.read(session -> policy.consumptionRecorded(
                session, registry, data.uniqueInstanceId(), operation)));
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.join();
    }
}
