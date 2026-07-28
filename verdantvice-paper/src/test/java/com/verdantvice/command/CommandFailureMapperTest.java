package com.verdantvice.command;

import com.verdantvice.database.DatabaseUnavailableException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandFailureMapperTest {
    @Test
    void overlappingFarmUsesDomainMessageWithoutStackTrace() {
        var mapping = CommandFailureMapper.map(
                new IllegalArgumentException("farm_region_overlap")
        );
        assertEquals("errors.farm-region-overlap", mapping.translationKey());
        assertFalse(mapping.logStackTrace());
    }

    @Test
    void blockedManagerTableIsNormalPlayerError() {
        var mapping = CommandFailureMapper.map(
                new IllegalStateException("manager_table_blocked")
        );
        assertEquals("errors.manager-table-blocked", mapping.translationKey());
        assertFalse(mapping.logStackTrace());
    }

    @Test
    void trueDatabaseOutageStillUsesDatabaseUnavailable() {
        var mapping = CommandFailureMapper.map(
                new DatabaseUnavailableException("offline")
        );
        assertEquals("errors.database-unavailable", mapping.translationKey());
        assertTrue(mapping.logStackTrace());
    }

    @Test
    void permissionAndInvalidItemHaveSpecificMappings() {
        assertEquals(
                "errors.no-permission",
                CommandFailureMapper.map(new IllegalStateException("permission_denied"))
                        .translationKey()
        );
        assertEquals(
                "errors.invalid-item",
                CommandFailureMapper.map(new IllegalArgumentException("invalid_item"))
                        .translationKey()
        );
    }
}
