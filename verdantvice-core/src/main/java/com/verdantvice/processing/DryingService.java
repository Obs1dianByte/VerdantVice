package com.verdantvice.processing;

import com.verdantvice.common.DomainChecks;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Idempotent timestamp calculation: output is derived from values captured at process start.
 */
public final class DryingService {
    public ProductBatch start(ProductBatch batch, Instant now, Duration targetDuration,
                              double cleanliness, double stability, double speed) {
        DomainChecks.required(batch, "batch");
        DomainChecks.required(now, "now");
        if (batch.state() != BatchState.FRESH || batch.dryingData().isPresent()) {
            throw new IllegalStateException("Only a fresh batch can start drying");
        }
        DryingData data = new DryingData(DryingStage.FRESH, now, now, targetDuration, cleanliness,
                stability, speed, batch.qualityScore(), batch.contamination());
        return batch.transition(0.0, batch.qualityScore(), batch.contamination(), batch.freshness(),
                Optional.of(data), batch.curingData(), BatchState.DRYING);
    }

    public ProductBatch update(ProductBatch batch, Instant now, boolean offlineProcessing) {
        DomainChecks.required(batch, "batch");
        DomainChecks.required(now, "now");
        DryingData data = batch.dryingData()
                .orElseThrow(() -> new IllegalStateException("Batch has no drying process"));
        if (now.isBefore(data.lastUpdatedAt())) {
            throw new IllegalArgumentException("now cannot precede last drying update");
        }
        Instant effectiveNow = offlineProcessing ? now : data.lastUpdatedAt();
        double progress = data.progressAt(effectiveNow);
        DryingStage stage = stage(progress);
        double dryFraction = progress <= 1.0
                ? 1.0 - 0.78 * Math.max(0.0, progress)
                : 0.22 * (1.0 - Math.min(0.18, (progress - 1.0) * 0.12));
        double dryWeight = DomainChecks.clamp(batch.freshWeight() * dryFraction, 0.0, batch.freshWeight());

        double equipmentPenalty = (100.0 - data.rackCleanliness()) * 0.035
                + (100.0 - data.rackStability()) * 0.025;
        double timingPenalty = progress < 0.75 ? (0.75 - progress) * 8.0
                : progress > 1.15 ? (progress - 1.15) * 24.0 : 0.0;
        double quality = DomainChecks.clamp(data.startingQuality() - equipmentPenalty - timingPenalty,
                0.0, 100.0);
        double contamination = DomainChecks.clamp(data.startingContamination()
                + (100.0 - data.rackCleanliness()) * Math.min(progress, 2.0) * 0.045, 0.0, 100.0);
        double freshness = DomainChecks.clamp(100.0 - Math.max(0.0, progress - 1.0) * 22.0, 0.0, 100.0);
        DryingData updatedData = new DryingData(stage, data.startedAt(), now, data.targetDuration(),
                data.rackCleanliness(), data.rackStability(), data.rackSpeed(), data.startingQuality(),
                data.startingContamination());
        BatchState state = switch (stage) {
            case READY, OVERDRIED -> BatchState.DRIED;
            case RUINED -> BatchState.SPOILED;
            default -> BatchState.DRYING;
        };
        if (stage == DryingStage.RUINED) {
            quality = 0.0;
            freshness = 0.0;
        }
        return batch.transition(dryWeight, quality, contamination, freshness, Optional.of(updatedData),
                batch.curingData(), state);
    }

    private static DryingStage stage(double progress) {
        if (progress < 0.08) return DryingStage.FRESH;
        if (progress < 0.25) return DryingStage.WET;
        if (progress < 0.48) return DryingStage.DAMP;
        if (progress < 0.72) return DryingStage.PARTIALLY_DRY;
        if (progress < 1.0) return DryingStage.ALMOST_READY;
        if (progress < 1.28) return DryingStage.READY;
        if (progress < 1.8) return DryingStage.OVERDRIED;
        return DryingStage.RUINED;
    }
}
