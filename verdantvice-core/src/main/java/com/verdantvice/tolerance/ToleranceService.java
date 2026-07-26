package com.verdantvice.tolerance;

import com.verdantvice.common.DomainChecks;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public final class ToleranceService {
    public ToleranceState decay(ToleranceState state, Instant now, TolerancePolicy policy) {
        validate(state, now, policy);
        if (!policy.enabled()) {
            return new ToleranceState(state.playerUuid(), 0.0, state.lastUsedAt(), now);
        }
        double elapsedDays = Duration.between(state.lastUpdatedAt(), now).toMillis() / 86_400_000.0;
        double level = DomainChecks.clamp(state.level() - elapsedDays * policy.decayPerDay(), 0.0, 100.0);
        return new ToleranceState(state.playerUuid(), level, state.lastUsedAt(), now);
    }

    public ToleranceState recordUse(ToleranceState state, double dosePotency, Instant now,
                                    TolerancePolicy policy) {
        DomainChecks.range(dosePotency, 0.0, 3.0, "dosePotency");
        ToleranceState decayed = decay(state, now, policy);
        if (!policy.enabled()) return decayed;
        double gain = DomainChecks.clamp(policy.baseGainPerUse() * Math.max(0.25, dosePotency),
                policy.baseGainPerUse() * 0.25, policy.maximumGainPerUse());
        return new ToleranceState(state.playerUuid(),
                DomainChecks.clamp(decayed.level() + gain, 0.0, 100.0),
                Optional.of(now), now);
    }

    public double potencyMultiplier(ToleranceState state, TolerancePolicy policy) {
        return policy.enabled()
                ? 1.0 - (state.level() / 100.0) * policy.potencyReductionAtMaximum()
                : 1.0;
    }

    public double durationMultiplier(ToleranceState state, TolerancePolicy policy) {
        return policy.enabled()
                ? 1.0 - (state.level() / 100.0) * policy.durationReductionAtMaximum()
                : 1.0;
    }

    public double negativeRiskMultiplier(ToleranceState state, TolerancePolicy policy) {
        return policy.enabled()
                ? 1.0 - (state.level() / 100.0) * policy.negativeRiskReductionAtMaximum()
                : 1.0;
    }

    private static void validate(ToleranceState state, Instant now, TolerancePolicy policy) {
        DomainChecks.required(state, "state");
        DomainChecks.required(now, "now");
        DomainChecks.required(policy, "policy");
        if (now.isBefore(state.lastUpdatedAt())) {
            throw new IllegalArgumentException("now cannot precede lastUpdatedAt");
        }
    }
}
