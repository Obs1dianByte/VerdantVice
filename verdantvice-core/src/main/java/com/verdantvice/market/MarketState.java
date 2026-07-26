package com.verdantvice.market;

import com.verdantvice.common.DomainChecks;

import java.time.Instant;

public record MarketState(
        String strainId,
        double supplyIndex,
        double demandIndex,
        double serverSalesWeight,
        MarketEventType event,
        double eventMultiplier,
        double minimumPrice,
        double maximumPrice,
        Instant lastUpdatedAt
) {
    public MarketState {
        strainId = DomainChecks.identifier(strainId, "strainId");
        supplyIndex = DomainChecks.range(supplyIndex, 0.05, 100.0, "supplyIndex");
        demandIndex = DomainChecks.range(demandIndex, 0.05, 100.0, "demandIndex");
        serverSalesWeight = DomainChecks.range(serverSalesWeight, 0.0, 1_000_000_000.0,
                "serverSalesWeight");
        DomainChecks.required(event, "event");
        eventMultiplier = DomainChecks.range(eventMultiplier, 0.1, 5.0, "eventMultiplier");
        minimumPrice = DomainChecks.positive(minimumPrice, "minimumPrice");
        maximumPrice = DomainChecks.positive(maximumPrice, "maximumPrice");
        if (maximumPrice < minimumPrice) {
            throw new IllegalArgumentException("maximumPrice cannot be lower than minimumPrice");
        }
        DomainChecks.required(lastUpdatedAt, "lastUpdatedAt");
    }

    public static MarketState balanced(String strainId, double minimumPrice, double maximumPrice, Instant now) {
        return new MarketState(strainId, 1.0, 1.0, 0.0, MarketEventType.NONE, 1.0,
                minimumPrice, maximumPrice, now);
    }
}
