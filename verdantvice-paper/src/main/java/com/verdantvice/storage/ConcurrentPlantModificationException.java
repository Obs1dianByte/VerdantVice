package com.verdantvice.storage;

import com.verdantvice.database.DatabaseException;

/**
 * Logical optimistic-locking conflict. This is deliberately not an SQL
 * exception, so the database retry policy must not replay it as a transient
 * availability failure.
 */
public final class ConcurrentPlantModificationException extends DatabaseException {
    public ConcurrentPlantModificationException(String message) {
        super(message);
    }
}
