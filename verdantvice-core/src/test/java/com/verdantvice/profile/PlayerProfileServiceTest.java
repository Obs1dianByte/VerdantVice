package com.verdantvice.profile;

import com.verdantvice.test.TestFixtures;
import com.verdantvice.tolerance.TolerancePolicy;
import com.verdantvice.tolerance.ToleranceService;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class PlayerProfileServiceTest {
    @Test
    void allSkillTreesExistAndCapAtTwenty() {
        PlayerProfile initial = PlayerProfile.create(TestFixtures.OWNER, "pl_PL", TestFixtures.NOW);
        assertEquals(4, initial.skills().size());
        PlayerProfileService profiles = new PlayerProfileService();
        PlayerProfile advanced = profiles.grantExperience(initial, SkillTree.GROWER,
                100_000L, TestFixtures.NOW.plusSeconds(1));
        assertEquals(20, advanced.skill(SkillTree.GROWER).level());
        assertEquals(0, advanced.skill(SkillTree.TRADER).level());
    }

    @Test
    void updatesLocaleReputationAndToleranceWithoutChangingIdentity() {
        PlayerProfileService profiles = new PlayerProfileService();
        PlayerProfile initial = PlayerProfile.create(TestFixtures.OWNER, "pl_PL", TestFixtures.NOW);
        PlayerProfile localized = profiles.changeLocale(initial, "en_US", TestFixtures.NOW.plusSeconds(1));
        PlayerProfile reputable = profiles.addReputation(localized, 120,
                TestFixtures.NOW.plusSeconds(2));
        var tolerance = new ToleranceService().recordUse(reputable.tolerance(), 1.0,
                TestFixtures.NOW.plusSeconds(3), TolerancePolicy.defaults());
        PlayerProfile used = profiles.withTolerance(reputable, tolerance,
                TestFixtures.NOW.plusSeconds(3));

        assertEquals(TestFixtures.OWNER, used.playerUuid());
        assertEquals("en_US", used.locale());
        assertEquals(100.0, used.reputation());
        assertTrue(used.tolerance().level() > 0.0);
        assertThrows(IllegalArgumentException.class,
                () -> profiles.changeLocale(used, "english", TestFixtures.NOW.plusSeconds(4)));
    }
}
