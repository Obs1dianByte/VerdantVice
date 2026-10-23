package com.verdantvice.i18n;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class TranslationServiceTest {
    @Test
    void bundledKeysFillOldLanguageFileWithoutOverwritingCustomText() {
        YamlConfiguration local = new YamlConfiguration();
        local.set("rolling.errors.missing-paper", "custom paper message");

        YamlConfiguration bundled = new YamlConfiguration();
        bundled.set("rolling.errors.missing-paper", "bundled paper message");
        bundled.set("rolling.errors.wrong-product-state", "bundled state message");

        TranslationService.mergeMissingTranslations(local, bundled);

        assertEquals("custom paper message",
                local.getString("rolling.errors.missing-paper"));
        assertEquals("bundled state message",
                local.getString("rolling.errors.wrong-product-state"));
    }
}
