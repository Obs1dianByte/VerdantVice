package com.verdantvice.database.migration;

import com.verdantvice.database.DatabaseException;

public final class MigrationIntegrityException extends DatabaseException {
    public MigrationIntegrityException(String message) {
        super(message);
    }

    public MigrationIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
