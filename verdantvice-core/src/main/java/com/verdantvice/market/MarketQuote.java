package com.verdantvice.market;

import com.verdantvice.common.DomainChecks;

public record MarketQuote(
        String strainId,
        double unitPrice,
        double marketFactor,
        double qualityFactor,
        double reputationFactor,
        boolean clamped
) {
    public MarketQuote {
        strainId = DomainChecks.identifier(strainId, "strainId");
        unitPrice = DomainChecks.positive(unitPrice, "unitPrice");
        marketFactor = DomainChecks.positive(marketFactor, "marketFactor");
        qualityFactor = DomainChecks.positive(qualityFactor, "qualityFactor");
        reputationFactor = DomainChecks.positive(reputationFactor, "reputationFactor");
    }
}
