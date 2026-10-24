package com.verdantvice.scenario;

import com.verdantvice.common.BlockPosition;
import com.verdantvice.contract.*;
import com.verdantvice.effects.*;
import com.verdantvice.harvest.HarvestService;
import com.verdantvice.harvest.HarvestWindowService;
import com.verdantvice.market.MarketPricingService;
import com.verdantvice.market.MarketState;
import com.verdantvice.plant.GrowthStage;
import com.verdantvice.plant.Plant;
import com.verdantvice.plant.growth.GrowthPolicy;
import com.verdantvice.plant.growth.PlantGrowthService;
import com.verdantvice.processing.*;
import com.verdantvice.test.TestFixtures;
import com.verdantvice.tolerance.*;
import com.verdantvice.trade.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises real domain services together; Paper/SQL boundaries have separate suites. */
class GameplayLifecycleTest {
    private static final byte[] SECRET =
            "scenario-only-32-byte-signing-secret".getBytes(StandardCharsets.UTF_8);

    @ParameterizedTest
    @ValueSource(longs = {1L, 7L, 91L})
    void plantingToHarvestProcessingTradeContractAndEffects(long randomSeed) {
        var random = new SplittableRandom(randomSeed);
        var authenticity = new BatchAuthenticityService();
        var growth = new PlantGrowthService();
        var harvest = new HarvestService(new HarvestWindowService());
        var drying = new DryingService();
        var curing = new CuringService();
        var harvestAt = TestFixtures.NOW.plus(Duration.ofHours(11));
        var dryAt = harvestAt.plus(Duration.ofHours(2));
        var cureAt = dryAt.plus(Duration.ofHours(2));
        var harvests = new java.util.ArrayList<ProductBatch>();

        for (int index = 0; index < 4; index++) {
            Plant plant = Plant.planted(UUID.randomUUID(), TestFixtures.OWNER,
                    new BlockPosition(TestFixtures.WORLD, 10 + index * 5, 64, 10),
                    TestFixtures.strain(), TestFixtures.genetics(),
                    TestFixtures.NOW, Optional.empty());
            for (int hour = 1; hour <= 10; hour++) {
                var at = TestFixtures.NOW.plus(Duration.ofHours(hour));
                plant = growth.update(plant, TestFixtures.strain(), TestFixtures.genetics(),
                        at, GrowthPolicy.defaults(), true).plant();
                if (plant.hydration() < 65.0) {
                    plant = growth.water(plant, 15.0, at).plant();
                }
            }
            assertEquals(GrowthStage.READY, plant.growthStage());
            ProductBatch fresh = authenticity.sign(harvest.harvest(UUID.randomUUID(), plant,
                    TestFixtures.strain(), TestFixtures.genetics(), harvestAt), SECRET);
            assertTrue(authenticity.verify(fresh, SECRET));
            assertTrue(fresh.geneticOrigin().contains(plant.geneticProfileId()));
            ProductBatch dried = drying.update(drying.start(fresh, harvestAt,
                    Duration.ofHours(2), 100, 100, 1), dryAt, true);
            assertEquals(BatchState.DRIED, dried.state());
            assertTrue(dried.dryWeight() > 0 && dried.dryWeight() < fresh.freshWeight());
            assertFalse(authenticity.verify(dried, SECRET),
                    "Processing must invalidate the old persistent signature");
            ProductBatch cured = authenticity.sign(curing.update(curing.start(dried, dryAt,
                    Duration.ofHours(2), 100, 100), cureAt, true), SECRET);
            assertTrue(authenticity.verify(cured, SECRET));
            assertEquals(fresh.batchId(), cured.batchId());
            assertEquals(BatchState.CURED, cured.state());
            assertTrue(cured.qualityScore() >= dried.qualityScore());
            harvests.add(cured);
        }

        var first = harvests.get(0);
        var second = harvests.get(1);
        var grinder = new GrindingService();
        var parameters = new GrinderParameters(2, 100, 100, 0.95, 0);
        GroundProduct groundA = grinder.grind(UUID.randomUUID(), first, parameters,
                first.dryWeight(), 80, random, cureAt);
        GroundProduct groundB = grinder.grind(UUID.randomUUID(), second, parameters,
                second.dryWeight(), 80, random, cureAt);
        Mixture mixture = new MixtureService().mix(UUID.randomUUID(), TestFixtures.OWNER,
                List.of(new MixtureComponent(groundA, groundA.weight()),
                        new MixtureComponent(groundB, groundB.weight())),
                4, 0.95, 80, random, cureAt);
        assertEquals(Set.of(first.batchId(), second.batchId()), mixture.sourceBatchIds());
        assertEquals(groundA.weight() + groundB.weight(), mixture.totalWeight(), 1e-9);
        assertTrue(mixture.totalWeight() <= first.dryWeight() + second.dryWeight());
        RolledProduct rolled = new RollingService().roll(UUID.randomUUID(), mixture,
                new RollingAttempt(List.of(RollingStep.PAPER, RollingStep.FILTER,
                        RollingStep.ADD_PRODUCT, RollingStep.SHAPE, RollingStep.SEAL),
                        List.of(25L, 35L, 45L, 20L, 40L), 1.0),
                2, 80, cureAt);
        assertEquals(RollingResult.PERFECT, rolled.result());
        assertEquals(mixture.mixtureId(), rolled.mixtureId());
        assertEquals(2.0, rolled.retainedWeight(), 1e-9);

        var tolerance = new ToleranceService();
        var tolerancePolicy = TolerancePolicy.defaults();
        var initial = ToleranceState.initial(TestFixtures.OWNER, cureAt);
        var effects = new EffectService(tolerance);
        var effectPolicy = EffectPolicy.defaults();
        EffectOutcome outcome = effects.calculate(TestFixtures.strain().effectProfile(),
                TestFixtures.strain().negativeEffectProfile(), mixture.qualityScore(),
                rolled.retainedWeight(), mixture.stability(), initial, tolerancePolicy,
                new PlayerCondition(100, 100, 0, false), effectPolicy, random);
        assertTrue(outcome.accepted());
        assertTrue(outcome.strong());
        assertTrue(outcome.effects().stream().allMatch(effect ->
                effect.duration().compareTo(effectPolicy.maximumDuration()) <= 0));
        assertFalse(effects.calculate(TestFixtures.strain().effectProfile(), EffectProfile.empty(),
                mixture.qualityScore(), rolled.retainedWeight(), mixture.stability(), initial,
                tolerancePolicy, new PlayerCondition(100, 100, 0, true), effectPolicy, random).accepted());
        var used = tolerance.recordUse(initial, rolled.retainedWeight(), cureAt, tolerancePolicy);
        assertTrue(used.level() > initial.level());
        assertTrue(tolerance.decay(used, cureAt.plus(Duration.ofDays(2)), tolerancePolicy).level()
                < used.level());

        var saleBatch = harvests.get(2);
        var contractBatch = harvests.get(3);
        var market = new MarketPricingService();
        var balanced = MarketState.balanced(saleBatch.strainComposition().keySet().iterator().next(),
                1, 100, cureAt);
        var quote = market.quote(TestFixtures.strain(), balanced, saleBatch.qualityScore(),
                saleBatch.freshness(), 50);
        var afterSale = market.recordSale(balanced, saleBatch.dryWeight(), cureAt);
        assertTrue(market.quote(TestFixtures.strain(), afterSale, saleBatch.qualityScore(),
                saleBatch.freshness(), 50).unitPrice() <= quote.unitPrice());
        var day = cureAt.atZone(ZoneOffset.UTC).toLocalDate();
        var limits = new TradeLimits(10, 1_000, 100_000);
        var trades = new TradeLimitService();
        var sale = trades.evaluate(saleBatch, saleBatch.dryWeight(), saleBatch.dryWeight() * quote.unitPrice(),
                authenticity.verify(saleBatch, SECRET), TradeLedger.empty(TestFixtures.OWNER, day), limits, day);
        assertTrue(sale.accepted());
        assertFalse(trades.evaluate(saleBatch, saleBatch.dryWeight(), 1, true,
                sale.ledger(), limits, day).accepted());

        var contracts = new ContractService();
        Contract contract = Contract.active(UUID.randomUUID(), TestFixtures.OWNER, UUID.randomUUID(),
                ContractType.CURED_PRODUCT, List.of(new ContractRequirement(Optional.of("berry_fog"),
                        contractBatch.dryWeight(), QualityGrade.COMMON, true, Optional.empty())),
                cureAt.plus(Duration.ofDays(1)), new ContractReward(12, 0, 3), 3, cureAt);
        var delivery = contracts.deliver(contract, contractBatch,
                authenticity.verify(contractBatch, SECRET), cureAt);
        assertTrue(delivery.newlyCompleted());
        Contract claimed = contracts.claim(delivery.contract());
        assertEquals(ContractStatus.CLAIMED, claimed.status());
        assertThrows(IllegalStateException.class, () -> contracts.claim(claimed));
    }
}
