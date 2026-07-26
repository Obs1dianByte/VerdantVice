package com.verdantvice.herbalist;

import org.bukkit.configuration.file.YamlConfiguration;

import java.util.Locale;
import java.util.Objects;

public enum HerbalistTier {
    NOVICE(1),
    APPRENTICE(2),
    JOURNEYMAN(3),
    EXPERT(4),
    MASTER(5),
    VERDANT_EXPERT(6);

    private final int databaseLevel;

    HerbalistTier(int databaseLevel) {
        this.databaseLevel = databaseLevel;
    }

    public int databaseLevel() {
        return databaseLevel;
    }

    public String translationKey() {
        return "herbalist.tiers." + name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static HerbalistTier fromDatabaseLevel(int level) {
        HerbalistTier[] values = values();
        return values[Math.max(0, Math.min(values.length - 1, level - 1))];
    }

    public static HerbalistTier forExperience(long experience, YamlConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        HerbalistTier result = NOVICE;
        for (HerbalistTier tier : values()) {
            long threshold = configuration.getLong("levels." + tier.name(), defaultThreshold(tier));
            if (experience >= Math.max(0L, threshold)) {
                result = tier;
            }
        }
        return result;
    }

    private static long defaultThreshold(HerbalistTier tier) {
        return switch (tier) {
            case NOVICE -> 0L;
            case APPRENTICE -> 15L;
            case JOURNEYMAN -> 40L;
            case EXPERT -> 80L;
            case MASTER -> 140L;
            case VERDANT_EXPERT -> 220L;
        };
    }
}
