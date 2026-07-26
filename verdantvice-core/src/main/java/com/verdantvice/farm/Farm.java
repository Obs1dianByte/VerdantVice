package com.verdantvice.farm;

import com.verdantvice.common.DomainChecks;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public record Farm(
        UUID farmId,
        UUID ownerUuid,
        String name,
        FarmRegion region,
        int level,
        int plantLimit,
        int workerLimit,
        Set<UUID> linkedStorage,
        Set<UUID> linkedDryingRacks,
        Set<UUID> linkedCuringContainers,
        Optional<UUID> salaryStorage,
        Instant createdAt,
        FarmSettings settings,
        Map<UUID, FarmMember> members
) {
    public Farm {
        DomainChecks.required(farmId, "farmId");
        DomainChecks.required(ownerUuid, "ownerUuid");
        name = DomainChecks.nonBlank(name, "name");
        if (name.length() > 48) throw new IllegalArgumentException("Farm name exceeds 48 characters");
        DomainChecks.required(region, "region");
        level = DomainChecks.range(level, 1, 100, "level");
        plantLimit = DomainChecks.range(plantLimit, 1, 1_000_000, "plantLimit");
        workerLimit = DomainChecks.range(workerLimit, 0, 10_000, "workerLimit");
        linkedStorage = Set.copyOf(DomainChecks.required(linkedStorage, "linkedStorage"));
        linkedDryingRacks = Set.copyOf(DomainChecks.required(linkedDryingRacks, "linkedDryingRacks"));
        linkedCuringContainers = Set.copyOf(DomainChecks.required(linkedCuringContainers,
                "linkedCuringContainers"));
        salaryStorage = DomainChecks.required(salaryStorage, "salaryStorage");
        DomainChecks.required(createdAt, "createdAt");
        DomainChecks.required(settings, "settings");
        members = Map.copyOf(DomainChecks.required(members, "members"));
        FarmMember owner = members.get(ownerUuid);
        if (owner == null || owner.role() != FarmRole.OWNER) {
            throw new IllegalArgumentException("Farm owner must be present with OWNER role");
        }
        if (salaryStorage.isPresent() && !linkedStorage.contains(salaryStorage.get())) {
            throw new IllegalArgumentException("Salary storage must be linked to the farm");
        }
    }

    public boolean can(UUID playerUuid, FarmPermission permission) {
        FarmMember member = members.get(playerUuid);
        return member != null && member.can(permission);
    }
}
