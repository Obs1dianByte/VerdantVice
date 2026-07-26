package com.verdantvice.strain;

import com.verdantvice.config.ConfigManager;
import com.verdantvice.effects.EffectProfile;
import com.verdantvice.effects.EffectType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class StrainConfigLoader {
    private final ConfigManager configs;

    public StrainConfigLoader(ConfigManager configs) {
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    public StrainRegistry load() {
        YamlConfiguration yaml = configs.get("strains.yml");
        ConfigurationSection root = yaml.getConfigurationSection("strains");
        if (root == null || root.getKeys(false).isEmpty()) {
            throw new IllegalStateException("strains.yml does not define any strains");
        }
        StrainRegistry registry = new StrainRegistry();
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) {
                throw new IllegalStateException("Invalid strain section: " + id);
            }
            registry.register(parse(id, section));
        }
        if (registry.size() < 12) {
            throw new IllegalStateException("At least 12 strains must be configured");
        }
        return registry;
    }

    private Strain parse(String id, ConfigurationSection section) {
        String displayName = required(section, "display-name");
        StrainRarity rarity = parseEnum(
                StrainRarity.class,
                required(section, "rarity"),
                "rarity for " + id
        );
        long durationSeconds = positiveLong(section, "growth-duration-seconds");
        return new Strain(
                id.toLowerCase(Locale.ROOT),
                displayName,
                rarity,
                Duration.ofSeconds(durationSeconds),
                section.getDouble("difficulty"),
                section.getDouble("base-yield"),
                section.getDouble("quality-potential"),
                section.getDouble("disease-resistance"),
                section.getDouble("water-consumption"),
                section.getDouble("light-preference"),
                section.getDouble("stress-sensitivity"),
                section.getDouble("smell-intensity"),
                effects(section, "effect-profile"),
                effects(section, "negative-effect-profile"),
                section.getInt("seed-price"),
                section.getDouble("base-market-price"),
                required(section, "visual-model"),
                required(section, "particle-profile")
        );
    }

    private EffectProfile effects(ConfigurationSection strain, String key) {
        ConfigurationSection section = strain.getConfigurationSection(key);
        if (section == null) {
            return EffectProfile.empty();
        }
        Map<EffectType, Double> weights = new EnumMap<>(EffectType.class);
        for (String effectName : section.getKeys(false)) {
            EffectType type = parseEnum(EffectType.class, effectName, key);
            weights.put(type, section.getDouble(effectName));
        }
        return new EffectProfile(weights);
    }

    private String required(ConfigurationSection section, String key) {
        String value = section.getString(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Missing required value " + section.getCurrentPath() + "." + key
            );
        }
        return value;
    }

    private long positiveLong(ConfigurationSection section, String key) {
        long value = section.getLong(key);
        if (value <= 0L) {
            throw new IllegalStateException(
                    section.getCurrentPath() + "." + key + " must be positive"
            );
        }
        return value;
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid " + field + ": " + value, exception);
        }
    }
}
