package com.verdantvice.processing;

import com.verdantvice.common.DomainChecks;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

public record GroundProduct(
        UUID productId,
        UUID ownerUuid,
        Set<UUID> sourceBatchIds,
        Map<String, Double> strainComposition,
        double weight,
        double qualityScore,
        QualityGrade qualityGrade,
        double stability,
        Instant createdAt
) {
    public GroundProduct {
        DomainChecks.required(productId, "productId");
        DomainChecks.required(ownerUuid, "ownerUuid");
        sourceBatchIds = Set.copyOf(DomainChecks.required(sourceBatchIds, "sourceBatchIds"));
        if (sourceBatchIds.isEmpty()) throw new IllegalArgumentException("sourceBatchIds must not be empty");
        strainComposition = normalized(strainComposition);
        weight = DomainChecks.positive(weight, "weight");
        qualityScore = DomainChecks.range(qualityScore, 0.0, 100.0, "qualityScore");
        DomainChecks.required(qualityGrade, "qualityGrade");
        stability = DomainChecks.range(stability, 0.0, 1.0, "stability");
        DomainChecks.required(createdAt, "createdAt");
    }

    private static Map<String, Double> normalized(Map<String, Double> input) {
        DomainChecks.required(input, "strainComposition");
        if (input.isEmpty()) throw new IllegalArgumentException("strainComposition must not be empty");
        TreeMap<String, Double> values = new TreeMap<>();
        input.forEach((id, share) -> values.merge(DomainChecks.identifier(id, "strain id"),
                DomainChecks.positive(share, "share"), Double::sum));
        double total = values.values().stream().mapToDouble(Double::doubleValue).sum();
        values.replaceAll((id, share) -> share / total);
        return Collections.unmodifiableMap(values);
    }
}
