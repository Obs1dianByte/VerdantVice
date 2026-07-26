package com.verdantvice.database.journal;

import com.verdantvice.database.DatabaseException;

public final class OperationInProgressException extends DatabaseException {
    public OperationInProgressException(String message) {
        super(message);
    }
}
