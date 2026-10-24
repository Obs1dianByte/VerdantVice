package com.verdantvice.farm;

import com.verdantvice.common.BlockPosition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FarmConcurrentUpdateTest {
    private final FarmService service = new FarmService();

    @Test
    void storageCompletionDoesNotRemoveAMemberInvitedWhileItWasSaving() {
        Farm original = farm();
        FarmStorage salary = storage(original);
        Farm pendingStorage = service.linkStorage(original, salary, List.of(),
                List.of(salary.position())).farm().orElseThrow();
        FarmMember invited = member();
        Farm current = service.addMember(original, invited);

        Farm applied = FarmManager.applyStorageSnapshot(current, pendingStorage);
        assertTrue(applied.can(invited.playerUuid(), FarmPermission.PLANT));
        assertEquals(salary.storageId(), applied.salaryStorage().orElseThrow());
    }

    @Test
    void removalCompletionKeepsAnotherNewMemberAndTheLatestSalaryStorage() {
        FarmMember removed = member();
        FarmMember invited = member();
        Farm original = service.addMember(farm(), removed);
        FarmStorage salary = storage(original);
        Farm current = service.linkStorage(original, salary, List.of(),
                List.of(salary.position())).farm().orElseThrow();
        current = service.addMember(current, invited);

        Farm applied = FarmManager.applyMemberRemoval(current, removed.playerUuid());
        assertFalse(applied.members().containsKey(removed.playerUuid()));
        assertTrue(applied.can(invited.playerUuid(), FarmPermission.ACCESS_STORAGE));
        assertEquals(salary.storageId(), applied.salaryStorage().orElseThrow());
    }

    @Test
    void unlinkCompletionKeepsTheLatestMembership() {
        Farm original = farm();
        FarmStorage salary = storage(original);
        Farm linked = service.linkStorage(original, salary, List.of(),
                List.of(salary.position())).farm().orElseThrow();
        Farm unlinkSnapshot = service.unlinkStorage(linked, salary).farm().orElseThrow();
        FarmMember invited = member();
        Farm current = service.addMember(linked, invited);

        Farm applied = FarmManager.applyStorageSnapshot(current, unlinkSnapshot);
        assertTrue(applied.can(invited.playerUuid(), FarmPermission.HARVEST));
        assertTrue(applied.salaryStorage().isEmpty());
        assertTrue(applied.linkedStorage().isEmpty());
    }

    @Test
    void anotherFarmsStorageSnapshotIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> FarmManager.applyStorageSnapshot(farm(), farm()));
    }

    private Farm farm() {
        return service.create(UUID.randomUUID(), UUID.randomUUID(), "Shared Farm",
                new FarmRegion(UUID.randomUUID(), 0, 60, 0, 16, 80, 16),
                64, 5, FarmSettings.defaults(), List.of(), Instant.now());
    }

    private static FarmMember member() {
        return new FarmMember(UUID.randomUUID(), FarmRole.MEMBER,
                Set.of(), Set.of(), Instant.now());
    }

    private static FarmStorage storage(Farm farm) {
        return new FarmStorage(UUID.randomUUID(), farm.farmId(),
                new BlockPosition(farm.region().worldUuid(), 2, 64, 2),
                StorageType.SALARY_STORAGE, true, Instant.now());
    }
}
