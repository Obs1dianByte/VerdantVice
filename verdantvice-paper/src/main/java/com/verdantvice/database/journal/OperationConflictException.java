package com.verdantvice.database.journal;

import com.verdantvice.database.DatabaseException;

public final class OperationConflictException extends DatabaseException {
    public OperationConflictException(String message) {
        super(message);
    }
}
