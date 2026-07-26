package com.verdantvice.processing;

import com.verdantvice.test.TestFixtures;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGeneratorFactory;

import static org.junit.jupiter.api.Assertions.*;

class ProcessingPipelineTest {
    @Test
    void configurableQualityWeightsIncludeContaminationPenalty() {
        QualityService quality = new QualityService();
        QualityInputs pristine = new QualityInputs(100, 100, 100, 100, 100,
                100, 100, 100, 100, 0);
        QualityInputs contaminated = new QualityInputs(100, 100, 100, 100, 100,
                100, 100, 100, 100, 80);
        assertEquals(100.0, quality.calculate(pristine, QualityWeights.defaults()), 0.0001);
        assertTrue(quality.calculate(contaminated, QualityWeights.defaults())
                < quality.calculate(pristine, QualityWeights.defaults()));
        assertEquals(QualityGrade.VERDANT_GRADE, quality.grade(pristine, QualityWeights.defaults()));
    }

    @Test
    void dryingUsesElapsedTimeAndIsIdempotent() {
        DryingService drying = new DryingService();
        ProductBatch started = drying.start(TestFixtures.freshBatch(), TestFixtures.NOW,
                Duration.ofHours(10), 100.0, 100.0, 1.0);
        ProductBatch ready = drying.update(started, TestFixtures.NOW.plus(Duration.ofHours(10)), true);
        ProductBatch repeated = drying.update(ready, TestFixtures.NOW.plus(Duration.ofHours(10)), true);

        assertEquals(BatchState.DRIED, ready.state());
        assertEquals(DryingStage.READY, ready.dryingData().orElseThrow().stage());
        assertEquals(8.8, ready.dryWeight(), 0.0001);
        assertEquals(ready, repeated);
    }

    @Test
    void neglectedDryingRuinsBatch() {
        DryingService drying = new DryingService();
        ProductBatch started = drying.start(TestFixtures.freshBatch(), TestFixtures.NOW,
                Duration.ofHours(10), 50.0, 50.0, 1.0);
        ProductBatch ruined = drying.update(started, TestFixtures.NOW.plus(Duration.ofHours(20)), true);
        assertEquals(BatchState.SPOILED, ruined.state());
        assertEquals(DryingStage.RUINED, ruined.dryingData().orElseThrow().stage());
        assertEquals(QualityGrade.SPOILED, ruined.qualityGrade());
    }

    @Test
    void optionalCuringImprovesAWellHandledBatch() {
        ProductBatch dried = TestFixtures.driedBatch();
        CuringService curing = new CuringService();
        ProductBatch started = curing.start(dried, TestFixtures.NOW.plus(Duration.ofHours(10)),
                Duration.ofHours(10), 100.0, 100.0);
        ProductBatch cured = curing.update(started, TestFixtures.NOW.plus(Duration.ofHours(20)), true);
        assertEquals(BatchState.CURED, cured.state());
        assertEquals(CuringStage.OPTIMAL, cured.curingData().orElseThrow().stage());
        assertTrue(cured.qualityScore() > dried.qualityScore());
    }

    @Test
    void grindingPreservesSourceBatchAndProperties() {
        ProductBatch batch = TestFixtures.curedBatch();
        GroundProduct ground = new GrindingService().grind(UUID.randomUUID(), batch,
                new GrinderParameters(2, 20.0, 100.0, 0.95, 0.0),
                5.0, 80.0,
                RandomGeneratorFactory.<java.util.random.RandomGenerator>of("L64X128MixRandom").create(5L),
                TestFixtures.NOW.plus(Duration.ofHours(21)));
        assertTrue(ground.sourceBatchIds().contains(batch.batchId()));
        assertEquals(batch.strainComposition(), ground.strainComposition());
        assertTrue(ground.weight() > 4.5 && ground.weight() <= 5.0);
    }

    @Test
    void mixtureKeepsExactWeightBasedProportions() {
        GroundProduct first = ground("60000000-0000-0000-0000-000000000006",
                "berry_fog", 10.0, 80.0);
        GroundProduct second = ground("70000000-0000-0000-0000-000000000007",
                "neon_haze", 10.0, 60.0);
        Mixture mixture = new MixtureService().mix(UUID.randomUUID(), TestFixtures.OWNER,
                List.of(new MixtureComponent(first, 7.0), new MixtureComponent(second, 3.0)),
                4, 0.9, 50.0,
                RandomGeneratorFactory.<java.util.random.RandomGenerator>of("L64X128MixRandom").create(3L),
                TestFixtures.NOW);

        assertEquals(0.7, mixture.strainComposition().get("berry_fog"), 0.0001);
        assertEquals(0.3, mixture.strainComposition().get("neon_haze"), 0.0001);
        assertEquals(74.0, mixture.qualityScore(), 0.0001);
        assertEquals(2, mixture.sourceBatchIds().size());
    }

    @Test
    void mixtureRejectsTheSameSourceBatchTwice() {
        GroundProduct source = ground("60000000-0000-0000-0000-000000000006",
                "berry_fog", 10.0, 80.0);
        assertThrows(IllegalArgumentException.class, () -> new MixtureService().mix(
                UUID.randomUUID(), TestFixtures.OWNER,
                List.of(new MixtureComponent(source, 3.0), new MixtureComponent(source, 2.0)),
                4, 1.0, 50.0,
                RandomGeneratorFactory.<java.util.random.RandomGenerator>of("L64X128MixRandom").create(4L),
                TestFixtures.NOW));
    }

    @Test
    void rollingRequiresTheFullOrderedMinigame() {
        RollingService service = new RollingService();
        RollingAttempt perfect = new RollingAttempt(
                List.of(RollingStep.PAPER, RollingStep.FILTER, RollingStep.ADD_PRODUCT,
                        RollingStep.SHAPE, RollingStep.SEAL),
                List.of(30L, 40L, 50L, 60L, 40L),
                1.0);
        assertEquals(RollingResult.PERFECT, service.classify(perfect, 80.0));
        RollingAttempt wrongOrder = new RollingAttempt(
                List.of(RollingStep.FILTER, RollingStep.PAPER), List.of(10L, 10L), 1.0);
        assertEquals(RollingResult.FAILED, service.classify(wrongOrder, 100.0));
    }

    private static GroundProduct ground(String sourceId, String strain, double weight, double quality) {
        return new GroundProduct(UUID.randomUUID(), TestFixtures.OWNER,
                java.util.Set.of(UUID.fromString(sourceId)), java.util.Map.of(strain, 1.0),
                weight, quality, QualityGrade.fromScore(quality), 0.9, TestFixtures.NOW);
    }
}
