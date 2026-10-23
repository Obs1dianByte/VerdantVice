package com.verdantvice.gui;

import com.verdantvice.farm.Farm;
import com.verdantvice.farm.FarmMember;
import com.verdantvice.farm.FarmPermission;
import com.verdantvice.farm.FarmRegion;
import com.verdantvice.farm.FarmRole;
import com.verdantvice.farm.FarmSettings;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarmMenuPolicyTest {
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000211");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000212");
    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000213");
    private static final UUID OUTSIDER = UUID.fromString("00000000-0000-0000-0000-000000000214");

    @Test
    void memberWithViewCanSeeFarmPanel() {
        assertTrue(FarmMenuPolicy.mayOpen(farm(), VIEWER, MenuType.FARM, false));
    }

    @Test
    void playerWithoutViewCannotSeeFarmPanel() {
        assertFalse(FarmMenuPolicy.mayOpen(farm(), OUTSIDER, MenuType.FARM, false));
    }

    @Test
    void viewerCannotUnlinkStorage() {
        assertFalse(FarmMenuPolicy.mayUnlinkStorage(farm(), VIEWER, false));
    }

    @Test
    void viewerCannotManageWorkerPriorities() {
        assertFalse(FarmMenuPolicy.mayOpen(farm(), VIEWER, MenuType.PRIORITIES, false));
    }

    @Test
    void administratorBypassesScreenChecksAndMayDeleteWithAdminPermission() {
        assertTrue(FarmMenuPolicy.mayOpen(farm(), OUTSIDER, MenuType.PERMISSIONS, true));
        assertTrue(FarmMenuPolicy.mayDelete(farm(), OUTSIDER, true));
        assertTrue(FarmMenuPolicy.mayDelete(farm(), OWNER, false));
    }

    @Test
    void viewerCannotOpenWorkerOrStorageManagementScreens() {
        assertFalse(FarmMenuPolicy.mayOpen(farm(), VIEWER, MenuType.WORKERS, false));
        assertFalse(FarmMenuPolicy.mayOpen(farm(), VIEWER, MenuType.STORAGES, false));
    }

    @Test
    void storageConfirmationRequiresManageStorage() {
        assertFalse(FarmMenuPolicy.mayOpen(
                farm(), VIEWER, MenuType.STORAGE_UNLINK_CONFIRM, false));
    }

    private Farm farm() {
        FarmMember owner = new FarmMember(
                OWNER, FarmRole.OWNER, Set.of(), Set.of(), Instant.EPOCH);
        FarmMember viewer = new FarmMember(
                VIEWER, FarmRole.VIEWER, Set.of(FarmPermission.VIEW),
                Set.of(), Instant.EPOCH);
        return new Farm(
                UUID.randomUUID(), OWNER, "test",
                new FarmRegion(WORLD, 0, 0, 0, 10, 255, 10),
                1, 64, 5, Set.of(), Set.of(), Set.of(), Optional.empty(),
                Instant.EPOCH, FarmSettings.defaults(),
                Map.of(OWNER, owner, VIEWER, viewer)
        );
    }
}
