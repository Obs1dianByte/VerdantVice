package com.verdantvice.i18n;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class DomainTranslationKeysTest {
    private static final List<String> LOCALES = List.of(
            "pl_PL", "en_US", "de_DE", "es_ES", "cs_CZ", "uk_UA"
    );
    private static final List<String> KEYS = List.of(
            "errors.farm-region-overlap",
            "errors.manager-table-blocked",
            "errors.manager-table-outside-farm",
            "errors.farm-region-too-large",
            "errors.farm-name-required"
    );

    @Test
    void everyBuiltInLanguageContainsDomainErrorKeys() {
        for (String locale : LOCALES) {
            File file = new File("src/main/resources/languages/" + locale + ".yml");
            YamlConfiguration language = YamlConfiguration.loadConfiguration(file);
            for (String key : KEYS) {
                assertNotNull(language.getString(key), locale + " is missing " + key);
            }
        }
    }
}
