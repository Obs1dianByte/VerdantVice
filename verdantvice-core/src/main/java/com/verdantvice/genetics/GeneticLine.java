package com.verdantvice.genetics;

import com.verdantvice.common.DomainChecks;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record GeneticLine(
        UUID id,
        String name,
        Set<String> parentStrainIds,
        UUID creatorUuid,
        int generation,
        Map<String, Double> dominantTraits,
        Set<String> colors,
        Set<String> effects,
        double stability,
        Instant createdAt
) {
    public GeneticLine {
        DomainChecks.required(id, "id");
        name = DomainChecks.nonBlank(name, "name");
        parentStrainIds = Set.copyOf(DomainChecks.required(parentStrainIds, "parentStrainIds"));
        if (parentStrainIds.size() < 2) {
            throw new IllegalArgumentException("At least two parent strains are required");
        }
        DomainChecks.required(creatorUuid, "creatorUuid");
        if (generation < 1) throw new IllegalArgumentException("generation must be positive");
        dominantTraits = Map.copyOf(DomainChecks.required(dominantTraits, "dominantTraits"));
        dominantTraits.forEach((key, value) -> {
            DomainChecks.nonBlank(key, "dominant trait");
            DomainChecks.range(value, 0.0, 2.0, "dominant trait value");
        });
        colors = Set.copyOf(DomainChecks.required(colors, "colors"));
        effects = Set.copyOf(DomainChecks.required(effects, "effects"));
        stability = DomainChecks.range(stability, 0.0, 1.0, "stability");
        DomainChecks.required(createdAt, "createdAt");
    }
}
