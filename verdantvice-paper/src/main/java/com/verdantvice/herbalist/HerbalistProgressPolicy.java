package com.verdantvice.herbalist;

import org.bukkit.configuration.file.YamlConfiguration;

import java.util.Objects;

public final class HerbalistProgressPolicy {
    private HerbalistProgressPolicy() {
    }

    public static HerbalistTier tierAfterProgress(
            HerbalistTier currentTier,
            long resultingExperience,
            YamlConfiguration configuration
    ) {
        Objects.requireNonNull(currentTier, "currentTier");
        HerbalistTier derived = HerbalistTier.forExperience(
                resultingExperience,
                Objects.requireNonNull(configuration, "configuration")
        );
        return maximum(currentTier, derived);
    }

    public static AdministrativeProgress administrativeProgress(
            HerbalistTier currentTier,
            long currentExperience,
            HerbalistTier requestedTier,
            YamlConfiguration configuration
    ) {
        Objects.requireNonNull(currentTier, "currentTier");
        Objects.requireNonNull(requestedTier, "requestedTier");
        Objects.requireNonNull(configuration, "configuration");
        long experience = Math.max(
                currentExperience,
                HerbalistTier.threshold(requestedTier, configuration)
        );
        HerbalistTier derived = HerbalistTier.forExperience(experience, configuration);
        HerbalistTier tier = maximum(currentTier, maximum(requestedTier, derived));
        return new AdministrativeProgress(tier, experience);
    }

    private static HerbalistTier maximum(HerbalistTier first, HerbalistTier second) {
        return first.ordinal() >= second.ordinal() ? first : second;
    }

    public record AdministrativeProgress(HerbalistTier tier, long experience) {
        public AdministrativeProgress {
            Objects.requireNonNull(tier, "tier");
            if (experience < 0L) {
                throw new IllegalArgumentException("experience");
            }
        }
    }
}
