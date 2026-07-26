package com.verdantvice.market;

import java.time.Instant;
import java.util.Objects;

public record MarketEventWindow(MarketEventType type, double multiplier, Instant expiresAt) {
    public MarketEventWindow {
        Objects.requireNonNull(type, "type");
        if (!Double.isFinite(multiplier) || multiplier < 0.1 || multiplier > 5.0) {
            throw new IllegalArgumentException("multiplier must be between 0.1 and 5");
        }
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public boolean activeAt(Instant now) {
        return type != MarketEventType.NONE && now.isBefore(expiresAt);
    }

    public static MarketEventWindow none(Instant now) {
        return new MarketEventWindow(MarketEventType.NONE, 1.0, now);
    }
}
