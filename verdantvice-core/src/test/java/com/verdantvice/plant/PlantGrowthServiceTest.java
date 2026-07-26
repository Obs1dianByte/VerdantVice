package com.verdantvice.plant;

import com.verdantvice.harvest.HarvestService;
import com.verdantvice.harvest.HarvestTiming;
import com.verdantvice.harvest.HarvestWindowService;
import com.verdantvice.plant.growth.GrowthPolicy;
import com.verdantvice.plant.growth.PlantGrowthService;
import com.verdantvice.plant.growth.PlantUpdate;
import com.verdantvice.processing.BatchState;
import com.verdantvice.test.TestFixtures;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlantGrowthServiceTest {
    private final PlantGrowthService growth = new PlantGrowthService();

    @Test
    void advancesFromTimestampsInsteadOfTicks() {
        PlantUpdate update = growth.update(TestFixtures.plant(), TestFixtures.strain(),
                TestFixtures.genetics(), TestFixtures.NOW.plus(Duration.ofHours(6)),
                GrowthPolicy.defaults(), true);

        assertEquals(GrowthStage.VEGETATIVE, update.plant().growthStage());
        assertEquals(60.0, update.plant().growthProgress(), 0.0001);
        assertEquals(60.0, update.plant().hydration(), 0.0001);
        assertEquals(Duration.ofHours(6), update.simulatedElapsed());
        assertTrue(update.stageChanged());
    }

    @Test
    void obeysOfflineAndMaximumElapsedPolicies() {
        GrowthPolicy disabled = new GrowthPolicy(false, true, Duration.ofDays(1),
                2.5, 0.7, 4.0, 2.0, Duration.ofHours(12));
        PlantUpdate frozen = growth.update(TestFixtures.plant(), TestFixtures.strain(),
                TestFixtures.genetics(), TestFixtures.NOW.plus(Duration.ofHours(6)), disabled, true);
        assertEquals(0.0, frozen.plant().growthProgress());

        GrowthPolicy capped = new GrowthPolicy(true, true, Duration.ofHours(2),
                2.5, 0.7, 4.0, 2.0, Duration.ofHours(12));
        PlantUpdate limited = growth.update(TestFixtures.plant(), TestFixtures.strain(),
                TestFixtures.genetics(), TestFixtures.NOW.plus(Duration.ofDays(2)), capped, true);
        assertEquals(Duration.ofHours(2), limited.simulatedElapsed());
        assertEquals(20.0, limited.plant().growthProgress(), 0.0001);
    }

    @Test
    void wateringCapsHydrationAndPenalizesOverwatering() {
        Plant plant = TestFixtures.plant();
        var ordinary = growth.water(plant, 20.0, TestFixtures.NOW.plusSeconds(1));
        assertEquals(95.0, ordinary.plant().hydration());
        assertFalse(ordinary.overwatered());

        var excess = growth.water(ordinary.plant(), 30.0, TestFixtures.NOW.plusSeconds(2));
        assertEquals(100.0, excess.plant().hydration());
        assertEquals(5.0, excess.appliedAmount());
        assertTrue(excess.overwatered());
        assertTrue(excess.plant().stress() > ordinary.plant().stress());
    }

    @Test
    void categorizesTheHarvestWindow() {
        HarvestWindowService windows = new HarvestWindowService();
        Plant base = TestFixtures.plant();
        Plant almostReady = base.withSimulation(GrowthStage.MATURING, 90.0, 100.0, 70.0,
                70.0, 0.0, 50.0, 80.0, TestFixtures.NOW.plus(Duration.ofHours(9)));
        assertEquals(HarvestTiming.EARLY,
                windows.assess(almostReady, TestFixtures.NOW.plus(Duration.ofHours(9))));
        Plant ready = almostReady.withSimulation(GrowthStage.READY, 100.0, 100.0, 60.0,
                60.0, 0.0, 50.0, 80.0, TestFixtures.NOW.plus(Duration.ofHours(10)));
        assertEquals(HarvestTiming.PERFECT,
                windows.assess(ready, TestFixtures.NOW.plus(Duration.ofHours(11))));
        assertEquals(HarvestTiming.OVERRIPE,
                windows.assess(ready, TestFixtures.NOW.plus(Duration.ofHours(12))));
    }

    @Test
    void spoiledPlantCannotBeHarvested() {
        GrowthPolicy policy = GrowthPolicy.defaults();
        PlantUpdate update = growth.update(TestFixtures.plant(), TestFixtures.strain(),
                TestFixtures.genetics(), TestFixtures.NOW.plus(Duration.ofHours(25)), policy, true);
        assertEquals(GrowthStage.SPOILED, update.plant().growthStage());
        HarvestService harvest = new HarvestService(new HarvestWindowService());
        assertThrows(IllegalStateException.class, () -> harvest.harvest(UUID.randomUUID(), update.plant(),
                TestFixtures.strain(), TestFixtures.genetics(), TestFixtures.NOW.plus(Duration.ofHours(25))));
    }

    @Test
    void harvestCreatesUniqueFreshBatchWithOrigin() {
        Plant ready = TestFixtures.plant().withSimulation(GrowthStage.READY, 100.0, 95.0,
                65.0, 60.0, 5.0, 50.0, 80.0,
                TestFixtures.NOW.plus(Duration.ofHours(10)));
        var batchId = UUID.randomUUID();
        var batch = new HarvestService(new HarvestWindowService()).harvest(batchId, ready,
                TestFixtures.strain(), TestFixtures.genetics(), TestFixtures.NOW.plus(Duration.ofHours(11)));
        assertEquals(batchId, batch.batchId());
        assertEquals(BatchState.FRESH, batch.state());
        assertEquals(1.0, batch.strainComposition().get("berry_fog"));
        assertTrue(batch.geneticOrigin().contains(TestFixtures.GENETICS_ID));
    }
}
