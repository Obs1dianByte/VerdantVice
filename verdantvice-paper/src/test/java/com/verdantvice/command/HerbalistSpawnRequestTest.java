package com.verdantvice.command;

import com.verdantvice.herbalist.HerbalistTier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HerbalistSpawnRequestTest {
    @Test
    void ordinarySpawnKeepsVillageLimit() {
        HerbalistSpawnRequest request = HerbalistSpawnRequest.parse(
                new String[] {"admin", "spawnherbalist", "VERDANT_EXPERT"}
        );
        assertEquals(HerbalistTier.VERDANT_EXPERT, request.tier().orElseThrow());
        assertFalse(request.force());
    }

    @Test
    void forceMayAppearWithOrWithoutExplicitTier() {
        HerbalistSpawnRequest withTier = HerbalistSpawnRequest.parse(
                new String[] {"admin", "spawnherbalist", "VERDANT_EXPERT", "--force"}
        );
        assertTrue(withTier.force());
        assertEquals(HerbalistTier.VERDANT_EXPERT, withTier.tier().orElseThrow());

        HerbalistSpawnRequest withoutTier = HerbalistSpawnRequest.parse(
                new String[] {"admin", "spawnherbalist", "--force"}
        );
        assertTrue(withoutTier.force());
        assertTrue(withoutTier.tier().isEmpty());
    }

    @Test
    void forceMayAppearBeforeExplicitTier() {
        HerbalistSpawnRequest request = HerbalistSpawnRequest.parse(
                new String[] {"admin", "spawnherbalist", "--force", "MASTER"}
        );
        assertTrue(request.force());
        assertEquals(HerbalistTier.MASTER, request.tier().orElseThrow());
    }

    @Test
    void duplicateOrUnexpectedArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> HerbalistSpawnRequest.parse(
                new String[] {"admin", "spawnherbalist", "--force", "--force"}
        ));
        assertThrows(IllegalArgumentException.class, () -> HerbalistSpawnRequest.parse(
                new String[] {"admin", "spawnherbalist", "NOVICE", "MASTER"}
        ));
    }
}
