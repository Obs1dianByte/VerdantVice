package com.verdantvice.effects;

import com.verdantvice.common.DomainChecks;

import java.util.EnumMap;
import java.util.Map;

/**
 * Effect weights in the inclusive range 0..1.
 */
public record EffectProfile(Map<EffectType, Double> weights) {
    public EffectProfile {
        DomainChecks.required(weights, "weights");
        EnumMap<EffectType, Double> copy = new EnumMap<>(EffectType.class);
        weights.forEach((type, weight) -> copy.put(
                DomainChecks.required(type, "effect type"),
                DomainChecks.range(weight, 0.0, 1.0, "effect weight")));
        weights = Map.copyOf(copy);
    }

    public static EffectProfile empty() {
        return new EffectProfile(Map.of());
    }

    public double weight(EffectType type) {
        return weights.getOrDefault(type, 0.0);
    }

    public EffectProfile scaled(double multiplier) {
        DomainChecks.range(multiplier, 0.0, 10.0, "multiplier");
        EnumMap<EffectType, Double> scaled = new EnumMap<>(EffectType.class);
        weights.forEach((type, weight) -> scaled.put(type, DomainChecks.clamp(weight * multiplier, 0.0, 1.0)));
        return new EffectProfile(scaled);
    }
}
