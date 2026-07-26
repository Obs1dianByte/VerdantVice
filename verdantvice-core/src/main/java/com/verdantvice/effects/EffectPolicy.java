package com.verdantvice.effects;

import com.verdantvice.common.DomainChecks;

import java.time.Duration;

public record EffectPolicy(
        Duration baseDuration,
        Duration maximumDuration,
        double strongEffectThreshold,
        double greenOutThreshold
) {
    public EffectPolicy {
        baseDuration = DomainChecks.positive(baseDuration, "baseDuration");
        maximumDuration = DomainChecks.positive(maximumDuration, "maximumDuration");
        if (maximumDuration.compareTo(baseDuration) < 0) {
            throw new IllegalArgumentException("maximumDuration must not be shorter than baseDuration");
        }
        strongEffectThreshold = DomainChecks.range(strongEffectThreshold, 0.0, 3.0,
                "strongEffectThreshold");
        greenOutThreshold = DomainChecks.range(greenOutThreshold, strongEffectThreshold, 5.0,
                "greenOutThreshold");
    }

    public static EffectPolicy defaults() {
        return new EffectPolicy(Duration.ofMinutes(2), Duration.ofMinutes(8), 1.0, 1.75);
    }
}
