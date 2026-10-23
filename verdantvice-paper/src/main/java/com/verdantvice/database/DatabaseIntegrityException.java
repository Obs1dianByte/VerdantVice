package com.verdantvice.database;

import java.util.Objects;

/** Permanent relational-integrity failure that must never be retried. */
public final class DatabaseIntegrityException extends DatabaseException {
    private static final long serialVersionUID = 1L;

    private final ConstraintType constraintType;
    private final String sqlState;
    private final int vendorCode;

    public DatabaseIntegrityException(
            String message,
            ConstraintType constraintType,
            String sqlState,
            int vendorCode,
            Throwable cause
    ) {
        super(message, cause);
        this.constraintType = Objects.requireNonNull(constraintType, "constraintType");
        this.sqlState = sqlState;
        this.vendorCode = vendorCode;
    }

    public ConstraintType constraintType() { return constraintType; }
    public String sqlState() { return sqlState; }
    public int vendorCode() { return vendorCode; }

    public enum ConstraintType { FOREIGN_KEY, UNIQUE, NOT_NULL, CHECK, OTHER }
}
