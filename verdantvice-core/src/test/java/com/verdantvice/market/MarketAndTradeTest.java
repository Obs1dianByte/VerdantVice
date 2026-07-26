package com.verdantvice.market;

import com.verdantvice.processing.ProductBatch;
import com.verdantvice.test.TestFixtures;
import com.verdantvice.trade.TradeDecision;
import com.verdantvice.trade.TradeLedger;
import com.verdantvice.trade.TradeLimitService;
import com.verdantvice.trade.TradeLimits;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class MarketAndTradeTest {
    @Test
    void quoteAlwaysHonorsConfiguredPriceBounds() {
        MarketPricingService pricing = new MarketPricingService();
        MarketState extreme = new MarketState("berry_fog", 0.05, 100.0, 0.0,
                MarketEventType.HIGH_DEMAND, 5.0, 2.0, 30.0, TestFixtures.NOW);
        MarketQuote high = pricing.quote(TestFixtures.strain(), extreme, 100, 100, 100);
        assertEquals(30.0, high.unitPrice());
        assertTrue(high.clamped());

        MarketState depressed = new MarketState("berry_fog", 100.0, 0.05, 100_000.0,
                MarketEventType.OVERSUPPLY, 0.1, 2.0, 30.0, TestFixtures.NOW);
        MarketQuote low = pricing.quote(TestFixtures.strain(), depressed, 0, 0, 0);
        assertEquals(2.0, low.unitPrice());
    }

    @Test
    void repeatedSalesIncreaseSupplyPressure() {
        MarketPricingService pricing = new MarketPricingService();
        MarketState initial = MarketState.balanced("berry_fog", 1.0, 100.0, TestFixtures.NOW);
        double before = pricing.quote(TestFixtures.strain(), initial, 70, 100, 50).unitPrice();
        MarketState afterSale = pricing.recordSale(initial, 250.0,
                TestFixtures.NOW.plus(Duration.ofHours(1)));
        double after = pricing.quote(TestFixtures.strain(), afterSale, 70, 100, 50).unitPrice();
        assertTrue(after < before);
        assertTrue(afterSale.serverSalesWeight() >= 250.0);
    }

    @Test
    void dailyTradeLimitsRejectDuplicatesAndExcessPayout() {
        LocalDate day = LocalDate.of(2026, 7, 26);
        ProductBatch batch = TestFixtures.driedBatch();
        TradeLimitService service = new TradeLimitService();
        TradeLimits limits = new TradeLimits(2, 20.0, 100.0);
        TradeDecision first = service.evaluate(batch, 5.0, 40.0, true,
                TradeLedger.empty(TestFixtures.OWNER, day), limits, day);
        assertTrue(first.accepted());
        TradeDecision duplicate = service.evaluate(batch, 2.0, 10.0, true,
                first.ledger(), limits, day);
        assertFalse(duplicate.accepted());
        assertEquals("batch-already-sold", duplicate.reasonCode());

        ProductBatch another = new ProductBatch(java.util.UUID.randomUUID(), batch.ownerUuid(),
                batch.strainComposition(), batch.geneticOrigin(), batch.freshWeight(), batch.dryWeight(),
                batch.qualityScore(), batch.qualityGrade(), batch.contamination(), batch.freshness(),
                batch.harvestTiming(), batch.dryingData(), batch.curingData(), batch.createdAt(),
                batch.signature(), batch.state());
        TradeDecision excessive = service.evaluate(another, 2.0, 70.0, true,
                first.ledger(), limits, day);
        assertFalse(excessive.accepted());
        assertEquals("payout-limit", excessive.reasonCode());
    }

    @Test
    void rejectsUnsignedOrWetProducts() {
        TradeLimitService service = new TradeLimitService();
        LocalDate day = LocalDate.of(2026, 7, 26);
        var ledger = TradeLedger.empty(TestFixtures.OWNER, day);
        assertEquals("invalid-signature", service.evaluate(TestFixtures.driedBatch(), 1, 1,
                false, ledger, new TradeLimits(2, 10, 100), day).reasonCode());
        assertEquals("batch-not-tradeable", service.evaluate(TestFixtures.freshBatch(), 1, 1,
                true, ledger, new TradeLimits(2, 10, 100), day).reasonCode());
    }
}
