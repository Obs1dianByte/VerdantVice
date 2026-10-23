package com.verdantvice.integration;

import org.bukkit.configuration.ConfigurationSection;

/** Both installation and the owner's explicit integration setting must allow a hook. */
public final class IntegrationPolicy {
    private IntegrationPolicy() { }

    public static boolean enabled(ConfigurationSection settings, String key, boolean installed) {
        return installed && settings.getBoolean(key, true);
    }
}
