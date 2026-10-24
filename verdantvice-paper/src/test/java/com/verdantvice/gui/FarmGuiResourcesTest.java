package com.verdantvice.gui;

import com.verdantvice.farm.FarmPermission;
import com.verdantvice.farm.FarmRole;
import com.verdantvice.farm.StorageType;
import com.verdantvice.notification.NotificationType;
import com.verdantvice.worker.WorkerSpecialization;
import com.verdantvice.worker.WorkerState;
import com.verdantvice.worker.WorkerTaskType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarmGuiResourcesTest {
    private static final List<String> LANGUAGES = List.of(
            "pl_PL", "en_US", "de_DE", "es_ES", "cs_CZ", "uk_UA");

    @Test
    void everyLanguageContainsSpecificEmptyFarmMessages() throws Exception {
        for (String locale : LANGUAGES) {
            YamlConfiguration yaml = language(locale);
            for (String key : List.of(
                    "gui.farm-panel.empty.plants",
                    "gui.farm-panel.empty.workers",
                    "gui.farm-panel.empty.storages",
                    "gui.farm-panel.empty.drying",
                    "gui.farm-panel.empty.curing"
            )) {
                assertText(yaml, locale, key);
            }
        }
    }

    @Test
    void everyRuntimeEnumDisplayedByFarmGuiHasLocalizedLabel() throws Exception {
        for (String locale : LANGUAGES) {
            YamlConfiguration yaml = language(locale);
            for (StorageType type : StorageType.values()) {
                assertText(yaml, locale, "gui.farm-panel.storage-types." + slug(type.name()));
            }
            for (WorkerState state : WorkerState.values()) {
                assertText(yaml, locale, "gui.farm-panel.worker.states." + slug(state.name()));
            }
            for (WorkerSpecialization specialization : WorkerSpecialization.values()) {
                assertText(yaml, locale,
                        "gui.farm-panel.worker.specializations." + slug(specialization.name()));
            }
            for (WorkerTaskType task : WorkerTaskType.values()) {
                assertText(yaml, locale, "gui.farm-panel.worker.tasks." + slug(task.name()));
            }
            for (NotificationType type : NotificationType.values()) {
                assertText(yaml, locale,
                        "gui.farm-panel.notifications.types." + slug(type.name()));
            }
        }
    }

    @Test
    void everyRoleAndPermissionIsLocalized() throws Exception {
        for (String locale : LANGUAGES) {
            YamlConfiguration yaml = language(locale);
            for (FarmRole role : FarmRole.values()) {
                assertText(yaml, locale,
                        "gui.farm-panel.permissions.roles." + slug(role.name()));
            }
            for (FarmPermission permission : FarmPermission.values()) {
                assertText(yaml, locale,
                        "gui.farm-panel.permissions.values." + slug(permission.name()));
            }
        }
    }

    @Test
    void refreshAndFarmAccessMessagesExistInEveryLanguage() throws Exception {
        for (String locale : LANGUAGES) {
            YamlConfiguration yaml = language(locale);
            assertText(yaml, locale, "gui.navigation.refresh");
            assertText(yaml, locale, "gui.farm-panel.errors.no-access");
            assertText(yaml, locale, "gui.farm-panel.errors.no-context");
        }
    }

    private YamlConfiguration language(String locale) throws Exception {
        var stream = getClass().getClassLoader().getResourceAsStream(
                "languages/" + locale + ".yml");
        assertNotNull(stream);
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(reader);
            return yaml;
        }
    }

    private void assertText(YamlConfiguration yaml, String locale, String key) {
        assertTrue(yaml.isString(key), locale + " misses " + key);
        assertFalse(yaml.getString(key, "").isBlank(), locale + " has blank " + key);
    }

    private String slug(String value) {
        return value.toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
