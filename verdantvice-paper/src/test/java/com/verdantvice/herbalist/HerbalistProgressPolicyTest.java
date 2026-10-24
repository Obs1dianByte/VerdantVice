package com.verdantvice.herbalist;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class HerbalistProgressPolicyTest {
    @Test
    void normalProgressNeverDowngradesCurrentTier() {
        YamlConfiguration configuration = thresholds();
        assertEquals(
                HerbalistTier.VERDANT_EXPERT,
                HerbalistProgressPolicy.tierAfterProgress(
                        HerbalistTier.VERDANT_EXPERT, 1L, configuration)
        );
    }

    @Test
    void noviceStillPromotesNormallyAtThresholds() {
        YamlConfiguration configuration = thresholds();
        assertEquals(
                HerbalistTier.EXPERT,
                HerbalistProgressPolicy.tierAfterProgress(
                        HerbalistTier.NOVICE, 80L, configuration)
        );
    }

    @Test
    void changedConfigurationCannotDowngradeExistingHerbalist() {
        YamlConfiguration configuration = thresholds();
        configuration.set("levels.VERDANT_EXPERT", 10_000L);
        assertEquals(
                HerbalistTier.VERDANT_EXPERT,
                HerbalistProgressPolicy.tierAfterProgress(
                        HerbalistTier.VERDANT_EXPERT, 221L, configuration)
        );
    }

    @Test
    void administrativeTierRaisesExperienceToConfiguredThreshold() {
        YamlConfiguration configuration = thresholds();
        configuration.set("levels.VERDANT_EXPERT", 777L);

        HerbalistProgressPolicy.AdministrativeProgress progress =
                HerbalistProgressPolicy.administrativeProgress(
                        HerbalistTier.NOVICE,
                        4L,
                        HerbalistTier.VERDANT_EXPERT,
                        configuration
                );

        assertEquals(HerbalistTier.VERDANT_EXPERT, progress.tier());
        assertEquals(777L, progress.experience());
    }

    @Test
    void administrativeChangePreservesHigherExperienceAndTier() {
        YamlConfiguration configuration = thresholds();
        HerbalistProgressPolicy.AdministrativeProgress progress =
                HerbalistProgressPolicy.administrativeProgress(
                        HerbalistTier.VERDANT_EXPERT,
                        2_000L,
                        HerbalistTier.EXPERT,
                        configuration
                );
        assertEquals(HerbalistTier.VERDANT_EXPERT, progress.tier());
        assertEquals(2_000L, progress.experience());
    }

    private YamlConfiguration thresholds() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("levels.NOVICE", 0L);
        configuration.set("levels.APPRENTICE", 15L);
        configuration.set("levels.JOURNEYMAN", 40L);
        configuration.set("levels.EXPERT", 80L);
        configuration.set("levels.MASTER", 140L);
        configuration.set("levels.VERDANT_EXPERT", 220L);
        return configuration;
    }
}
