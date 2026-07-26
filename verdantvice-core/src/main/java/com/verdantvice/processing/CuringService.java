package com.verdantvice.processing;

import com.verdantvice.common.DomainChecks;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public final class CuringService {
    public ProductBatch start(ProductBatch batch, Instant now, Duration targetDuration,
                              double cleanliness, double stability) {
        DomainChecks.required(batch, "batch");
        DomainChecks.required(now, "now");
        if (batch.state() != BatchState.DRIED || batch.curingData().isPresent()) {
            throw new IllegalStateException("Only a dried, unprocessed batch can start curing");
        }
        CuringData data = new CuringData(CuringStage.NOT_STARTED, now, now, targetDuration, cleanliness,
                stability, batch.qualityScore(), batch.contamination(), batch.freshness());
        return batch.transition(batch.dryWeight(), batch.qualityScore(), batch.contamination(),
                batch.freshness(), batch.dryingData(), Optional.of(data), BatchState.CURING);
    }

    public ProductBatch update(ProductBatch batch, Instant now, boolean offlineProcessing) {
        DomainChecks.required(batch, "batch");
        DomainChecks.required(now, "now");
        CuringData data = batch.curingData()
                .orElseThrow(() -> new IllegalStateException("Batch has no curing process"));
        if (now.isBefore(data.lastUpdatedAt())) {
            throw new IllegalArgumentException("now cannot precede last curing update");
        }
        Instant effectiveNow = offlineProcessing ? now : data.lastUpdatedAt();
        double progress = data.progressAt(effectiveNow);
        CuringStage stage = stage(progress);
        double equipmentFactor = (data.containerCleanliness() + data.containerStability()) / 200.0;
        double improvement = Math.min(progress, 1.0) * 9.0 * equipmentFactor;
        double overagePenalty = progress > 1.35 ? (progress - 1.35) * 20.0 : 0.0;
        double quality = DomainChecks.clamp(data.startingQuality() + improvement - overagePenalty,
                0.0, 100.0);
        double contamination = DomainChecks.clamp(data.startingContamination()
                + (100.0 - data.containerCleanliness()) * Math.min(progress, 2.0) * 0.025, 0.0, 100.0);
        double freshness = DomainChecks.clamp(data.startingFreshness()
                - Math.max(0.0, progress - 1.0) * 12.0, 0.0, 100.0);
        BatchState state = switch (stage) {
            case OPTIMAL, OVERAGED -> BatchState.CURED;
            case DAMAGED -> BatchState.SPOILED;
            default -> BatchState.CURING;
        };
        if (stage == CuringStage.DAMAGED) {
            quality = 0.0;
            freshness = 0.0;
        }
        CuringData updated = new CuringData(stage, data.startedAt(), now, data.targetDuration(),
                data.containerCleanliness(), data.containerStability(), data.startingQuality(),
                data.startingContamination(), data.startingFreshness());
        return batch.transition(batch.dryWeight(), quality, contamination, freshness, batch.dryingData(),
                Optional.of(updated), state);
    }

    private static CuringStage stage(double progress) {
        if (progress < 0.2) return CuringStage.EARLY;
        if (progress < 0.85) return CuringStage.DEVELOPING;
        if (progress < 1.35) return CuringStage.OPTIMAL;
        if (progress < 2.0) return CuringStage.OVERAGED;
        return CuringStage.DAMAGED;
    }
}
