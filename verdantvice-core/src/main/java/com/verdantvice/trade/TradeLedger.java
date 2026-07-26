package com.verdantvice.trade;

import com.verdantvice.common.DomainChecks;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

public record TradeLedger(
        UUID playerUuid,
        LocalDate tradingDay,
        int soldBatches,
        double soldWeight,
        double paidOut,
        Set<UUID> soldBatchIds
) {
    public TradeLedger {
        DomainChecks.required(playerUuid, "playerUuid");
        DomainChecks.required(tradingDay, "tradingDay");
        if (soldBatches < 0) throw new IllegalArgumentException("soldBatches must not be negative");
        DomainChecks.range(soldWeight, 0.0, 1_000_000_000.0, "soldWeight");
        DomainChecks.range(paidOut, 0.0, 1_000_000_000_000.0, "paidOut");
        soldBatchIds = Set.copyOf(DomainChecks.required(soldBatchIds, "soldBatchIds"));
        if (soldBatches != soldBatchIds.size()) {
            throw new IllegalArgumentException("soldBatches must equal the number of unique sold batch ids");
        }
    }

    public static TradeLedger empty(UUID playerUuid, LocalDate day) {
        return new TradeLedger(playerUuid, day, 0, 0.0, 0.0, Set.of());
    }
}
