package com.verdantvice.contract;

import com.verdantvice.processing.QualityGrade;
import com.verdantvice.test.TestFixtures;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ContractServiceTest {
    @Test
    void deliveryCompletesAndRewardCanOnlyBeClaimedOnce() {
        Contract contract = Contract.active(UUID.randomUUID(), TestFixtures.OWNER, UUID.randomUUID(),
                ContractType.STRAIN_DELIVERY,
                List.of(new ContractRequirement(Optional.of("berry_fog"), 5.0,
                        QualityGrade.GOOD, false, Optional.empty())),
                TestFixtures.NOW.plus(Duration.ofDays(2)),
                new ContractReward(12, 0.0, 3.0), 3.0, TestFixtures.NOW);
        ContractUpdate update = new ContractService().deliver(contract, TestFixtures.driedBatch(),
                true, TestFixtures.NOW.plus(Duration.ofHours(12)));
        assertTrue(update.accepted());
        assertTrue(update.newlyCompleted());
        assertEquals(ContractStatus.COMPLETED, update.contract().status());

        Contract claimed = new ContractService().claim(update.contract());
        assertEquals(ContractStatus.CLAIMED, claimed.status());
        assertThrows(IllegalStateException.class, () -> new ContractService().claim(claimed));
    }

    @Test
    void duplicateBatchDoesNotContributeTwice() {
        Contract contract = Contract.active(UUID.randomUUID(), TestFixtures.OWNER, UUID.randomUUID(),
                ContractType.WHOLESALE,
                List.of(new ContractRequirement(Optional.of("berry_fog"), 15.0,
                        QualityGrade.COMMON, false, Optional.empty())),
                TestFixtures.NOW.plus(Duration.ofDays(2)),
                new ContractReward(20, 0, 2), 2, TestFixtures.NOW);
        ContractService service = new ContractService();
        ContractUpdate first = service.deliver(contract, TestFixtures.driedBatch(), true,
                TestFixtures.NOW.plusSeconds(1));
        assertFalse(first.newlyCompleted());
        ContractUpdate duplicate = service.deliver(first.contract(), TestFixtures.driedBatch(), true,
                TestFixtures.NOW.plusSeconds(2));
        assertFalse(duplicate.accepted());
        assertEquals("batch-already-delivered", duplicate.reasonCode());
        assertEquals(first.contract().deliveredWeight(), duplicate.contract().deliveredWeight());
    }

    @Test
    void lateDeliveryExpiresContract() {
        Contract contract = Contract.active(UUID.randomUUID(), TestFixtures.OWNER, UUID.randomUUID(),
                ContractType.EXPRESS_DELIVERY,
                List.of(new ContractRequirement(Optional.empty(), 1.0,
                        QualityGrade.COMMON, false, Optional.empty())),
                TestFixtures.NOW.plusSeconds(30),
                new ContractReward(2, 0, 1), 1, TestFixtures.NOW);
        ContractUpdate update = new ContractService().deliver(contract, TestFixtures.driedBatch(),
                true, TestFixtures.NOW.plusSeconds(31));
        assertFalse(update.accepted());
        assertEquals(ContractStatus.EXPIRED, update.contract().status());
    }
}
