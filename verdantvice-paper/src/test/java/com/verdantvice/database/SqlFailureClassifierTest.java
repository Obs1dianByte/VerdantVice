package com.verdantvice.database;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SqlFailureClassifierTest {
    @Test
    void sqliteForeignKeyIsPermanentIntegrityFailure() {
        SQLException failure = new SQLException(
                "[SQLITE_CONSTRAINT_FOREIGNKEY] FOREIGN KEY constraint failed",
                null,
                19);
        assertEquals(
                DatabaseIntegrityException.ConstraintType.FOREIGN_KEY,
                SqlFailureClassifier.integrity(failure).orElseThrow());
        assertFalse(SqlFailureClassifier.transientFailure(failure));
    }

    @Test
    void sqliteUniqueIsPermanentIntegrityFailure() {
        SQLException failure = new SQLException(
                "[SQLITE_CONSTRAINT_UNIQUE] UNIQUE constraint failed: t.value",
                null,
                19);
        assertEquals(
                DatabaseIntegrityException.ConstraintType.UNIQUE,
                SqlFailureClassifier.integrity(failure).orElseThrow());
    }

    @Test
    void mysqlForeignKeyUsesVendorCode() {
        SQLException failure = new SQLException(
                "Cannot add or update a child row: a foreign key constraint fails",
                "23000",
                1452);
        assertEquals(
                DatabaseIntegrityException.ConstraintType.FOREIGN_KEY,
                SqlFailureClassifier.integrity(failure).orElseThrow());
    }

    @Test
    void mariaDbDuplicateUsesVendorCode() {
        SQLException failure = new SQLException(
                "Duplicate entry", "23000", 1062);
        assertEquals(
                DatabaseIntegrityException.ConstraintType.UNIQUE,
                SqlFailureClassifier.integrity(failure).orElseThrow());
    }

    @Test
    void sqliteBusyIsTransient() {
        SQLException failure = new SQLException("database is locked", null, 5);
        assertTrue(SqlFailureClassifier.transientFailure(failure));
        assertTrue(SqlFailureClassifier.integrity(failure).isEmpty());
    }

    @Test
    void sqliteExtendedConstraintCodesAreClassifiedWithoutRetry() {
        assertEquals(DatabaseIntegrityException.ConstraintType.FOREIGN_KEY,
                SqlFailureClassifier.integrity(new SQLException(
                        "constraint", null, 787)).orElseThrow());
        assertEquals(DatabaseIntegrityException.ConstraintType.UNIQUE,
                SqlFailureClassifier.integrity(new SQLException(
                        "constraint", null, 2067)).orElseThrow());
        assertEquals(DatabaseIntegrityException.ConstraintType.NOT_NULL,
                SqlFailureClassifier.integrity(new SQLException(
                        "constraint", null, 1299)).orElseThrow());
        assertEquals(DatabaseIntegrityException.ConstraintType.CHECK,
                SqlFailureClassifier.integrity(new SQLException(
                        "constraint", null, 275)).orElseThrow());
    }

    @Test
    void sqliteExtendedBusyCodeRemainsTransient() {
        assertTrue(SqlFailureClassifier.transientFailure(
                new SQLException("busy snapshot", null, 517)));
    }

    @Test
    void transientConnectionExceptionRemainsTransient() {
        SQLException failure = new SQLTransientConnectionException(
                "temporary network failure", "08001");
        assertTrue(SqlFailureClassifier.transientFailure(failure));
        assertInstanceOf(
                DatabaseIntegrityException.class,
                SqlFailureClassifier.integrityException(new SQLException(
                        "FOREIGN KEY constraint failed", "23000", 19)));
    }
}
