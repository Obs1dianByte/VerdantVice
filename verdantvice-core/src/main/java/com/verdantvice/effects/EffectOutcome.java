package com.verdantvice.effects;

import java.util.List;

public record EffectOutcome(
        boolean accepted,
        String rejectionCode,
        List<AppliedEffect> effects,
        double effectivePotency,
        boolean strong,
        boolean greenOut
) {
    public EffectOutcome {
        rejectionCode = java.util.Objects.requireNonNull(rejectionCode, "rejectionCode");
        effects = List.copyOf(java.util.Objects.requireNonNull(effects, "effects"));
        if (effectivePotency < 0.0 || !Double.isFinite(effectivePotency)) {
            throw new IllegalArgumentException("effectivePotency must be non-negative and finite");
        }
        if (!accepted && rejectionCode.isBlank()) {
            throw new IllegalArgumentException("Rejected outcome requires a rejection code");
        }
    }

    public static EffectOutcome rejected(String code) {
        return new EffectOutcome(false, code, List.of(), 0.0, false, false);
    }
}
