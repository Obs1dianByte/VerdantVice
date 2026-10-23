package com.verdantvice.integration;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class IntegrationPolicyTest {
    @Test void missingSettingKeepsInstalledOptionalHooksEnabledByDefault() {
        assertTrue(IntegrationPolicy.enabled(new YamlConfiguration(), "placeholderapi", true));
    }

    @Test void explicitOptOutWinsEvenWhenDependencyIsInstalled() {
        var settings = new YamlConfiguration();
        settings.set("placeholderapi", false);
        assertFalse(IntegrationPolicy.enabled(settings, "placeholderapi", true));
        settings.set("placeholderapi", true);
        assertTrue(IntegrationPolicy.enabled(settings, "placeholderapi", true));
    }

    @Test void configurationCannotEnableAMissingDependency() {
        var settings = new YamlConfiguration();
        settings.set("placeholderapi", true);
        assertFalse(IntegrationPolicy.enabled(settings, "placeholderapi", false));
    }
}
