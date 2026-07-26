package com.verdantvice.processing;

import com.verdantvice.common.DomainChecks;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

public record Mixture(
        UUID mixtureId,
        UUID ownerUuid,
        Set<UUID> sourceBatchIds,
        Map<String, Double> strainComposition,
        double totalWeight,
        double qualityScore,
        QualityGrade qualityGrade,
        double stability,
        Instant createdAt
) {
    public Mixture {
        DomainChecks.required(mixtureId, "mixtureId");
        DomainChecks.required(ownerUuid, "ownerUuid");
        sourceBatchIds = Set.copyOf(DomainChecks.required(sourceBatchIds, "sourceBatchIds"));
        if (sourceBatchIds.isEmpty()) throw new IllegalArgumentException("sourceBatchIds must not be empty");
        strainComposition = normalize(strainComposition);
        totalWeight = DomainChecks.positive(totalWeight, "totalWeight");
        qualityScore = DomainChecks.range(qualityScore, 0.0, 100.0, "qualityScore");
        DomainChecks.required(qualityGrade, "qualityGrade");
        stability = DomainChecks.range(stability, 0.0, 1.0, "stability");
        DomainChecks.required(createdAt, "createdAt");
    }

    private static Map<String, Double> normalize(Map<String, Double> input) {
        DomainChecks.required(input, "strainComposition");
        if (input.isEmpty()) throw new IllegalArgumentException("strainComposition must not be empty");
        TreeMap<String, Double> sorted = new TreeMap<>();
        input.forEach((id, share) -> sorted.merge(DomainChecks.identifier(id, "strain id"),
                DomainChecks.positive(share, "share"), Double::sum));
        double total = sorted.values().stream().mapToDouble(Double::doubleValue).sum();
        sorted.replaceAll((id, share) -> share / total);
        return Collections.unmodifiableMap(sorted);
    }
}
