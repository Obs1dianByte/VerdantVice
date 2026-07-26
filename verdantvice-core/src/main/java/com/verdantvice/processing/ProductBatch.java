package com.verdantvice.processing;

import com.verdantvice.common.DomainChecks;
import com.verdantvice.harvest.HarvestTiming;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Immutable, uniquely identifiable product batch.
 */
public record ProductBatch(
        UUID batchId,
        UUID ownerUuid,
        Map<String, Double> strainComposition,
        Set<UUID> geneticOrigin,
        double freshWeight,
        double dryWeight,
        double qualityScore,
        QualityGrade qualityGrade,
        double contamination,
        double freshness,
        HarvestTiming harvestTiming,
        Optional<DryingData> dryingData,
        Optional<CuringData> curingData,
        Instant createdAt,
        String signature,
        BatchState state
) {
    public ProductBatch {
        DomainChecks.required(batchId, "batchId");
        DomainChecks.required(ownerUuid, "ownerUuid");
        strainComposition = normalizedComposition(strainComposition);
        geneticOrigin = Set.copyOf(DomainChecks.required(geneticOrigin, "geneticOrigin"));
        if (geneticOrigin.isEmpty()) throw new IllegalArgumentException("geneticOrigin must not be empty");
        freshWeight = DomainChecks.range(freshWeight, 0.0, 1_000_000.0, "freshWeight");
        dryWeight = DomainChecks.range(dryWeight, 0.0, freshWeight, "dryWeight");
        qualityScore = DomainChecks.range(qualityScore, 0.0, 100.0, "qualityScore");
        DomainChecks.required(qualityGrade, "qualityGrade");
        contamination = DomainChecks.range(contamination, 0.0, 100.0, "contamination");
        freshness = DomainChecks.range(freshness, 0.0, 100.0, "freshness");
        DomainChecks.required(harvestTiming, "harvestTiming");
        dryingData = DomainChecks.required(dryingData, "dryingData");
        curingData = DomainChecks.required(curingData, "curingData");
        DomainChecks.required(createdAt, "createdAt");
        signature = DomainChecks.required(signature, "signature");
        DomainChecks.required(state, "state");
    }

    public static ProductBatch fresh(UUID batchId, UUID ownerUuid, String strainId, UUID geneticsId,
                                     double weight, double qualityScore, double contamination,
                                     HarvestTiming timing, Instant createdAt) {
        return new ProductBatch(batchId, ownerUuid, Map.of(strainId, 1.0), Set.of(geneticsId),
                weight, 0.0, qualityScore, QualityGrade.fromScore(qualityScore), contamination,
                100.0, timing, Optional.empty(), Optional.empty(), createdAt, "", BatchState.FRESH);
    }

    public ProductBatch transition(double newDryWeight, double newQuality, double newContamination,
                                   double newFreshness, Optional<DryingData> newDrying,
                                   Optional<CuringData> newCuring, BatchState newState) {
        return new ProductBatch(batchId, ownerUuid, strainComposition, geneticOrigin, freshWeight,
                newDryWeight, newQuality, QualityGrade.fromScore(newQuality), newContamination,
                newFreshness, harvestTiming, newDrying, newCuring, createdAt, "", newState);
    }

    public ProductBatch withSignature(String newSignature) {
        return new ProductBatch(batchId, ownerUuid, strainComposition, geneticOrigin, freshWeight,
                dryWeight, qualityScore, qualityGrade, contamination, freshness, harvestTiming,
                dryingData, curingData, createdAt, DomainChecks.required(newSignature, "newSignature"), state);
    }

    private static Map<String, Double> normalizedComposition(Map<String, Double> input) {
        DomainChecks.required(input, "strainComposition");
        if (input.isEmpty()) throw new IllegalArgumentException("strainComposition must not be empty");
        TreeMap<String, Double> sorted = new TreeMap<>();
        input.forEach((id, share) -> sorted.merge(
                DomainChecks.identifier(id, "strain id"),
                DomainChecks.positive(share, "composition share"),
                Double::sum));
        double total = sorted.values().stream().mapToDouble(Double::doubleValue).sum();
        sorted.replaceAll((id, share) -> share / total);
        return Collections.unmodifiableMap(sorted);
    }
}
