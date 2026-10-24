package com.verdantvice.farm;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StorageTypeTest {
    @Test
    void everyStorageTypeParsesCaseInsensitivelyWithHyphensOrUnderscores() {
        for (StorageType type : StorageType.values()) {
            assertEquals(type, StorageType.parse(type.argument()).orElseThrow());
            assertEquals(type, StorageType.parse(type.name().toLowerCase()).orElseThrow());
            assertEquals(type, StorageType.parse(type.argument().toUpperCase()).orElseThrow());
        }
        assertTrue(StorageType.parse("unknown-storage").isEmpty());
    }

    @Test
    void argumentsContainEveryStorageTypeExactlyOnce() {
        assertEquals(StorageType.values().length, StorageType.arguments().size());
        assertEquals(Set.copyOf(StorageType.arguments()).size(), StorageType.arguments().size());
        assertTrue(StorageType.arguments().contains("salary-storage"));
        assertTrue(StorageType.arguments().contains("general-storage"));
    }
}
