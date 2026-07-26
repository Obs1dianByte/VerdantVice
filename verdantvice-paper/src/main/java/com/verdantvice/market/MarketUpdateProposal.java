package com.verdantvice.market;

public record MarketUpdateProposal(PaperMarketRecord previous, PaperMarketRecord replacement) {
    public MarketUpdateProposal {
        java.util.Objects.requireNonNull(previous, "previous");
        java.util.Objects.requireNonNull(replacement, "replacement");
    }
}
