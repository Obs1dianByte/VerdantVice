package com.verdantvice.plant.care;

import com.verdantvice.plant.Plant;
import com.verdantvice.test.TestFixtures;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PlantCareServiceTest {
    private final PlantCareService care = new PlantCareService();

    @Test
    void nutrientsClampAtOneHundredAndExcessHasARealPenalty() {
        Plant plant = TestFixtures.plant();
        CareResult fed = care.feed(plant, 40.0, 1.0, TestFixtures.NOW.plusSeconds(1));
        assertEquals(100.0, fed.plant().nutrition());
        assertEquals(25.0, fed.appliedAmount());
        assertTrue(fed.penalty() > 0.0);
        assertTrue(fed.plant().stress() > plant.stress());
        assertTrue(fed.plant().qualityPotential() < plant.qualityPotential());
    }

    @Test
    void cleaningClampsAndDoesNotMagicallyConsumeMoreCapacity() {
        Plant dirty = TestFixtures.plant().withEnvironment(10, 20.0, Optional.empty(), 0.0);
        CareResult cleaned = care.clean(dirty, 100.0, 2.0, TestFixtures.NOW.plusSeconds(1));
        assertEquals(100.0, cleaned.plant().cleanliness());
        assertEquals(80.0, cleaned.appliedAmount());
        assertTrue(cleaned.problemResolved());
        assertEquals(100.0, cleaned.consumedResource());
    }

    @Test
    void treatmentClearsDiseaseAndPestsButOveruseDamagesQuality() {
        Plant sick = TestFixtures.plant().withEnvironment(10, 80.0,
                Optional.of("fictional_mold"), 10.0);
        CareResult precise = care.treat(sick, 30.0, 1.0, TestFixtures.NOW.plusSeconds(1));
        assertTrue(precise.problemResolved());
        assertEquals(0.0, precise.penalty());

        Plant healthy = precise.plant();
        CareResult excessive = care.treat(healthy, 50.0, 1.0,
                TestFixtures.NOW.plusSeconds(2));
        assertEquals(0.0, excessive.appliedAmount());
        assertTrue(excessive.penalty() > 0.0);
        assertTrue(excessive.plant().qualityPotential() < healthy.qualityPotential());
    }

    @Test
    void repairRelievesStressWithoutExceedingHealthOrGoingBelowZero() {
        Plant stressed = TestFixtures.plant().withCare(98.0, 75.0, 80.0, 100.0,
                Optional.empty(), 0.0, 50.0, 80.0, TestFixtures.NOW.plusSeconds(1));
        CareResult repaired = care.repairAndRelieveStress(stressed, 100.0, 2.0,
                TestFixtures.NOW.plusSeconds(2));
        assertEquals(0.0, repaired.plant().stress());
        assertEquals(100.0, repaired.plant().health());
        assertEquals(80.0, repaired.appliedAmount());
        assertTrue(repaired.problemResolved());
    }

    @Test
    void rejectsCareBeforeTheLatestPlantTimestamp() {
        Plant current = TestFixtures.plant().withCare(100.0, 75.0, 0.0, 100.0,
                Optional.empty(), 0.0, 50.0, 80.0, TestFixtures.NOW.plus(Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class,
                () -> care.clean(current, 10.0, 1.0, TestFixtures.NOW));
    }
}
