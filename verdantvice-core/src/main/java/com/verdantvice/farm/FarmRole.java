package com.verdantvice.farm;

import java.util.EnumSet;
import java.util.Set;

public enum FarmRole {
    OWNER(EnumSet.allOf(FarmPermission.class)),
    MANAGER(EnumSet.complementOf(EnumSet.of(FarmPermission.DELETE))),
    MEMBER(EnumSet.of(FarmPermission.VIEW, FarmPermission.PLANT, FarmPermission.WATER,
            FarmPermission.HARVEST, FarmPermission.PROCESS, FarmPermission.ACCESS_STORAGE)),
    VIEWER(EnumSet.of(FarmPermission.VIEW));

    private final Set<FarmPermission> permissions;

    FarmRole(Set<FarmPermission> permissions) {
        this.permissions = Set.copyOf(permissions);
    }

    public boolean grants(FarmPermission permission) {
        return permissions.contains(permission);
    }

    public Set<FarmPermission> permissions() {
        return permissions;
    }
}
