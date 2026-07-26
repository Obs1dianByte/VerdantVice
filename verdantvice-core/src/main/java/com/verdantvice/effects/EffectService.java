package com.verdantvice.effects;

import com.verdantvice.common.DomainChecks;
import com.verdantvice.tolerance.TolerancePolicy;
import com.verdantvice.tolerance.ToleranceService;
import com.verdantvice.tolerance.ToleranceState;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

public final class EffectService {
    private final ToleranceService toleranceService;

    public EffectService(ToleranceService toleranceService) {
        this.toleranceService = DomainChecks.required(toleranceService, "toleranceService");
    }

    public EffectOutcome calculate(EffectProfile positiveProfile, EffectProfile negativeProfile,
                                   double quality, double amount, double productStability,
                                   ToleranceState tolerance, TolerancePolicy tolerancePolicy,
                                   PlayerCondition condition, EffectPolicy effectPolicy,
                                   RandomGenerator random) {
        DomainChecks.required(positiveProfile, "positiveProfile");
        DomainChecks.required(negativeProfile, "negativeProfile");
        DomainChecks.range(quality, 0.0, 100.0, "quality");
        DomainChecks.range(amount, 0.01, 3.0, "amount");
        DomainChecks.range(productStability, 0.0, 1.0, "productStability");
        DomainChecks.required(tolerance, "tolerance");
        DomainChecks.required(tolerancePolicy, "tolerancePolicy");
        DomainChecks.required(condition, "condition");
        DomainChecks.required(effectPolicy, "effectPolicy");
        DomainChecks.required(random, "random");

        double rawPotency = amount * (0.35 + quality / 100.0 * 0.65) * (0.55 + productStability * 0.45);
        if (condition.activeStrongEffect() && rawPotency >= effectPolicy.strongEffectThreshold()) {
            return EffectOutcome.rejected("active-strong-effect");
        }
        double potency = rawPotency * toleranceService.potencyMultiplier(tolerance, tolerancePolicy);
        double durationMultiplier = toleranceService.durationMultiplier(tolerance, tolerancePolicy);
        double negativeRisk = toleranceService.negativeRiskMultiplier(tolerance, tolerancePolicy);
        List<AppliedEffect> effects = new ArrayList<>();
        addEffects(effects, positiveProfile, potency, durationMultiplier, 1.0, effectPolicy, random);
        double conditionRisk = 1.0 + (100.0 - condition.health()) / 250.0
                + (100.0 - condition.hunger()) / 350.0 + condition.stress() / 500.0;
        addEffects(effects, negativeProfile, potency, durationMultiplier,
                negativeRisk * conditionRisk * (1.2 - productStability * 0.4), effectPolicy, random);
        boolean strong = potency >= effectPolicy.strongEffectThreshold();
        boolean greenOut = potency >= effectPolicy.greenOutThreshold()
                && random.nextDouble() < Math.min(0.8, (potency - effectPolicy.greenOutThreshold()) * 0.5
                * negativeRisk);
        if (greenOut && effects.stream().noneMatch(effect -> effect.type() == EffectType.GREEN_OUT)) {
            effects.add(effect(EffectType.GREEN_OUT, 1.0, durationMultiplier, effectPolicy));
        }
        return new EffectOutcome(true, "", effects, potency, strong, greenOut);
    }

    private static void addEffects(List<AppliedEffect> output, EffectProfile profile, double potency,
                                   double durationMultiplier, double chanceMultiplier,
                                   EffectPolicy policy, RandomGenerator random) {
        profile.weights().forEach((type, weight) -> {
            double intensity = weight * potency;
            double chance = DomainChecks.clamp(weight * potency * chanceMultiplier, 0.0, 1.0);
            if (intensity > 0.02 && random.nextDouble() <= chance) {
                output.add(effect(type, intensity, durationMultiplier, policy));
            }
        });
    }

    private static AppliedEffect effect(EffectType type, double intensity, double durationMultiplier,
                                        EffectPolicy policy) {
        int amplifier = Math.min(4, Math.max(0, (int) Math.floor(intensity)));
        long millis = Math.max(1L, Math.round(policy.baseDuration().toMillis()
                * DomainChecks.clamp(intensity, 0.25, 3.0) * durationMultiplier));
        Duration duration = Duration.ofMillis(Math.min(millis, policy.maximumDuration().toMillis()));
        return new AppliedEffect(type, amplifier, duration, DomainChecks.clamp(intensity, 0.0, 3.0));
    }
}
