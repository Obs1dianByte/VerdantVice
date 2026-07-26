package com.verdantvice.processing;

import com.verdantvice.common.DomainChecks;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

public final class MixtureService {
    public Mixture mix(UUID mixtureId, UUID ownerUuid, List<MixtureComponent> components,
                       int maximumComponents, double compatibility, double playerSkill,
                       RandomGenerator random, Instant now) {
        DomainChecks.required(mixtureId, "mixtureId");
        DomainChecks.required(ownerUuid, "ownerUuid");
        components = List.copyOf(DomainChecks.required(components, "components"));
        DomainChecks.range(maximumComponents, 2, 32, "maximumComponents");
        DomainChecks.range(compatibility, 0.0, 1.0, "compatibility");
        DomainChecks.range(playerSkill, 0.0, 100.0, "playerSkill");
        DomainChecks.required(random, "random");
        DomainChecks.required(now, "now");
        if (components.size() < 2 || components.size() > maximumComponents) {
            throw new IllegalArgumentException("Mixture component count is outside configured limits");
        }
        if (components.stream().anyMatch(component -> !component.product().ownerUuid().equals(ownerUuid))) {
            throw new IllegalArgumentException("All components must belong to the mixture owner");
        }

        Set<UUID> sources = new HashSet<>();
        Map<String, Double> weightedStrains = new HashMap<>();
        double totalWeight = 0.0;
        double weightedQuality = 0.0;
        double weightedStability = 0.0;
        for (MixtureComponent component : components) {
            if (!java.util.Collections.disjoint(sources, component.product().sourceBatchIds())) {
                throw new IllegalArgumentException("A source batch cannot be included more than once");
            }
            sources.addAll(component.product().sourceBatchIds());
            totalWeight += component.amount();
            weightedQuality += component.product().qualityScore() * component.amount();
            weightedStability += component.product().stability() * component.amount();
            component.product().strainComposition().forEach((strain, share) ->
                    weightedStrains.merge(strain, share * component.amount(), Double::sum));
        }
        double quality = weightedQuality / totalWeight;
        double baseStability = weightedStability / totalWeight;
        double randomStability = random.nextDouble(0.92, 1.01);
        double stability = DomainChecks.clamp(baseStability
                * (0.65 + compatibility * 0.30 + playerSkill / 2000.0) * randomStability, 0.0, 1.0);
        return new Mixture(mixtureId, ownerUuid, sources, weightedStrains, totalWeight, quality,
                QualityGrade.fromScore(quality), stability, now);
    }
}
