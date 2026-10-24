package com.verdantvice.contract;

import com.verdantvice.database.DatabaseConfig;
import com.verdantvice.database.DatabaseManager;
import com.verdantvice.database.item.ItemRegistration;
import com.verdantvice.database.item.ItemRegistryRepository;
import com.verdantvice.processing.BatchAuthenticityService;
import com.verdantvice.processing.ProductBatch;
import com.verdantvice.storage.BatchRepository;
import com.verdantvice.storage.BatchService;
import com.verdantvice.test.TestFixtures;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

final class GeneticContractCandidateIntegrationTest {
    private static final String HASH = "a".repeat(64);
    @TempDir Path directory;
    private DatabaseManager database;
    private ItemRegistryRepository items;
    private PaperContractRepository contracts;
    private ProductBatch batch;

    @BeforeEach
    void start() {
        Logger logger = Logger.getLogger(getClass().getName() + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        database = new DatabaseManager(DatabaseConfig.fromBukkit(new YamlConfiguration(), directory), logger);
        database.start().toCompletableFuture().join();
        var authenticity = new BatchAuthenticityService();
        var batches = new BatchService(new BatchRepository(database), authenticity, new byte[32]);
        batch = authenticity.sign(TestFixtures.driedBatch(), new byte[32]);
        batches.create(batch).join();
        items = new ItemRegistryRepository(database);
        contracts = new PaperContractRepository(database, items, batches);
    }

    @AfterEach
    void close() {
        if (database != null) database.close();
    }

    @Test
    void anOldBatchWithoutAnAvailablePhysicalOutputDoesNotGenerateAnImpossibleObjective() {
        assertTrue(contracts.candidateGeneticBatch(TestFixtures.OWNER).join().isEmpty());
    }

    @Test
    void anActiveOwnedDryBatchProvidesItsVerifiedCompositionQualityWeightAndOrigin() {
        activeItem();
        assertEquals(batch, contracts.candidateGeneticBatch(TestFixtures.OWNER).join().orElseThrow());
        assertEquals(TestFixtures.GENETICS_ID,
                contracts.candidateGeneticOrigin(TestFixtures.OWNER).join().orElseThrow());
        assertTrue(contracts.candidateGeneticBatch(UUID.randomUUID()).join().isEmpty());
    }

    @Test
    void aSoldOrAlreadyDeliveredItemCannotBeUsedAsTheOnlySourceForANewGeneticObjective() {
        UUID instance = activeItem();
        UUID token = UUID.randomUUID();
        var reserved = items.reserve(instance, 0, HASH, token, TestFixtures.OWNER.toString(),
                Instant.now().plusSeconds(30), "candidate:sale").join();
        items.consume(instance, reserved.current().version(), HASH, token, "candidate:sale", Instant.now()).join();
        assertTrue(contracts.candidateGeneticBatch(TestFixtures.OWNER).join().isEmpty());
    }

    private UUID activeItem() {
        UUID instance = UUID.randomUUID();
        items.register(new ItemRegistration(instance, "DRY_BATCH", "dry-batch", batch.batchId(),
                TestFixtures.OWNER, batch.qualityScore(), "berry_fog", Instant.now(), HASH, 1, null)).join();
        return instance;
    }
}
