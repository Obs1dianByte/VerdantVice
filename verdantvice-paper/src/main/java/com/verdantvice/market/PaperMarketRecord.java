package com.verdantvice.market;

import java.util.Objects;

public record PaperMarketRecord(
        String strainId,
        double basePrice,
        double currentPrice,
        MarketState state,
        MarketEventWindow event,
        long version
) {
    public PaperMarketRecord {
        Objects.requireNonNull(strainId, "strainId");
        if (!Double.isFinite(basePrice) || basePrice <= 0.0
                || !Double.isFinite(currentPrice) || currentPrice <= 0.0) {
            throw new IllegalArgumentException("Market prices must be positive");
        }
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(event, "event");
        if (!strainId.equals(state.strainId())) {
            throw new IllegalArgumentException("Market state belongs to another strain");
        }
        if (version < 0L) throw new IllegalArgumentException("version cannot be negative");
    }
}
