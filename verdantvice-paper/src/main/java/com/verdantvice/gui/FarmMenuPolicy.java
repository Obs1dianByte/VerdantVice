package com.verdantvice.gui;

import com.verdantvice.farm.Farm;
import com.verdantvice.farm.FarmPermission;

import java.util.Objects;
import java.util.UUID;

public final class FarmMenuPolicy {
    private FarmMenuPolicy() {
    }

    public static FarmPermission required(MenuType type) {
        Objects.requireNonNull(type, "type");
        return switch (type) {
            case WORKERS, PRIORITIES -> FarmPermission.MANAGE_WORKERS;
            case STORAGES, STORAGE_UNLINK_CONFIRM -> FarmPermission.MANAGE_STORAGE;
            case PERMISSIONS -> FarmPermission.MANAGE_MEMBERS;
            default -> FarmPermission.VIEW;
        };
    }

    public static boolean mayOpen(
            Farm farm,
            UUID playerUuid,
            MenuType type,
            boolean administrator
    ) {
        Objects.requireNonNull(farm, "farm");
        Objects.requireNonNull(playerUuid, "playerUuid");
        return administrator || farm.can(playerUuid, required(type));
    }

    public static boolean mayUnlinkStorage(
            Farm farm,
            UUID playerUuid,
            boolean administrator
    ) {
        Objects.requireNonNull(farm, "farm");
        Objects.requireNonNull(playerUuid, "playerUuid");
        return administrator || farm.can(playerUuid, FarmPermission.MANAGE_STORAGE);
    }

    public static boolean mayDelete(
            Farm farm,
            UUID playerUuid,
            boolean administrator
    ) {
        Objects.requireNonNull(farm, "farm");
        Objects.requireNonNull(playerUuid, "playerUuid");
        return administrator || farm.ownerUuid().equals(playerUuid);
    }
}
