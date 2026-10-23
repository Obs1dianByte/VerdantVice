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
            throw new IllegalArgumentException("farm_region_overlap");
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

    public StorageLinkResult linkStorage(
            Farm farm,
            FarmStorage storage,
            Collection<FarmStorage> allAssignments,
            Collection<com.verdantvice.common.BlockPosition> physicalPositions
    ) {
        DomainChecks.required(farm, "farm");
        DomainChecks.required(storage, "storage");
        DomainChecks.required(allAssignments, "allAssignments");
        DomainChecks.required(physicalPositions, "physicalPositions");
        if (!farm.farmId().equals(storage.farmId())) {
            return StorageLinkResult.failure(StorageLinkResult.Status.OTHER_FARM);
        }
        if (physicalPositions.isEmpty()
                || physicalPositions.stream().anyMatch(position -> !farm.region().contains(position))) {
            return StorageLinkResult.failure(StorageLinkResult.Status.OUTSIDE_FARM);
        }
        Optional<FarmStorage> assigned = allAssignments.stream()
                .filter(existing -> existing.position().equals(storage.position()))
                .findFirst();
        if (assigned.isPresent()) {
            return StorageLinkResult.failure(assigned.get().farmId().equals(farm.farmId())
                    ? StorageLinkResult.Status.ALREADY_LINKED
                    : StorageLinkResult.Status.OTHER_FARM);
        }
        if (storage.type() == StorageType.SALARY_STORAGE && farm.salaryStorage().isPresent()) {
            return StorageLinkResult.failure(
                    StorageLinkResult.Status.SALARY_STORAGE_ALREADY_EXISTS);
        }
        HashSet<UUID> linked = new HashSet<>(farm.linkedStorage());
        linked.add(storage.storageId());
        Optional<UUID> salary = storage.type() == StorageType.SALARY_STORAGE
                ? Optional.of(storage.storageId()) : farm.salaryStorage();
        Farm updated = copy(farm, linked, salary, farm.members());
        return StorageLinkResult.success(StorageLinkResult.Status.LINKED, updated, storage);
    }

    public StorageLinkResult unlinkStorage(Farm farm, FarmStorage storage) {
        DomainChecks.required(farm, "farm");
        DomainChecks.required(storage, "storage");
        if (!farm.farmId().equals(storage.farmId())) {
            return StorageLinkResult.failure(StorageLinkResult.Status.OTHER_FARM);
        }
        if (!farm.linkedStorage().contains(storage.storageId())) {
            return StorageLinkResult.failure(StorageLinkResult.Status.NOT_LINKED);
        }
        HashSet<UUID> linked = new HashSet<>(farm.linkedStorage());
        linked.remove(storage.storageId());
        Optional<UUID> salary = farm.salaryStorage()
                .filter(storageId -> !storageId.equals(storage.storageId()));
        Farm updated = copy(farm, linked, salary, farm.members());
        return StorageLinkResult.success(StorageLinkResult.Status.UNLINKED, updated, storage);
    }

    private static Farm copy(Farm farm, java.util.Set<UUID> storages, Optional<UUID> salary,
                             Map<UUID, FarmMember> members) {
        return new Farm(farm.farmId(), farm.ownerUuid(), farm.name(), farm.region(), farm.level(),
                farm.plantLimit(), farm.workerLimit(), storages, farm.linkedDryingRacks(),
                farm.linkedCuringContainers(), salary, farm.createdAt(), farm.settings(), members);
    }
}
