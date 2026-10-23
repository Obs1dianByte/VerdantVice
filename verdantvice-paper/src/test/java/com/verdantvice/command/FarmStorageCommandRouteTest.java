package com.verdantvice.command;

import com.verdantvice.farm.StorageType;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FarmStorageCommandRouteTest {
    @Test
    void everyDocumentedStorageCommandHasValidArguments() {
        for (StorageType type : StorageType.values()) {
            FarmStorageCommandRoute.Result result = FarmStorageCommandRoute.resolve(
                    new String[] { "farm", "storage", type.argument() });
            assertEquals(FarmStorageCommandRoute.Action.LINK, result.action());
            assertEquals(type, result.type().orElseThrow());
        }
    }

    @Test
    void unlinkAndInvalidSyntaxRemainDistinct() {
        assertEquals(FarmStorageCommandRoute.Action.UNLINK,
                FarmStorageCommandRoute.resolve(
                        new String[] { "farm", "storage", "unlink" }).action());
        assertEquals(FarmStorageCommandRoute.Action.INVALID_TYPE,
                FarmStorageCommandRoute.resolve(
                        new String[] { "farm", "storage", "unknown" }).action());
        assertEquals(FarmStorageCommandRoute.Action.INVALID_USAGE,
                FarmStorageCommandRoute.resolve(
                        new String[] { "farm", "storage" }).action());
    }

    @Test
    void tabArgumentsContainEveryTypeAndUnlinkExactlyOnce() {
        var values = FarmStorageCommandRoute.arguments();
        assertEquals(StorageType.values().length + 1, values.size());
        assertEquals(values.size(), new HashSet<>(values).size());
        assertTrue(values.containsAll(StorageType.arguments()));
        assertTrue(values.contains("unlink"));
    }
}
