package com.verdantvice.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundledResourcesTest {
    private static final List<String> LANGUAGES = List.of(
            "pl_PL", "en_US", "de_DE", "es_ES", "cs_CZ", "uk_UA"
    );
    private static final List<String> CONFIGURATIONS = List.of(
            "config.yml",
            "database.yml",
            "plants.yml",
            "strains.yml",
            "genetics.yml",
            "quality.yml",
            "effects.yml",
            "villagers.yml",
            "workers.yml",
            "economy.yml",
            "market.yml",
            "contracts.yml",
            "gui.yml",
            "items.yml",
            "integrations.yml",
            "resourcepack.yml",
            "notifications.yml"
    );

    @Test
    void everyBundledYamlIsSyntacticallyValidAndNonEmpty() throws Exception {
        for (String file : CONFIGURATIONS) {
            YamlConfiguration yaml = load(file);
            assertFalse(yaml.getKeys(false).isEmpty(), file + " must not be empty");
        }
        for (String locale : LANGUAGES) {
            YamlConfiguration yaml = load("languages/" + locale + ".yml");
            assertEquals(locale, yaml.getString("meta.locale"));
        }
    }

    @Test
    void translationsHaveIdenticalKeysAndValidUtf8Text() throws Exception {
        Set<String> reference = leafKeys(load("languages/pl_PL.yml"));
        assertFalse(reference.isEmpty());
        for (String locale : LANGUAGES) {
            YamlConfiguration language = load("languages/" + locale + ".yml");
            assertEquals(
                    reference,
                    leafKeys(language),
                    locale + " must contain the same translation keys as pl_PL"
            );
            for (String key : reference) {
                Object value = language.get(key);
                if (value instanceof String text) {
                    assertFalse(
                            text.contains("\uFFFD"),
                            locale + " contains an invalid UTF-8 replacement at " + key
                    );
                }
            }
        }
    }

    @Test
    void defaultStrainCatalogContainsAllTwelveVarieties() throws Exception {
        ConfigurationSection strains =
                load("strains.yml").getConfigurationSection("strains");
        assertNotNull(strains);
        assertEquals(12, strains.getKeys(false).size());
        assertTrue(strains.contains("berry_fog"));
        assertTrue(strains.contains("verdant_vice"));
        for (String id : strains.getKeys(false)) {
            ConfigurationSection strain = strains.getConfigurationSection(id);
            assertNotNull(strain);
            for (String required : List.of(
                    "display-name",
                    "rarity",
                    "growth-duration-seconds",
                    "base-yield",
                    "quality-potential",
                    "effect-profile",
                    "negative-effect-profile",
                    "seed-price",
                    "base-market-price",
                    "visual-model"
            )) {
                assertTrue(strain.contains(required), id + " misses " + required);
            }
        }
    }

    @Test
    void pluginDescriptorDeclaresEntrypointCommandAndRequiredPermissions()
            throws Exception {
        YamlConfiguration descriptor = load("plugin.yml");
        assertEquals("VerdantVice", descriptor.getString("name"));
        assertEquals(
                "com.verdantvice.VerdantVicePlugin",
                descriptor.getString("main")
        );
        assertEquals("1.21.4", descriptor.getString("api-version"));
        assertTrue(descriptor.contains("commands.verdantvice"));
        assertTrue(
                descriptor.getStringList("commands.verdantvice.aliases")
                        .contains("vv")
        );
        for (String permission : List.of(
                "verdantvice.use",
                "verdantvice.plant",
                "verdantvice.process",
                "verdantvice.trade",
                "verdantvice.farm.create",
                "verdantvice.worker.hire",
                "verdantvice.admin",
                "verdantvice.admin.debug",
                "verdantvice.admin.database"
        )) {
            assertTrue(
                    descriptor.contains("permissions." + permission),
                    "plugin.yml misses " + permission
            );
        }
    }

    private Set<String> leafKeys(YamlConfiguration yaml) {
        Set<String> result = new TreeSet<>();
        for (String key : yaml.getKeys(true)) {
            if (!yaml.isConfigurationSection(key)) {
                result.add(key);
            }
        }
        return Set.copyOf(result);
    }

    private YamlConfiguration load(String resource)
            throws IOException, InvalidConfigurationException {
        InputStream stream = getClass().getClassLoader().getResourceAsStream(resource);
        assertNotNull(stream, "Missing bundled resource " + resource);
        try (InputStreamReader reader =
                     new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(reader);
            return yaml;
        }
    }
}
