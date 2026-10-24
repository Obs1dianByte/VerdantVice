package com.verdantvice.farm;

import com.verdantvice.common.DomainChecks;

import java.util.Optional;

/**
 * Typed outcome for linking and unlinking a physical farm storage.
 */
public record StorageLinkResult(
        Status status,
        Optional<Farm> farm,
        Optional<FarmStorage> storage
) {
    public StorageLinkResult {
        status = DomainChecks.required(status, "status");
        farm = DomainChecks.required(farm, "farm");
        storage = DomainChecks.required(storage, "storage");
        if ((status == Status.LINKED || status == Status.UNLINKED)
                && (farm.isEmpty() || storage.isEmpty())) {
            throw new IllegalArgumentException("Successful storage result requires farm and storage");
        }
    }

    public static StorageLinkResult success(Status status, Farm farm, FarmStorage storage) {
        return new StorageLinkResult(status, Optional.of(farm), Optional.of(storage));
    }

    public static StorageLinkResult failure(Status status) {
        return new StorageLinkResult(status, Optional.empty(), Optional.empty());
    }

    public enum Status {
        LINKED,
        UNLINKED,
        NO_OWNED_FARM,
        NO_TARGET,
        NOT_A_CONTAINER,
        OUTSIDE_FARM,
        ALREADY_LINKED,
        NOT_LINKED,
        SALARY_STORAGE_ALREADY_EXISTS,
        NO_PERMISSION,
        PROTECTED_REGION,
        INVALID_STORAGE_TYPE,
        OTHER_FARM,
        DATABASE_FAILURE
    }
}
