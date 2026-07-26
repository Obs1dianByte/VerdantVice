package com.verdantvice.processing;

import com.verdantvice.common.DomainChecks;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class RollingService {
    private static final List<RollingStep> EXPECTED_STEPS = List.of(
            RollingStep.PAPER, RollingStep.FILTER, RollingStep.ADD_PRODUCT, RollingStep.SHAPE, RollingStep.SEAL);

    public RolledProduct roll(UUID productId, Mixture mixture, RollingAttempt attempt,
                              double amount, double playerSkill, Instant now) {
        DomainChecks.required(productId, "productId");
        DomainChecks.required(mixture, "mixture");
        DomainChecks.required(attempt, "attempt");
        DomainChecks.range(amount, 0.01, Math.min(100.0, mixture.totalWeight()), "amount");
        DomainChecks.range(playerSkill, 0.0, 100.0, "playerSkill");
        DomainChecks.required(now, "now");

        RollingResult result = classify(attempt, playerSkill);
        double retention = switch (result) {
            case FAILED -> 0.0;
            case SPILLED -> 0.35;
            case CROOKED -> 0.72;
            case LOOSE -> 0.82;
            case NORMAL -> 0.91;
            case GOOD -> 0.96;
            case PERFECT -> 1.0;
            case OVERFILLED -> 0.78;
        };
        double qualityFactor = switch (result) {
            case FAILED -> 0.0;
            case SPILLED -> 0.45;
            case CROOKED -> 0.65;
            case LOOSE -> 0.78;
            case NORMAL -> 0.9;
            case GOOD -> 1.0;
            case PERFECT -> 1.12;
            case OVERFILLED -> 0.82;
        };
        double burn = DomainChecks.clamp(qualityFactor * mixture.stability(), 0.0, 1.0);
        return new RolledProduct(productId, mixture.ownerUuid(), mixture.mixtureId(), result, amount,
                amount * retention, qualityFactor, qualityFactor, burn, 1.0 - burn,
                qualityFactor * (0.7 + mixture.qualityScore() / 333.333), now);
    }

    public RollingResult classify(RollingAttempt attempt, double playerSkill) {
        DomainChecks.required(attempt, "attempt");
        DomainChecks.range(playerSkill, 0.0, 100.0, "playerSkill");
        if (!attempt.performedSteps().equals(EXPECTED_STEPS)) return RollingResult.FAILED;
        double averageError = attempt.timingErrorsMillis().stream().mapToLong(Long::longValue)
                .average().orElse(Double.POSITIVE_INFINITY);
        if (attempt.fillRatio() > 1.25) return RollingResult.OVERFILLED;
        if (attempt.fillRatio() < 0.45 || averageError > 900.0) return RollingResult.SPILLED;
        double adjustedError = averageError * (1.0 - playerSkill / 250.0);
        if (adjustedError > 600.0) return RollingResult.CROOKED;
        if (adjustedError > 400.0) return RollingResult.LOOSE;
        if (adjustedError <= 90.0 && attempt.fillRatio() >= 0.9 && attempt.fillRatio() <= 1.05) {
            return RollingResult.PERFECT;
        }
        if (adjustedError <= 240.0) return RollingResult.GOOD;
        return RollingResult.NORMAL;
    }
}
