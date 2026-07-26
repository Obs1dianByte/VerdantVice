package com.verdantvice.market;

import com.verdantvice.common.DomainChecks;
import com.verdantvice.strain.Strain;

import java.time.Duration;
import java.time.Instant;

public final class MarketPricingService {
    public MarketQuote quote(Strain strain, MarketState state, double quality, double freshness,
                             double reputation) {
        DomainChecks.required(strain, "strain");
        DomainChecks.required(state, "state");
        if (!strain.id().equals(state.strainId())) {
            throw new IllegalArgumentException("Market state belongs to a different strain");
        }
        DomainChecks.range(quality, 0.0, 100.0, "quality");
        DomainChecks.range(freshness, 0.0, 100.0, "freshness");
        DomainChecks.range(reputation, 0.0, 100.0, "reputation");
        double supplyDemand = DomainChecks.clamp(
                Math.sqrt(state.demandIndex() / state.supplyIndex()), 0.45, 2.25);
        double salesPressure = 1.0 / (1.0 + Math.log1p(state.serverSalesWeight()) / 25.0);
        double marketFactor = supplyDemand * state.eventMultiplier() * salesPressure;
        double qualityFactor = 0.35 + quality / 100.0 * 1.05;
        double freshnessFactor = 0.55 + freshness / 100.0 * 0.45;
        double reputationFactor = 0.9 + reputation / 500.0;
        double raw = strain.baseMarketPrice() * strain.rarity().marketMultiplier() * marketFactor
                * qualityFactor * freshnessFactor * reputationFactor;
        double clamped = DomainChecks.clamp(raw, state.minimumPrice(), state.maximumPrice());
        return new MarketQuote(strain.id(), clamped, marketFactor, qualityFactor * freshnessFactor,
                reputationFactor, Double.compare(raw, clamped) != 0);
    }

    public MarketState recordSale(MarketState state, double soldWeight, Instant now) {
        DomainChecks.required(state, "state");
        DomainChecks.range(soldWeight, 0.001, 1_000_000.0, "soldWeight");
        DomainChecks.required(now, "now");
        if (now.isBefore(state.lastUpdatedAt())) {
            throw new IllegalArgumentException("now cannot precede lastUpdatedAt");
        }
        double elapsedDays = Duration.between(state.lastUpdatedAt(), now).toMillis() / 86_400_000.0;
        double decayedSales = state.serverSalesWeight() * Math.pow(0.75, elapsedDays);
        double supply = Math.max(0.05, 1.0 + (state.supplyIndex() - 1.0) * Math.pow(0.82, elapsedDays)
                + soldWeight / 100.0);
        double demand = Math.max(0.05, 1.0 + (state.demandIndex() - 1.0) * Math.pow(0.9, elapsedDays)
                - Math.min(0.35, soldWeight / 500.0));
        return new MarketState(state.strainId(), supply, demand, decayedSales + soldWeight, state.event(),
                state.eventMultiplier(), state.minimumPrice(), state.maximumPrice(), now);
    }

    public MarketState applyEvent(MarketState state, MarketEventType event, double customMultiplier,
                                  Instant now) {
        DomainChecks.required(state, "state");
        DomainChecks.required(event, "event");
        DomainChecks.required(now, "now");
        return new MarketState(state.strainId(), state.supplyIndex(), state.demandIndex(),
                state.serverSalesWeight(), event, customMultiplier, state.minimumPrice(),
                state.maximumPrice(), now);
    }
}
