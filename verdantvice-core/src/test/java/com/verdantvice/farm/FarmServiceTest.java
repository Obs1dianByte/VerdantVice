package com.verdantvice.farm;

import com.verdantvice.common.BlockPosition;
import com.verdantvice.test.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarmServiceTest {
    private final FarmService service = new FarmService();

    @Test
    void farmRegionsCannotOverlap() {
        Farm first = service.create(UUID.randomUUID(), TestFixtures.OWNER, "First",
                region(0, 0, 10, 10), 100, 5, FarmSettings.defaults(), List.of(), TestFixtures.NOW);
        try {
            service.create(UUID.randomUUID(), UUID.randomUUID(), "Second",
                    region(10, 10, 20, 20), 100, 5, FarmSettings.defaults(),
                    List.of(first), TestFixtures.NOW);
            throw new AssertionError("Overlapping farm should be rejected");
        } catch (IllegalArgumentException expected) {
            assertEquals("farm_region_overlap", expected.getMessage());
        }
        Farm separate = service.create(UUID.randomUUID(), UUID.randomUUID(), "Separate",
                region(11, 11, 20, 20), 100, 5, FarmSettings.defaults(),
                List.of(first), TestFixtures.NOW);
        assertFalse(first.region().overlaps(separate.region()));
    }

    @Test
    void rolesAndOverridesControlFarmActions() {
        Farm farm = farm();
        UUID memberId = UUID.randomUUID();
        FarmMember member = new FarmMember(memberId, FarmRole.MEMBER,
                Set.of(FarmPermission.MANAGE_WORKERS), Set.of(FarmPermission.HARVEST),
                TestFixtures.NOW);
        Farm updated = service.addMember(farm, member);
        assertTrue(updated.can(memberId, FarmPermission.WATER));
        assertTrue(updated.can(memberId, FarmPermission.MANAGE_WORKERS));
        assertFalse(updated.can(memberId, FarmPermission.HARVEST));
        assertTrue(updated.can(TestFixtures.OWNER, FarmPermission.DELETE));
    }

    @Test
    void storageInsideFarmLinksAndCanBeUnlinkedAndRelinked() {
        Farm farm = farm();
        BlockPosition position = position(5, 64, 5);
        FarmStorage storage = storage(farm, position, StorageType.SEED_STORAGE);

        StorageLinkResult linked = service.linkStorage(
                farm, storage, List.of(), List.of(position));
        assertEquals(StorageLinkResult.Status.LINKED, linked.status());
        Farm linkedFarm = linked.farm().orElseThrow();
        assertTrue(linkedFarm.linkedStorage().contains(storage.storageId()));

        StorageLinkResult unlinked = service.unlinkStorage(linkedFarm, storage);
        assertEquals(StorageLinkResult.Status.UNLINKED, unlinked.status());
        Farm unlinkedFarm = unlinked.farm().orElseThrow();
        assertFalse(unlinkedFarm.linkedStorage().contains(storage.storageId()));

        FarmStorage replacement = storage(unlinkedFarm, position, StorageType.TOOL_STORAGE);
        assertEquals(StorageLinkResult.Status.LINKED, service.linkStorage(
                unlinkedFarm, replacement, List.of(), List.of(position)).status());
    }

    @Test
    void outsideDuplicateOtherFarmAndSalaryConflictsAreTyped() {
        Farm farm = farm();
        BlockPosition inside = position(5, 64, 5);
        BlockPosition outside = position(20, 64, 20);
        FarmStorage salary = storage(farm, inside, StorageType.SALARY_STORAGE);

        assertEquals(StorageLinkResult.Status.OUTSIDE_FARM, service.linkStorage(
                farm, storage(farm, outside, StorageType.TOOL_STORAGE),
                List.of(), List.of(outside)).status());

        StorageLinkResult first = service.linkStorage(
                farm, salary, List.of(), List.of(inside));
        Farm linked = first.farm().orElseThrow();
        assertEquals(StorageLinkResult.Status.ALREADY_LINKED, service.linkStorage(
                linked, storage(linked, inside, StorageType.TOOL_STORAGE),
                List.of(salary), List.of(inside)).status());

        FarmStorage secondSalary = storage(linked, position(6, 64, 6),
                StorageType.SALARY_STORAGE);
        assertEquals(StorageLinkResult.Status.SALARY_STORAGE_ALREADY_EXISTS,
                service.linkStorage(linked, secondSalary, List.of(salary),
                        List.of(secondSalary.position())).status());

        Farm other = service.create(UUID.randomUUID(), UUID.randomUUID(), "Other",
                region(20, 20, 30, 30), 100, 5, FarmSettings.defaults(),
                List.of(farm), TestFixtures.NOW);
        FarmStorage foreign = storage(other, inside, StorageType.GENERAL_STORAGE);
        assertEquals(StorageLinkResult.Status.OTHER_FARM, service.linkStorage(
                farm, foreign, List.of(), List.of(inside)).status());
    }

    @Test
    void everyPhysicalHalfMustBeInsideFarm() {
        Farm farm = farm();
        BlockPosition anchor = position(10, 64, 5);
        BlockPosition outsideHalf = position(11, 64, 5);
        FarmStorage storage = storage(farm, anchor, StorageType.GENERAL_STORAGE);
        assertEquals(StorageLinkResult.Status.OUTSIDE_FARM, service.linkStorage(
                farm, storage, List.of(), List.of(anchor, outsideHalf)).status());
    }

    private static Farm farm() {
        return new FarmService().create(UUID.randomUUID(), TestFixtures.OWNER, "Farm",
                region(0, 0, 10, 10), 100, 5, FarmSettings.defaults(),
                List.of(), TestFixtures.NOW);
    }

    private static FarmStorage storage(Farm farm, BlockPosition position, StorageType type) {
        return new FarmStorage(UUID.randomUUID(), farm.farmId(), position,
                type, true, TestFixtures.NOW);
    }

    private static BlockPosition position(int x, int y, int z) {
        return new BlockPosition(TestFixtures.WORLD, x, y, z);
    }

    private static FarmRegion region(int minimumX, int minimumZ, int maximumX, int maximumZ) {
        return new FarmRegion(TestFixtures.WORLD, minimumX, 0, minimumZ,
                maximumX, 255, maximumZ);
    }
}
