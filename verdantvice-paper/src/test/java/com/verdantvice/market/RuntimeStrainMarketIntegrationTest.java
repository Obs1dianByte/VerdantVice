package com.verdantvice.market;

import com.verdantvice.database.DatabaseManager;
import com.verdantvice.database.item.ItemRegistration;
import com.verdantvice.database.item.ItemRegistryRepository;
import com.verdantvice.herbalist.HerbalistRepository;
import com.verdantvice.processing.BatchAuthenticityService;
import com.verdantvice.processing.BatchState;
import com.verdantvice.processing.ProductBatch;
import com.verdantvice.security.CustomItemType;
import com.verdantvice.security.ItemUsePolicy;
import com.verdantvice.security.SignedItemData;
import com.verdantvice.storage.BatchRepository;
import com.verdantvice.storage.BatchService;
import com.verdantvice.strain.Strain;
import com.verdantvice.strain.StrainRegistry;
import com.verdantvice.test.TestFixtures;
import com.verdantvice.trade.PreparedSale;
import com.verdantvice.trade.SaleCommitResult;
import com.verdantvice.trade.TradeFailureReason;
import com.verdantvice.trade.TradeRepository;
import com.verdantvice.trade.TradeValidationException;
import com.verdantvice.worker.WorkerReliabilityTestSupport;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeStrainMarketIntegrationTest {
    private static final byte[] SECRET = new byte[32];
    private static final String HASH = "a".repeat(64);
    @TempDir Path directory;
    private final Map<String, YamlConfiguration> configurations = new HashMap<>();
    private DatabaseManager database;
    private MarketRepository repository;
    private PaperMarketService markets;
    private StrainRegistry strains;
    private BatchService batches;
    private ItemRegistryRepository items;
    private TradeRepository trades;
    private Instant now;
    private UUID herbalist;

    @BeforeEach
    void startWithOnlyTheOriginalVariety() throws Exception {
        for (String name : List.of("market.yml", "economy.yml", "villagers.yml")) {
            try (var stream = getClass().getClassLoader().getResourceAsStream(name)) {
                assertNotNull(stream, name);
                configurations.put(name, YamlConfiguration.loadConfiguration(
                        new InputStreamReader(stream, StandardCharsets.UTF_8)));
            }
        }
        database = WorkerReliabilityTestSupport.start(directory);
        repository = new MarketRepository(database, configurations::get);
        strains = new StrainRegistry();
        strains.register(TestFixtures.strain());
        markets = new PaperMarketService(repository, strains, configurations::get);
        await(markets.initialize());
        items = new ItemRegistryRepository(database);
        batches = new BatchService(new BatchRepository(database), new BatchAuthenticityService(), SECRET);
        trades = new TradeRepository(database, items, repository, strains, configurations::get,
                batches, new ItemUsePolicy(false));
        now = Instant.now();
        herbalist = UUID.randomUUID();
        await(new HerbalistRepository(database).createIgnoringVillageLimit(
                herbalist, UUID.randomUUID(), UUID.randomUUID(), 0, 64, 0, 32, 0, 0, now));
    }

    @AfterEach
    void close() {
        if (database != null) database.close();
    }

    @Test
    void newGeneticVarietyCanBeQuotedWithoutRestartAndExistingMarketsAreUntouched() throws Exception {
        PaperMarketRecord changed = changeExistingMarket();
        Strain child = register("custom_child");
        assertTrue(await(repository.find(child.id())).isEmpty());

        var quote = await(markets.quote(child.id(), 65, 100, 0));
        assertTrue(quote.unitPrice() > 0);
        assertTrue(await(repository.find(child.id())).isPresent());
        assertEquals(changed, await(repository.find(changed.strainId())).orElseThrow());
        assertEquals(child.baseMarketPrice(), markets.cachedPrice(child.id()));

        await(markets.quote(child.id(), 80, 90, 5));
        await(markets.quote(changed.strainId(), 80, 90, 5));
        assertEquals(changed, await(repository.find(changed.strainId())).orElseThrow());
        assertEquals(0, await(repository.find(child.id())).orElseThrow().version());
    }

    @Test
    void concurrentFirstQuotesCreateExactlyOneRow() throws Exception {
        Strain child = register("concurrent_child");
        List<CompletableFuture<MarketQuote>> quotes = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            quotes.add(markets.quote(child.id(), 65, 100, 0));
        }
        await(CompletableFuture.allOf(quotes.toArray(CompletableFuture[]::new)));
        assertEquals(2, await(repository.all()).size());
        assertEquals(0, await(repository.find(child.id())).orElseThrow().version());
        assertTrue(quotes.stream().allMatch(quote -> quote.join().unitPrice() > 0));
    }

    @Test
    void firstSaleOfMixedCustomHarvestInitializesOnlyItsComponentsAndCommits() throws Exception {
        PaperMarketRecord changed = changeExistingMarket();
        Strain first = register("first_child");
        Strain second = register("second_child");
        Strain unrelated = register("unused_child");
        SignedItemData item = storedProduct(Map.of(changed.strainId(), 0.5,
                first.id(), 0.25, second.id(), 0.25));

        PreparedSale quote = await(trades.prepareSale(item, HASH, item.ownerUuid(), herbalist, now));
        assertTrue(quote.payout() > 0);
        assertEquals(3, quote.marketVersions().size());
        assertEquals(3, await(repository.all()).size());
        assertTrue(await(repository.find(unrelated.id())).isEmpty());
        assertEquals(changed, await(repository.find(changed.strainId())).orElseThrow(),
                "Preparing a sale cannot reset prices, supply, demand, volume or row version");
        assertEquals(SaleCommitResult.Status.COMMITTED,
                await(trades.commit(quote, "custom-first-sale", now.plusSeconds(1))).status());
        assertEquals(SaleCommitResult.Status.ALREADY_COMMITTED,
                await(trades.commit(quote, "custom-first-sale", now.plusSeconds(2))).status());
        assertTrue(await(repository.find(first.id())).orElseThrow().state().serverSalesWeight() > 0);
        assertTrue(await(repository.find(second.id())).orElseThrow().state().serverSalesWeight() > 0);
    }

    @Test
    void aForeignBatchCannotInitializeAnotherPlayersCustomMarket() throws Exception {
        Strain child = register("private_child");
        SignedItemData owned = storedProduct(Map.of(child.id(), 1.0));
        UUID outsider = UUID.randomUUID();
        SignedItemData forgedOwner = new SignedItemData(owned.itemType(), owned.itemId(),
                owned.uniqueInstanceId(), owned.batchId(), outsider, owned.quality(),
                owned.strain(), owned.createdAt(), owned.schemaVersion());
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> await(trades.prepareSale(forgedOwner, HASH, outsider, herbalist, now)));
        assertEquals(TradeFailureReason.WRONG_OWNER,
                ((TradeValidationException) failure.getCause()).reason());
        assertTrue(await(repository.find(child.id())).isEmpty());
    }

    @Test
    void invalidDurableBatchSignatureCannotCreateMarketRows() throws Exception {
        Strain child = register("tampered_child");
        SignedItemData item = storedProduct(Map.of(child.id(), 1.0));
        await(database.transaction(session -> {
            session.update("UPDATE product_batches SET signature=? WHERE batch_id=?",
                    "tampered", item.batchId());
            return null;
        }));
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> await(trades.prepareSale(item, HASH, item.ownerUuid(), herbalist, now)));
        assertEquals(TradeFailureReason.INVALID_SIGNATURE,
                ((TradeValidationException) failure.getCause()).reason());
        assertFalse(await(repository.find(child.id())).isPresent());
    }

    private PaperMarketRecord changeExistingMarket() throws Exception {
        PaperMarketRecord original = await(repository.find(TestFixtures.strain().id())).orElseThrow();
        MarketState state = new MarketState(original.strainId(), 2.0, 0.8, 5.0,
                MarketEventType.NONE, 1.0, original.state().minimumPrice(),
                original.state().maximumPrice(), now);
        PaperMarketRecord changed = new PaperMarketRecord(original.strainId(), original.basePrice(),
                13.0, state, MarketEventWindow.none(now), original.version() + 1);
        assertTrue(await(repository.save(original, changed)));
        return await(repository.find(original.strainId())).orElseThrow();
    }

    private Strain register(String id) {
        Strain base = TestFixtures.strain();
        Strain child = new Strain(id, "Custom Test Variety", base.rarity(), base.growthDuration(),
                base.difficulty(), base.baseYield(), base.qualityPotential(), base.diseaseResistance(),
                base.waterConsumption(), base.lightPreference(), base.stressSensitivity(),
                base.smellIntensity(), base.effectProfile(), base.negativeEffectProfile(),
                base.seedPrice(), 12.0, base.visualModel(), base.particleProfile());
        strains.register(child);
        return child;
    }

    private SignedItemData storedProduct(Map<String, Double> composition) throws Exception {
        ProductBatch source = TestFixtures.driedBatch();
        ProductBatch batch = new ProductBatch(UUID.randomUUID(), source.ownerUuid(), composition,
                source.geneticOrigin(), source.freshWeight(), source.dryWeight(), source.qualityScore(),
                source.qualityGrade(), source.contamination(), source.freshness(), source.harvestTiming(),
                source.dryingData(), source.curingData(), now, "", BatchState.DRIED);
        batch = new BatchAuthenticityService().sign(batch, SECRET);
        await(batches.create(batch));
        String dominant = composition.keySet().iterator().next();
        SignedItemData item = new SignedItemData(CustomItemType.DRY_BATCH, "dry-batch", UUID.randomUUID(),
                batch.batchId(), batch.ownerUuid(), batch.qualityScore(), dominant, now, 1);
        await(items.register(new ItemRegistration(item.uniqueInstanceId(), item.itemType().name(),
                item.itemId(), item.batchId(), item.ownerUuid(), item.quality(), dominant, now,
                HASH, 1, null)));
        return item;
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(20, TimeUnit.SECONDS);
    }
}
