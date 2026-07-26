package com.verdantvice.genetics;

import com.verdantvice.test.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGeneratorFactory;

import static org.junit.jupiter.api.Assertions.*;

class GeneticsServiceTest {
    private final GeneticsService service = new GeneticsService();

    @Test
    void classifiesEveryQualityBand() {
        assertEquals(GeneticClass.DAMAGED, service.classify(0.5));
        assertEquals(GeneticClass.POOR, service.classify(0.7));
        assertEquals(GeneticClass.COMMON, service.classify(0.9));
        assertEquals(GeneticClass.GOOD, service.classify(1.1));
        assertEquals(GeneticClass.EXCEPTIONAL, service.classify(1.2));
        assertEquals(GeneticClass.PERFECT, service.classify(1.35));
        assertEquals(GeneticClass.LEGENDARY, service.classify(1.5));
    }

    @Test
    void generatedAndCrossedTraitsStayInsideSafeBounds() {
        var random = RandomGeneratorFactory.<java.util.random.RandomGenerator>of("L64X128MixRandom")
                .create(42L);
        GeneticProfile first = service.generate(random, TestFixtures.NOW);
        GeneticProfile second = service.generate(random, TestFixtures.NOW);
        GeneticProfile child = service.cross(UUID.randomUUID(), first, second, random,
                TestFixtures.NOW.plusSeconds(1));

        assertAll(
                () -> assertTrue(child.yieldModifier() >= 0.25 && child.yieldModifier() <= 2.0),
                () -> assertTrue(child.growthSpeedModifier() >= 0.25 && child.growthSpeedModifier() <= 2.0),
                () -> assertTrue(child.resistanceModifier() >= 0.25 && child.resistanceModifier() <= 2.0),
                () -> assertNotNull(child.geneticClass()));
    }

    @Test
    void rejectsBlockedAndOverlongGeneticLineNames() {
        GeneticLineService names = new GeneticLineService(16, Set.of("forbidden"));
        assertThrows(IllegalArgumentException.class, () -> names.validateName("My Forbidden Line"));
        assertThrows(IllegalArgumentException.class, () -> names.validateName("A name that is much too long"));
        assertEquals("Neon Garden", names.validateName("  Neon   Garden "));
        GeneticLine line = names.create(UUID.randomUUID(), "Neon Garden",
                Set.of("berry_fog", "neon_haze"), TestFixtures.OWNER, 2,
                Map.of("quality", 1.2), Set.of("violet"), Set.of("speed"),
                0.8, TestFixtures.NOW);
        assertEquals(2, line.parentStrainIds().size());
    }
}
