package com.verdantvice.contract;

import com.verdantvice.database.DatabaseConfig;
import com.verdantvice.database.DatabaseManager;
import com.verdantvice.database.item.ItemRegistryRepository;
import com.verdantvice.herbalist.HerbalistRepository;
import com.verdantvice.processing.BatchAuthenticityService;
import com.verdantvice.processing.QualityGrade;
import com.verdantvice.storage.BatchRepository;
import com.verdantvice.storage.BatchService;
import com.verdantvice.storage.PlayerDataRepository;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ContractExpiryIntegrationTest {
    @TempDir Path directory;
    private DatabaseManager database;
    private PaperContractRepository contracts;
    private final UUID owner = UUID.randomUUID();
    private final UUID otherOwner = UUID.randomUUID();
    private UUID herbalist;
    private final Instant createdAt = Instant.now().minusSeconds(172_800);
    private final Instant pastDeadline = Instant.now().minusSeconds(86_400);

    @BeforeEach
    void start() {
        Logger logger = Logger.getLogger(getClass().getName() + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        database = new DatabaseManager(DatabaseConfig.fromBukkit(new YamlConfiguration(), directory), logger);
        database.start().toCompletableFuture().join();
        PlayerDataRepository players = new PlayerDataRepository(database);
        players.markSeen(owner, "en_US").join();
        players.markSeen(otherOwner, "en_US").join();
        herbalist = new HerbalistRepository(database).createIfBelowLimit(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                0, 64, 0, 32, 1, 0, createdAt).join()
                .optionalRecord().orElseThrow().herbalistUuid();
        var batches = new BatchService(new BatchRepository(database),
                new BatchAuthenticityService(), new byte[32]);
        contracts = new PaperContractRepository(database, new ItemRegistryRepository(database), batches);
    }

    @AfterEach
    void close() {
        if (database != null) database.close();
    }

    @Test
    void eightNeglectedContractsExpireBeforeTheLimitIsChecked() {
        for (int index = 0; index < 8; index++) contracts.create(contract(owner), 8).join();
        makeOverdue(owner);

        Contract replacement = contract(owner);
        assertEquals(replacement.contractId(), contracts.create(replacement, 8).join().contract().contractId());
        List<StoredContract> history = contracts.list(owner).join();
        assertEquals(9, history.size());
        assertEquals(8, history.stream().filter(row -> row.contract().status() == ContractStatus.EXPIRED).count());
        assertEquals(1, history.stream().filter(row -> row.contract().status() == ContractStatus.ACTIVE).count());
    }

    @Test
    void aCurrentActiveContractStillUsesItsSlot() {
        contracts.create(contract(owner), 1).join();
        CompletionException failure = assertThrows(CompletionException.class,
                () -> contracts.create(contract(owner), 1).join());
        assertEquals("contract-limit", failure.getCause().getMessage());
        assertEquals(ContractStatus.ACTIVE, contracts.list(owner).join().getFirst().contract().status());
        assertTrue(contracts.list(UUID.randomUUID()).join().isEmpty());
    }

    @Test
    void completedUnclaimedRewardsDoNotExpireOrFreeTheirSlot() {
        Contract completed = contract(owner);
        contracts.create(completed, 1).join();
        database.update("UPDATE contracts SET contract_status='COMPLETED', completed_at=?, deadline=? "
                        + "WHERE contract_id=?", pastDeadline.minusSeconds(1), pastDeadline,
                completed.contractId()).join();

        StoredContract listed = contracts.list(owner).join().getFirst();
        assertEquals(ContractStatus.COMPLETED, listed.contract().status());
        assertTrue(!listed.rewardClaimed());
        assertThrows(CompletionException.class, () -> contracts.create(contract(owner), 1).join());
        assertEquals(ContractStatus.COMPLETED,
                contracts.find(completed.contractId()).join().orElseThrow().contract().status());
    }

    @Test
    void listingExpiresOnlyTheRequestedOwnersActiveContracts() {
        Contract mine = contract(owner);
        Contract theirs = contract(otherOwner);
        contracts.create(mine, 1).join();
        contracts.create(theirs, 1).join();
        makeOverdue(owner);
        makeOverdue(otherOwner);

        assertEquals(ContractStatus.EXPIRED, contracts.list(owner).join().getFirst().contract().status());
        assertEquals(ContractStatus.ACTIVE,
                contracts.find(theirs.contractId()).join().orElseThrow().contract().status());
        assertEquals(ContractStatus.EXPIRED, contracts.list(otherOwner).join().getFirst().contract().status());
    }

    private Contract contract(UUID player) {
        return Contract.active(UUID.randomUUID(), player, herbalist, ContractType.STRAIN_DELIVERY,
                List.of(new ContractRequirement(Optional.of("berry_fog"), 4,
                        QualityGrade.COMMON, false, Optional.empty())),
                Instant.now().plusSeconds(3600), new ContractReward(8, 0, 1), 1, createdAt);
    }

    private void makeOverdue(UUID player) {
        database.update("UPDATE contracts SET deadline=? WHERE owner_uuid=? AND contract_status='ACTIVE'",
                pastDeadline, player).join();
    }
}
