package com.verdantvice.farm;

import com.verdantvice.common.DomainChecks;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class FarmService {
    public Farm create(UUID farmId, UUID ownerUuid, String name, FarmRegion region,
                       int plantLimit, int workerLimit, FarmSettings settings,
                       Collection<Farm> existingFarms, Instant now) {
        DomainChecks.required(existingFarms, "existingFarms");
        if (existingFarms.stream().anyMatch(existing -> existing.region().overlaps(region))) {
            throw new IllegalArgumentException("Farm region overlaps an existing farm");
        }
        FarmMember owner = new FarmMember(ownerUuid, FarmRole.OWNER, java.util.Set.of(),
                java.util.Set.of(), now);
        return new Farm(farmId, ownerUuid, name, region, 1, plantLimit, workerLimit,
                java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), Optional.empty(), now,
                settings, Map.of(ownerUuid, owner));
    }

    public Farm addMember(Farm farm, FarmMember member) {
        DomainChecks.required(farm, "farm");
        DomainChecks.required(member, "member");
        if (member.role() == FarmRole.OWNER && !member.playerUuid().equals(farm.ownerUuid())) {
            throw new IllegalArgumentException("A farm can have only one owner");
        }
        HashMap<UUID, FarmMember> members = new HashMap<>(farm.members());
        members.put(member.playerUuid(), member);
        return copy(farm, farm.linkedStorage(), farm.salaryStorage(), members);
    }

    public Farm linkStorage(Farm farm, FarmStorage storage, Collection<FarmStorage> allAssignments) {
        DomainChecks.required(farm, "farm");
        DomainChecks.required(storage, "storage");
        DomainChecks.required(allAssignments, "allAssignments");
        if (!farm.farmId().equals(storage.farmId())) {
            throw new IllegalArgumentException("Storage references a different farm");
        }
        if (!farm.region().contains(storage.position())) {
            throw new IllegalArgumentException("Storage must be inside the farm");
        }
        boolean duplicatePosition = allAssignments.stream().anyMatch(existing ->
                !existing.storageId().equals(storage.storageId())
                        && existing.position().equals(storage.position()));
        if (duplicatePosition) throw new IllegalArgumentException("Storage position is already assigned");
        HashSet<UUID> linked = new HashSet<>(farm.linkedStorage());
        linked.add(storage.storageId());
        Optional<UUID> salary = storage.type() == StorageType.SALARY_STORAGE
                ? Optional.of(storage.storageId()) : farm.salaryStorage();
        if (storage.type() == StorageType.SALARY_STORAGE && farm.salaryStorage().isPresent()
                && !farm.salaryStorage().get().equals(storage.storageId())) {
            throw new IllegalArgumentException("Farm already has a salary storage");
        }
        return copy(farm, linked, salary, farm.members());
    }

    private static Farm copy(Farm farm, java.util.Set<UUID> storages, Optional<UUID> salary,
                             Map<UUID, FarmMember> members) {
        return new Farm(farm.farmId(), farm.ownerUuid(), farm.name(), farm.region(), farm.level(),
                farm.plantLimit(), farm.workerLimit(), storages, farm.linkedDryingRacks(),
                farm.linkedCuringContainers(), salary, farm.createdAt(), farm.settings(), members);
    }
}
