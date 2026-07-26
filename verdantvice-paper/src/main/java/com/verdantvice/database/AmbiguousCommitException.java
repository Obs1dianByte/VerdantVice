package com.verdantvice.database;

/**
 * Signals that a driver reported a failure while committing. The server may
 * already have committed the transaction, so blindly replaying it is unsafe.
 */
public final class AmbiguousCommitException extends DatabaseException {
    public AmbiguousCommitException(String message, Throwable cause) {
        super(message, cause);
    }
}
