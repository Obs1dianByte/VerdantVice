package com.verdantvice.trade;

import com.verdantvice.common.DomainChecks;
import com.verdantvice.processing.BatchState;
import com.verdantvice.processing.ProductBatch;

import java.time.LocalDate;
import java.util.HashSet;

/**
 * Enforces daily caps and one sale per unique batch.
 */
public final class TradeLimitService {
    public TradeDecision evaluate(ProductBatch batch, double soldWeight, double payout,
                                  boolean authentic, TradeLedger existing, TradeLimits limits,
                                  LocalDate tradingDay) {
        boolean tradeable = batch.state() == BatchState.DRIED || batch.state() == BatchState.CURED;
        return evaluate(batch, soldWeight, batch.dryWeight(), payout, authentic, tradeable,
                existing, limits, tradingDay);
    }

    public TradeDecision evaluate(ProductBatch batch, double soldWeight, double availableWeight,
                                  double payout, boolean authentic, boolean tradeable,
                                  TradeLedger existing, TradeLimits limits, LocalDate tradingDay) {
        DomainChecks.required(batch, "batch");
        DomainChecks.range(soldWeight, 0.001, 1_000_000.0, "soldWeight");
        DomainChecks.range(payout, 0.0, 1_000_000_000.0, "payout");
        DomainChecks.required(existing, "existing");
        DomainChecks.required(limits, "limits");
        DomainChecks.required(tradingDay, "tradingDay");
        TradeLedger ledger = existing.tradingDay().equals(tradingDay)
                ? existing : TradeLedger.empty(existing.playerUuid(), tradingDay);
        if (!batch.ownerUuid().equals(ledger.playerUuid())) return rejected("not-owner", ledger);
        if (!authentic) return rejected("invalid-signature", ledger);
        if (!tradeable) return rejected("batch-not-tradeable", ledger);
        DomainChecks.range(availableWeight, 0.001, 1_000_000.0, "availableWeight");
        if (soldWeight > availableWeight) return rejected("insufficient-batch-weight", ledger);
        if (ledger.soldBatchIds().contains(batch.batchId())) return rejected("batch-already-sold", ledger);
        if (ledger.soldBatches() + 1 > limits.maximumBatches()) return rejected("batch-limit", ledger);
        if (ledger.soldWeight() + soldWeight > limits.maximumWeight()) return rejected("weight-limit", ledger);
        if (ledger.paidOut() + payout > limits.maximumPayout()) return rejected("payout-limit", ledger);
        HashSet<java.util.UUID> soldIds = new HashSet<>(ledger.soldBatchIds());
        soldIds.add(batch.batchId());
        TradeLedger updated = new TradeLedger(ledger.playerUuid(), tradingDay, ledger.soldBatches() + 1,
                ledger.soldWeight() + soldWeight, ledger.paidOut() + payout, soldIds);
        return new TradeDecision(true, "", payout, updated);
    }

    private static TradeDecision rejected(String reason, TradeLedger ledger) {
        return new TradeDecision(false, reason, 0.0, ledger);
    }
}
