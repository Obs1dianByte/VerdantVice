package com.verdantvice.farm;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public enum StorageType {
    SEED_STORAGE,
    TOOL_STORAGE,
    FRESH_HARVEST_STORAGE,
    DRY_PRODUCT_STORAGE,
    PACKAGED_PRODUCT_STORAGE,
    SALARY_STORAGE,
    WATER_STORAGE,
    GENERAL_STORAGE;

    public String argument() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static Optional<StorageType> parse(String source) {
        if (source == null || source.isBlank()) {
            return Optional.empty();
        }
        String normalized = source.strip()
                .toUpperCase(Locale.ROOT)
                .replace('-', '_');
        try {
            return Optional.of(valueOf(normalized));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    public static List<String> arguments() {
        return Arrays.stream(values()).map(StorageType::argument).toList();
    }
}
