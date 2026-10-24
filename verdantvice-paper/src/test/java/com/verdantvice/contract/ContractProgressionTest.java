package com.verdantvice.contract;

import com.verdantvice.herbalist.HerbalistOffer;
import com.verdantvice.herbalist.HerbalistOfferKind;
import com.verdantvice.herbalist.HerbalistTier;
import com.verdantvice.processing.BatchState;
import com.verdantvice.security.CustomItemType;
import com.verdantvice.test.TestFixtures;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class ContractProgressionTest {
    @Test
    void contractsOnlyRequestSeedsActuallySoldByThisTier() {
        assertTrue(ContractProgression.availableStrains(List.of(TestFixtures.strain()), List.of()).isEmpty());
        assertEquals(List.of(TestFixtures.strain()), ContractProgression.availableStrains(
                List.of(TestFixtures.strain()), List.of(seed())));
    }

    @Test
    void noviceCannotReceiveACuringObjectiveBeforeBuyingACuringContainer() {
        assertEquals(ContractType.STRAIN_DELIVERY, ContractProgression.playableType(
                ContractType.CURED_PRODUCT, List.of(seed()), false));
        var curing = new HerbalistOffer("curing-container", HerbalistOfferKind.TOOL,
                Material.CHISELED_BOOKSHELF, CustomItemType.CURING_CONTAINER, Optional.empty(),
                Optional.empty(), 28, HerbalistTier.APPRENTICE);
        assertEquals(ContractType.CURED_PRODUCT, ContractProgression.playableType(
                ContractType.CURED_PRODUCT, List.of(seed(), curing), false));
    }

    @Test
    void geneticObjectiveRequiresAnAvailableBatchAndNeverMoreThanItsActualWeightOrGrade() {
        assertEquals(ContractType.STRAIN_DELIVERY, ContractProgression.playableType(
                ContractType.GENETIC_DELIVERY, List.of(seed()), false));
        var batch = TestFixtures.driedBatch().transition(0.5, 20, 0, 100,
                TestFixtures.driedBatch().dryingData(), Optional.empty(), BatchState.DRIED);
        var requirement = ContractProgression.geneticRequirement(batch, 16);
        assertEquals(0.5, requirement.requiredWeight());
        assertEquals(batch.qualityGrade(), requirement.minimumQuality());
        var contract = Contract.active(UUID.randomUUID(), TestFixtures.OWNER, UUID.randomUUID(),
                ContractType.GENETIC_DELIVERY, List.of(requirement), TestFixtures.NOW.plusSeconds(3600),
                new ContractReward(1, 0, 0), 0, TestFixtures.NOW);
        assertTrue(new ContractService().deliver(contract, batch, true, TestFixtures.NOW).newlyCompleted(),
                "The existing genetic batch must actually be sufficient to complete its generated objective");
    }

    private static HerbalistOffer seed() {
        return new HerbalistOffer("seed:berry_fog", HerbalistOfferKind.SEED, Material.WHEAT_SEEDS,
                CustomItemType.SEED, Optional.of("berry_fog"), Optional.empty(), 4, HerbalistTier.NOVICE);
    }
}
