package com.verdantvice.farm;

import com.verdantvice.common.BlockPosition;
import com.verdantvice.test.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FarmServiceTest {
    private final FarmService service = new FarmService();

    @Test
    void farmRegionsCannotOverlap() {
        Farm first = service.create(UUID.randomUUID(), TestFixtures.OWNER, "First",
                region(0, 0, 10, 10), 100, 5, FarmSettings.defaults(), List.of(), TestFixtures.NOW);
        assertThrows(IllegalArgumentException.class, () -> service.create(
                UUID.randomUUID(), UUID.randomUUID(), "Second", region(10, 10, 20, 20),
                100, 5, FarmSettings.defaults(), List.of(first), TestFixtures.NOW));
        Farm separate = service.create(UUID.randomUUID(), UUID.randomUUID(), "Separate",
                region(11, 11, 20, 20), 100, 5, FarmSettings.defaults(), List.of(first), TestFixtures.NOW);
        assertFalse(first.region().overlaps(separate.region()));
    }

    @Test
    void rolesAndOverridesControlFarmActions() {
        Farm farm = service.create(UUID.randomUUID(), TestFixtures.OWNER, "Farm",
                region(0, 0, 10, 10), 100, 5, FarmSettings.defaults(), List.of(), TestFixtures.NOW);
        UUID memberId = UUID.randomUUID();
        FarmMember member = new FarmMember(memberId, FarmRole.MEMBER, Set.of(FarmPermission.MANAGE_WORKERS),
                Set.of(FarmPermission.HARVEST), TestFixtures.NOW);
        Farm updated = service.addMember(farm, member);
        assertTrue(updated.can(memberId, FarmPermission.WATER));
        assertTrue(updated.can(memberId, FarmPermission.MANAGE_WORKERS));
        assertFalse(updated.can(memberId, FarmPermission.HARVEST));
        assertTrue(updated.can(TestFixtures.OWNER, FarmPermission.DELETE));
    }

    @Test
    void storageMustBeInsideAndCannotBeAssignedTwice() {
        Farm farm = service.create(UUID.randomUUID(), TestFixtures.OWNER, "Farm",
                region(0, 0, 10, 10), 100, 5, FarmSettings.defaults(), List.of(), TestFixtures.NOW);
        BlockPosition position = new BlockPosition(TestFixtures.WORLD, 5, 64, 5);
        FarmStorage salary = new FarmStorage(UUID.randomUUID(), farm.farmId(), position,
                StorageType.SALARY_STORAGE, true, TestFixtures.NOW);
        Farm linked = service.linkStorage(farm, salary, List.of());
        assertEquals(salary.storageId(), linked.salaryStorage().orElseThrow());

        FarmStorage duplicate = new FarmStorage(UUID.randomUUID(), farm.farmId(), position,
                StorageType.TOOL_STORAGE, true, TestFixtures.NOW);
        assertThrows(IllegalArgumentException.class,
                () -> service.linkStorage(linked, duplicate, List.of(salary)));
    }

    private static FarmRegion region(int minimumX, int minimumZ, int maximumX, int maximumZ) {
        return new FarmRegion(TestFixtures.WORLD, minimumX, 0, minimumZ, maximumX, 255, maximumZ);
    }
}
