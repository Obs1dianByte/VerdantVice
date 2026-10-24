package com.verdantvice.worker;

import com.verdantvice.common.BlockPosition;
import com.verdantvice.database.DatabaseManager;
import com.verdantvice.herbalist.HerbalistRecord;
import com.verdantvice.herbalist.HerbalistTier;
import com.verdantvice.database.SqlSession;
import com.verdantvice.database.item.CasStatus;
import com.verdantvice.database.item.ItemRegistration;
import com.verdantvice.database.item.ItemRegistryRepository;
import com.verdantvice.database.item.ItemRegistryState;
import com.verdantvice.security.CustomItemType;
import com.verdantvice.security.ItemConsumptionGateway;
import com.verdantvice.security.ProcessingItemValidator;
import com.verdantvice.security.SecureItemService;
import com.verdantvice.security.SignedItemData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.Set;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorkerRepositoryHireIntegrationTest {
    @TempDir
    Path root;

    private DatabaseManager database;
    private WorkerRepository workers;
    private UUID farmOwner;
    private UUID farmId;
    private UUID worldId;
    private Instant now;

    @BeforeEach
    void start() throws Exception {
        database = WorkerReliabilityTestSupport.start(root);
        workers = new WorkerRepository(database);
        farmOwner = UUID.randomUUID();
        farmId = UUID.randomUUID();
        worldId = UUID.randomUUID();
        now = Instant.parse("2026-07-29T12:00:00Z");
        database.transaction(session -> {
            session.update("INSERT INTO players (player_uuid, locale, created_at, updated_at, "
                            + "last_seen_at, row_version) VALUES (?, 'en_US', ?, ?, ?, 0)",
                    farmOwner, now, now, now);
            session.update("INSERT INTO farms (farm_id, owner_uuid, farm_name, world_uuid, "
                            + "table_x, table_y, table_z, created_at, updated_at, row_version) "
                            + "VALUES (?, ?, 'Hire Farm', ?, 0, 64, 0, ?, ?, 0)",
                    farmId, farmOwner, worldId, now, now);
            return null;
        }).get(15, TimeUnit.SECONDS);
    }

    @AfterEach
    void close() {
        if (database != null) database.close();
    }

    @Test
    void hirePersistsPlayerFarmWorkerAndHerbalistBeforeReturning() throws Exception {
        UUID hiringOwner = UUID.randomUUID();
        HerbalistRecord herbalist = insertHerbalist(null);

        WorkerRecord hired = workers.hireHerbalist(
                herbalist,
                hiringOwner,
                farmId,
                "Herbalist \u0141ukasz",
                WorkerSpecialization.HARVESTER,
                4,
                12,
                schedule(),
                new BlockPosition(worldId, 1, 64, 1),
                4,
                "pl_PL",
                now.plusSeconds(1))
                .get(15, TimeUnit.SECONDS);

        assertEquals(hiringOwner, hired.worker().ownerUuid());
        assertEquals(farmId, hired.worker().farmId());
        assertTrue(exists("SELECT 1 FROM players WHERE player_uuid=?", hiringOwner));
        assertTrue(exists("SELECT 1 FROM farms WHERE farm_id=?", farmId));
        assertTrue(exists("SELECT 1 FROM workers WHERE worker_uuid=?",
                hired.worker().workerUuid()));
        assertEquals(hiringOwner, database.queryOne(
                        "SELECT owner_uuid FROM herbalists WHERE herbalist_uuid=?",
                        result -> UUID.fromString(result.getString("owner_uuid")),
                        herbalist.herbalistUuid())
                .get(15, TimeUnit.SECONDS).orElseThrow());

        WorkerPaymentRepository.PaymentRecord payment =
                new WorkerPaymentRepository(database).createPending(
                        hired.worker().workerUuid(), hiringOwner, farmId,
                        hired.worker().salaryPerMinecraftDay(), 12,
                        now.plusSeconds(2))
                        .get(15, TimeUnit.SECONDS);
        assertEquals(WorkerPaymentRepository.PaymentStatus.PENDING,
                payment.status());
    }

    @Test
    void failedHireRollsBackEnsuredPlayerAndHerbalistClaim() throws Exception {
        UUID hiringOwner = UUID.randomUUID();
        HerbalistRecord herbalist = insertHerbalist(null);
        UUID missingFarm = UUID.randomUUID();

        assertThrows(CompletionException.class, () -> workers.hireHerbalist(
                herbalist,
                hiringOwner,
                missingFarm,
                "Worker",
                WorkerSpecialization.HARVESTER,
                4,
                12,
                schedule(),
                new BlockPosition(worldId, 1, 64, 1),
                4,
                "en_US",
                now.plusSeconds(1)).join());

        assertFalse(exists("SELECT 1 FROM players WHERE player_uuid=?", hiringOwner));
        assertFalse(exists("SELECT 1 FROM workers WHERE entity_uuid=?",
                herbalist.entityUuid()));
        assertTrue(exists(
                "SELECT 1 FROM herbalists WHERE herbalist_uuid=? AND owner_uuid IS NULL",
                herbalist.herbalistUuid()));
    }

    @Test
    void sameHerbalistCannotCreateTwoWorkers() throws Exception {
        UUID hiringOwner = UUID.randomUUID();
        HerbalistRecord herbalist = insertHerbalist(null);
        workers.hireHerbalist(
                herbalist, hiringOwner, farmId, "Worker",
                WorkerSpecialization.HARVESTER, 4, 12, schedule(),
                new BlockPosition(worldId, 1, 64, 1), 4, "en_US",
                now.plusSeconds(1)).get(15, TimeUnit.SECONDS);

        assertThrows(CompletionException.class, () -> workers.hireHerbalist(
                herbalist, hiringOwner, farmId, "Worker 2",
                WorkerSpecialization.HARVESTER, 4, 12, schedule(),
                new BlockPosition(worldId, 2, 64, 2), 4, "en_US",
                now.plusSeconds(2)).join());
        assertEquals(1, count("workers"));
    }

    @Test
    void successfulHireConsumesContractInTheWorkerTransaction() throws Exception {
        HerbalistRecord herbalist = insertHerbalist(null);
        SecureItemService.ValidatedIdentity contract = contract();
        WorkerRecord hired = hireWithContract(herbalist, contract, false, 4).join();
        assertEquals(farmOwner, hired.worker().ownerUuid());
        assertEquals(ItemRegistryState.CONSUMED, new ItemRegistryRepository(database)
                .find(contract.data().uniqueInstanceId()).join().orElseThrow().state());
        assertEquals(1, count("workers"));
        assertFalse(exists("SELECT 1 FROM herbalists WHERE herbalist_uuid=? AND owner_uuid IS NULL",
                herbalist.herbalistUuid()));
    }

    @Test
    void failureAfterContractConsumptionRollsBackContractClaimAndWorkerTogether() throws Exception {
        HerbalistRecord herbalist = insertHerbalist(null);
        SecureItemService.ValidatedIdentity contract = contract();
        assertThrows(CompletionException.class, () -> hireWithContract(herbalist, contract, true, 4).join());
        assertEquals(ItemRegistryState.ACTIVE, new ItemRegistryRepository(database)
                .find(contract.data().uniqueInstanceId()).join().orElseThrow().state());
        assertEquals(0, count("workers"));
        assertTrue(exists("SELECT 1 FROM herbalists WHERE herbalist_uuid=? AND owner_uuid IS NULL",
                herbalist.herbalistUuid()));
    }

    @Test
    void farmCapacityFailureDoesNotConsumeAContract() throws Exception {
        HerbalistRecord first = insertHerbalist(null);
        hireWithContract(first, contract(), false, 1).join();
        HerbalistRecord second = insertHerbalist(null);
        SecureItemService.ValidatedIdentity unused = contract();
        assertThrows(CompletionException.class, () -> hireWithContract(second, unused, false, 1).join());
        assertEquals(ItemRegistryState.ACTIVE, new ItemRegistryRepository(database)
                .find(unused.data().uniqueInstanceId()).join().orElseThrow().state());
        assertEquals(1, count("workers"));
        assertTrue(exists("SELECT 1 FROM herbalists WHERE herbalist_uuid=? AND owner_uuid IS NULL",
                second.herbalistUuid()));
    }

    @Test
    void copiedContractCannotHireASecondVillagerAndFailedClaimIsRolledBack() throws Exception {
        SecureItemService.ValidatedIdentity contract = contract();
        hireWithContract(insertHerbalist(null), contract, false, 4).join();
        HerbalistRecord second = insertHerbalist(null);
        assertThrows(CompletionException.class, () -> hireWithContract(second, contract, false, 4).join());
        assertEquals(1, count("workers"));
        assertTrue(exists("SELECT 1 FROM herbalists WHERE herbalist_uuid=? AND owner_uuid IS NULL",
                second.herbalistUuid()));
    }

    private CompletableFuture<WorkerRecord> hireWithContract(HerbalistRecord herbalist,
            SecureItemService.ValidatedIdentity contract, boolean failAfterConsumption, int limit) {
        return workers.hireHerbalist(herbalist, farmOwner, farmId, "Worker",
                WorkerSpecialization.HARVESTER, 4, 12, schedule(),
                new BlockPosition(worldId, 1, 64, 1), limit, "en_US", now.plusSeconds(1),
                contract, new ContractGateway(new ItemRegistryRepository(database), failAfterConsumption),
                "hire-test:" + UUID.randomUUID());
    }

    private SecureItemService.ValidatedIdentity contract() {
        SignedItemData data = new SignedItemData(CustomItemType.PLANTATION_CONTRACT, "plantation-contract",
                UUID.randomUUID(), null, farmOwner, 0.0, "", now, 1);
        String payloadHash = "a".repeat(64);
        new ItemRegistryRepository(database).register(new ItemRegistration(data.uniqueInstanceId(),
                data.itemType().name(), data.itemId(), null, farmOwner, 0.0, null,
                now, payloadHash, 1, null)).join();
        return new SecureItemService.ValidatedIdentity(data, payloadHash);
    }

    private static final class ContractGateway implements ItemConsumptionGateway {
        private final ItemRegistryRepository registry;
        private final boolean failAfterConsumption;
        private ContractGateway(ItemRegistryRepository registry, boolean failAfterConsumption) {
            this.registry = registry;
            this.failAfterConsumption = failAfterConsumption;
        }

        @Override
        public CompletableFuture<SecureItemService.ValidatedItem> validateActive(org.bukkit.inventory.ItemStack item,
                Set<CustomItemType> accepted, UUID actor) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("Tests provide domain identities"));
        }

        @Override
        public SecureItemService.ConsumedItem consume(SqlSession session, SecureItemService.ValidatedIdentity contract,
                Set<CustomItemType> accepted, UUID actor, String operationId, Instant now) throws SQLException {
            ProcessingItemValidator validator = new ProcessingItemValidator();
            validator.validateData(contract.data(), accepted, actor);
            var record = registry.find(session, contract.data().uniqueInstanceId(), true).orElseThrow();
            validator.validateRecord(record, contract.data(), contract.payloadHash(), actor);
            UUID token = UUID.randomUUID();
            var reserved = registry.reserve(session, record.instanceId(), record.version(), contract.payloadHash(),
                    token, actor.toString(), now.plusSeconds(60), operationId, now);
            if (reserved.status() == CasStatus.REJECTED) throw new SQLException("Contract reservation failed");
            var consumed = registry.consume(session, record.instanceId(), reserved.current().version(),
                    contract.payloadHash(), token, operationId, now);
            if (consumed.status() == CasStatus.REJECTED) throw new SQLException("Contract consumption failed");
            if (failAfterConsumption) throw new SQLException("Injected failure after contract consumption");
            return new SecureItemService.ConsumedItem(contract.data(), operationId);
        }
    }

    private HerbalistRecord insertHerbalist(UUID owner) throws Exception {
        UUID herbalistId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        database.transaction(session -> {
            session.update("""
                    INSERT INTO herbalists (
                      herbalist_uuid, entity_uuid, owner_uuid, world_uuid,
                      block_x, block_y, block_z, herbalist_level, experience,
                      reputation, offers_json, created_at, updated_at, row_version
                    ) VALUES (?, ?, ?, ?, 1, 64, 1, 6, 220, 10, NULL, ?, ?, 0)
                    """,
                    herbalistId, entityId, owner, worldId, now, now);
            return null;
        }).get(15, TimeUnit.SECONDS);
        return new HerbalistRecord(
                herbalistId, entityId, owner, worldId,
                1, 64, 1, HerbalistTier.VERDANT_EXPERT,
                220, 10, null, now, now, 0L);
    }

    private boolean exists(String sql, Object argument) throws Exception {
        return database.read(session -> session.exists(sql, argument))
                .get(15, TimeUnit.SECONDS);
    }

    private int count(String table) throws Exception {
        return database.queryOne("SELECT COUNT(*) AS total FROM " + table,
                        result -> result.getInt("total"))
                .get(15, TimeUnit.SECONDS).orElse(0);
    }

    private static WorkerSchedule schedule() {
        return new WorkerSchedule(0, 1_000, 6_000, 7_000, 12_000, 16_000);
    }
}
