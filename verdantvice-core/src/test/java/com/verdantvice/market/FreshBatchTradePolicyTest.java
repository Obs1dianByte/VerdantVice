package com.verdantvice.market;

import com.verdantvice.test.TestFixtures;
import com.verdantvice.trade.TradeLedger;
import com.verdantvice.trade.TradeLimitService;
import com.verdantvice.trade.TradeLimits;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FreshBatchTradePolicyTest {
    @Test
    void explicitlyAcceptedFreshBatchUsesItsRealFreshWeight() {
        var batch = TestFixtures.freshBatch();
        LocalDate day = LocalDate.ofInstant(TestFixtures.NOW, ZoneOffset.UTC);
        var decision = new TradeLimitService().evaluate(
                batch,
                batch.freshWeight(),
                batch.freshWeight(),
                25.0,
                true,
                true,
                TradeLedger.empty(TestFixtures.OWNER, day),
                new TradeLimits(10, 1_000.0, 10_000.0),
                day);
        assertTrue(decision.accepted());
    }
}
