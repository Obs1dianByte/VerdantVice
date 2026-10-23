package com.verdantvice.command;

import com.verdantvice.herbalist.HerbalistTier;

import java.util.Locale;
import java.util.Optional;

record HerbalistSpawnRequest(Optional<HerbalistTier> tier, boolean force) {
    static HerbalistSpawnRequest parse(String[] arguments) {
        HerbalistTier tier = null;
        boolean force = false;
        for (int index = 2; index < arguments.length; index++) {
            String value = arguments[index];
            if (value.equalsIgnoreCase("--force")) {
                if (force) {
                    throw new IllegalArgumentException("duplicate_force");
                }
                force = true;
            } else if (tier == null) {
                tier = HerbalistTier.valueOf(value.toUpperCase(Locale.ROOT));
            } else {
                throw new IllegalArgumentException("spawnherbalist_usage");
            }
        }
        return new HerbalistSpawnRequest(Optional.ofNullable(tier), force);
    }
}
