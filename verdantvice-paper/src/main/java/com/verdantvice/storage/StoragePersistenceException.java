package com.verdantvice.storage;

import com.verdantvice.farm.StorageLinkResult;

import java.util.Objects;

/** A typed storage race detected inside the database transaction. */
public final class StoragePersistenceException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final StorageLinkResult.Status status;

    public StoragePersistenceException(StorageLinkResult.Status status) {
        super(Objects.requireNonNull(status, "status").name().toLowerCase());
        this.status = status;
    }

    public StorageLinkResult.Status status() {
        return status;
    }
}
