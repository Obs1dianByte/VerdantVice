package com.verdantvice.genetics;

import com.verdantvice.database.DatabaseConfig;
import com.verdantvice.database.DatabaseManager;
import com.verdantvice.database.DatabaseType;
import com.verdantvice.database.item.ItemRegistration;
import com.verdantvice.database.item.ItemRegistryRepository;
import com.verdantvice.database.item.ItemRegistryState;
import com.verdantvice.security.CustomItemType;
import com.verdantvice.security.SecureItemService.ValidatedIdentity;
import com.verdantvice.security.SignedItemData;
import com.verdantvice.storage.CustomStrainRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the production repositories against real migrated SQLite, not mocks. */
final class GeneticsTransactionIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");
    private static final UUID OWNER = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID FIRST = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final String HASH = "a".repeat(64);

    @TempDir Path directory;
    private DatabaseManager database;
    private ItemRegistryRepository items;
    private CustomStrainRepository genetics;
    private ValidatedIdentity first;
    private ValidatedIdentity second;

    @BeforeEach
    void start() throws Exception {
        open();
        first = seed(FIRST, OWNER, "amber");
        second = seed(SECOND, OWNER, "azure");
        await(items.register(registration(first.data(), HASH)));
        await(items.register(registration(second.data(), HASH)));
    }

    @AfterEach
    void close() {
        if (database != null) database.close();
    }

    @Test
    void disabledAntiDupeAcceptsPhysicalCopiesWhileKeepingChildReceipts() throws Exception {
        var permissive = new CustomStrainRepository(database, new com.verdantvice.security.ItemUsePolicy(false));
        CustomStrainRecord firstLine = line("custom_permissive_first");
        CustomStrainRecord secondLine = line("custom_permissive_second");
        ItemRegistration firstChild = child(firstLine.strainId());
        ItemRegistration secondChild = child(secondLine.strainId());
        await(permissive.cross(firstLine, first, second, firstChild));
        await(permissive.cross(secondLine, first, second, secondChild));
        assertEquals(2, await(permissive.pendingSeeds(OWNER)).size());
        assertEquals("genetics:" + secondLine.strainId(),
                await(items.find(FIRST)).orElseThrow().consumedByOperation());
        assertTrue(await(permissive.find(firstLine.strainId())).isPresent());
        assertTrue(await(permissive.find(secondLine.strainId())).isPresent());
    }

    @Test
    void commitsParentsLineChildAndReceiptTogetherAndReplaysAfterRestart() throws Exception {
        CustomStrainRecord line = line("custom_success");
        ItemRegistration output = child(line.strainId());
        await(genetics.cross(line, first, second, output));

        assertConsumed(FIRST);
        assertConsumed(SECOND);
        assertEquals(ItemRegistryState.ACTIVE, await(items.find(output.instanceId())).orElseThrow().state());
        assertEquals(line, await(genetics.find(line.strainId())).orElseThrow());
        List<PendingGeneticsSeed> receipts = await(genetics.pendingSeeds(OWNER));
        assertEquals(1, receipts.size());
        assertEquals(output.instanceId(), receipts.get(0).data().uniqueInstanceId());
        assertEquals(FIRST, receipts.get(0).firstParentId());
        assertEquals(SECOND, receipts.get(0).secondParentId());
        assertEquals(NOW, receipts.get(0).data().createdAt());

        database.close();
        open();
        assertEquals(receipts, await(genetics.pendingSeeds(OWNER)),
                "A restart must recover the original child identity, not mint a new seed");
        await(genetics.acknowledgeSeed(OWNER, output.instanceId()));
        assertTrue(await(genetics.pendingSeeds(OWNER)).isEmpty());
        await(genetics.acknowledgeSeed(OWNER, output.instanceId()));
        assertEquals(1L, await(items.find(output.instanceId())).orElseThrow().version(),
                "Repeated acknowledgement must be idempotent");
    }

    @Test
    void secondParentFailureRollsBackTheFirstParentsReservationAndConsumption() throws Exception {
        ValidatedIdentity modifiedSecond = new ValidatedIdentity(second.data(), "b".repeat(64));
        CustomStrainRecord line = line("custom_invalid_signature");
        ItemRegistration output = child(line.strainId());
        assertThrows(CompletionException.class,
                () -> genetics.cross(line, first, modifiedSecond, output).join());
        assertActiveUnchanged(FIRST);
        assertActiveUnchanged(SECOND);
        assertNoOutput(line, output);
    }

    @Test
    void duplicateLineInsertRollsBackBothParentsAndDoesNotMintAChild() throws Exception {
        CustomStrainRecord line = line("custom_existing");
        await(genetics.create(line));
        ItemRegistration output = child(line.strainId());
        assertThrows(CompletionException.class,
                () -> genetics.cross(line, first, second, output).join());
        assertActiveUnchanged(FIRST);
        assertActiveUnchanged(SECOND);
        assertEquals(line, await(genetics.find(line.strainId())).orElseThrow());
        assertTrue(await(items.find(output.instanceId())).isEmpty());
        assertTrue(await(genetics.pendingSeeds(OWNER)).isEmpty());
    }

    @Test
    void childIdentityCollisionRollsBackLineAndBothParents() throws Exception {
        CustomStrainRecord line = line("custom_child_collision");
        ItemRegistration output = child(line.strainId());
        ItemRegistration unrelated = new ItemRegistration(output.instanceId(), "SEED", "seed", null,
                UUID.randomUUID(), 0.0, "unrelated", NOW, "c".repeat(64), 1, null);
        await(items.register(unrelated));
        assertThrows(CompletionException.class,
                () -> genetics.cross(line, first, second, output).join());
        assertActiveUnchanged(FIRST);
        assertActiveUnchanged(SECOND);
        assertTrue(await(genetics.find(line.strainId())).isEmpty());
        assertEquals(unrelated.ownerUuid(), await(items.find(output.instanceId())).orElseThrow().ownerUuid());
        assertTrue(await(genetics.pendingSeeds(OWNER)).isEmpty());
    }

    @Test
    void databaseFailureWritingOutputRollsBackAllEarlierWrites() throws Exception {
        CustomStrainRecord line = line("custom_write_failure");
        ItemRegistration output = child(line.strainId());
        await(database.update("""
                CREATE TRIGGER reject_genetics_seed BEFORE INSERT ON item_registry
                WHEN NEW.strain_id = 'custom_write_failure'
                BEGIN SELECT RAISE(ABORT, 'injected output write failure'); END
                """));
        assertThrows(CompletionException.class,
                () -> genetics.cross(line, first, second, output).join());
        assertActiveUnchanged(FIRST);
        assertActiveUnchanged(SECOND);
        assertNoOutput(line, output);
    }

    @Test
    void independentParentPairsDoNotBlockOrConsumeOneAnothersSeeds() throws Exception {
        ValidatedIdentity third = seed(UUID.randomUUID(), OWNER, "amber");
        ValidatedIdentity fourth = seed(UUID.randomUUID(), OWNER, "azure");
        await(items.register(registration(third.data(), HASH)));
        await(items.register(registration(fourth.data(), HASH)));
        CustomStrainRecord firstLine = line("custom_independent_first");
        CustomStrainRecord secondLine = line("custom_independent_second");
        await(CompletableFuture.allOf(
                genetics.cross(firstLine, first, second, child(firstLine.strainId())),
                genetics.cross(secondLine, third, fourth, child(secondLine.strainId()))));
        assertEquals(2, await(genetics.loadApproved()).size());
        assertEquals(2, await(genetics.pendingSeeds(OWNER)).size());
        assertConsumed(FIRST);
        assertConsumed(SECOND);
        assertConsumed(third.data().uniqueInstanceId());
        assertConsumed(fourth.data().uniqueInstanceId());
    }

    @Test
    void simultaneousCrossingsUsingTheSameParentsHaveExactlyOneWinner() throws Exception {
        List<CompletableFuture<Boolean>> attempts = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            CustomStrainRecord line = line("custom_concurrent_" + index);
            attempts.add(genetics.cross(line, index % 2 == 0 ? first : second,
                            index % 2 == 0 ? second : first, child(line.strainId()))
                    .handle((ignored, failure) -> failure == null));
        }
        await(CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new)));
        assertEquals(1, attempts.stream().filter(CompletableFuture::join).count());
        assertConsumed(FIRST);
        assertConsumed(SECOND);
        assertEquals(1, await(genetics.loadApproved()).size());
        assertEquals(1, await(genetics.pendingSeeds(OWNER)).size());
        int registryRows = await(database.queryOne("SELECT COUNT(*) AS total FROM item_registry",
                result -> result.getInt("total"))).orElseThrow();
        assertEquals(3, registryRows, "Only the two consumed parents and the single child may remain");
    }

    @Test
    void wrongOwnerCannotConsumeParentsOrAcknowledgeSomeoneElsesDelivery() throws Exception {
        CustomStrainRecord line = line("custom_wrong_owner");
        SignedItemData stolenData = new SignedItemData(CustomItemType.SEED, "seed", SECOND, null,
                UUID.randomUUID(), 0.0, "azure", NOW, 1);
        assertThrows(CompletionException.class,
                () -> genetics.cross(line, first, new ValidatedIdentity(stolenData, HASH),
                        child(line.strainId())).join());
        assertActiveUnchanged(FIRST);
        assertActiveUnchanged(SECOND);
        ItemRegistration output = child(line.strainId());
        await(genetics.cross(line, first, second, output));
        await(genetics.acknowledgeSeed(UUID.randomUUID(), output.instanceId()));
        assertEquals(1, await(genetics.pendingSeeds(OWNER)).size());
        assertTrue(await(genetics.pendingSeeds(UUID.randomUUID())).isEmpty());
    }

    @Test
    void invalidOrRepeatedParentIsRejectedBeforeAnyWrite() throws Exception {
        CustomStrainRecord line = line("custom_repeated");
        ItemRegistration output = child(line.strainId());
        assertThrows(CompletionException.class, () -> genetics.cross(line, first, first, output).join());
        CustomStrainRecord wrongLineage = new CustomStrainRecord(line.strainId(),
                new GeneticLine(line.line().id(), "Wrong", Set.of("amber", "other"), OWNER, 1,
                        Map.of(), Set.of(), Set.of(), 0.5, NOW), NOW, 0);
        assertThrows(CompletionException.class,
                () -> genetics.cross(wrongLineage, first, second, output).join());
        assertActiveUnchanged(FIRST);
        assertActiveUnchanged(SECOND);
        assertNoOutput(line, output);
    }

    private void open() throws Exception {
        var network = new DatabaseConfig.NetworkSettings("localhost", 3306, "unused", "unused", "", Map.of());
        var config = new DatabaseConfig(DatabaseType.SQLITE, directory,
                directory.resolve("data/genetics.db"), directory.resolve("backups"), false,
                Duration.ZERO, 2, Duration.ofSeconds(5), network, network,
                new DatabaseConfig.PoolSettings(0, 4, Duration.ofSeconds(5), Duration.ofMinutes(30),
                        Duration.ofSeconds(2), Duration.ofMinutes(10)),
                new DatabaseConfig.RetrySettings(6, Duration.ofMillis(10), Duration.ofMillis(100)),
                4, 128, Duration.ofSeconds(10));
        Logger logger = Logger.getLogger(getClass().getName() + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        database = new DatabaseManager(config, logger);
        await(database.start().toCompletableFuture());
        items = new ItemRegistryRepository(database);
        genetics = new CustomStrainRepository(database);
    }

    private static ValidatedIdentity seed(UUID id, UUID owner, String strain) {
        return new ValidatedIdentity(new SignedItemData(CustomItemType.SEED, "seed", id, null,
                owner, 0.0, strain, NOW, 1), HASH);
    }

    private static ItemRegistration registration(SignedItemData data, String hash) {
        return new ItemRegistration(data.uniqueInstanceId(), data.itemType().name(), data.itemId(),
                data.batchId(), data.ownerUuid(), data.quality(), data.strain(), data.createdAt(),
                hash, data.schemaVersion(), null);
    }

    private static ItemRegistration child(String strain) {
        return new ItemRegistration(UUID.randomUUID(), "SEED", "seed", null, OWNER,
                0.0, strain, NOW, HASH, 1, null);
    }

    private static CustomStrainRecord line(String strain) {
        UUID id = UUID.nameUUIDFromBytes(("verdantvice:line:" + strain).getBytes(StandardCharsets.UTF_8));
        return new CustomStrainRecord(strain, new GeneticLine(id, "Test Line", Set.of("amber", "azure"),
                OWNER, 1, Map.of("quality", 1.25), Set.of("blue", "orange"), Set.of("CALM"),
                0.75, NOW), NOW, 0);
    }

    private void assertActiveUnchanged(UUID id) throws Exception {
        var row = await(items.find(id)).orElseThrow();
        assertEquals(ItemRegistryState.ACTIVE, row.state());
        assertEquals(0L, row.version());
        assertNull(row.reservationToken());
        assertNull(row.consumedAt());
        assertNull(row.consumedByOperation());
    }

    private void assertConsumed(UUID id) throws Exception {
        var row = await(items.find(id)).orElseThrow();
        assertEquals(ItemRegistryState.CONSUMED, row.state());
        assertEquals(2L, row.version());
        assertNotNull(row.consumedByOperation());
    }

    private void assertNoOutput(CustomStrainRecord line, ItemRegistration output) throws Exception {
        assertTrue(await(genetics.find(line.strainId())).isEmpty());
        assertTrue(await(items.find(output.instanceId())).isEmpty());
        assertTrue(await(genetics.pendingSeeds(OWNER)).isEmpty());
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(20, TimeUnit.SECONDS);
    }
}
