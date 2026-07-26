package com.verdantvice.farm;

import com.verdantvice.common.DomainChecks;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record FarmMember(
        UUID playerUuid,
        FarmRole role,
        Set<FarmPermission> grantedOverrides,
        Set<FarmPermission> deniedOverrides,
        Instant joinedAt
) {
    public FarmMember {
        DomainChecks.required(playerUuid, "playerUuid");
        DomainChecks.required(role, "role");
        grantedOverrides = Set.copyOf(DomainChecks.required(grantedOverrides, "grantedOverrides"));
        deniedOverrides = Set.copyOf(DomainChecks.required(deniedOverrides, "deniedOverrides"));
        if (!java.util.Collections.disjoint(grantedOverrides, deniedOverrides)) {
            throw new IllegalArgumentException("A permission cannot be both granted and denied");
        }
        DomainChecks.required(joinedAt, "joinedAt");
    }

    public boolean can(FarmPermission permission) {
        DomainChecks.required(permission, "permission");
        if (deniedOverrides.contains(permission)) return false;
        return grantedOverrides.contains(permission) || role.grants(permission);
    }
}
