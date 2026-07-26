package com.verdantvice.effects;

import com.verdantvice.common.DomainChecks;

import java.time.Duration;

public record AppliedEffect(EffectType type, int amplifier, Duration duration, double intensity) {
    public AppliedEffect {
        DomainChecks.required(type, "type");
        amplifier = DomainChecks.range(amplifier, 0, 4, "amplifier");
        duration = DomainChecks.positive(duration, "duration");
        intensity = DomainChecks.range(intensity, 0.0, 3.0, "intensity");
    }
}
