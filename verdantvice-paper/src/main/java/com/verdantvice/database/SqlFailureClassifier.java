package com.verdantvice.database;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.Locale;
import java.util.Optional;

/** Cross-driver SQL failure classification for SQLite, MySQL and MariaDB. */
public final class SqlFailureClassifier {
    private SqlFailureClassifier() {}

    public static boolean transientFailure(SQLException exception) {
        for (SQLException current = exception; current != null; current = current.getNextException()) {
            if (current instanceof SQLTransientException || current instanceof SQLRecoverableException) return true;
            String state = current.getSQLState();
            if (state != null && (state.startsWith("08") || state.startsWith("40") || state.startsWith("HYT"))) return true;
            int code = current.getErrorCode();
            int sqliteBaseCode = code & 0xff;
            if (sqliteBaseCode == 5 || sqliteBaseCode == 6
                    || code == 1_205 || code == 1_213) return true;
        }
        return false;
    }

    public static Optional<DatabaseIntegrityException.ConstraintType> integrity(SQLException exception) {
        for (SQLException current = exception; current != null; current = current.getNextException()) {
            Optional<DatabaseIntegrityException.ConstraintType> value = classifyOne(current);
            if (value.isPresent()) return value;
        }
        return Optional.empty();
    }

    public static DatabaseIntegrityException integrityException(SQLException exception) {
        DatabaseIntegrityException.ConstraintType type = integrity(exception)
                .orElse(DatabaseIntegrityException.ConstraintType.OTHER);
        SQLException source = firstIntegrity(exception).orElse(exception);
        return new DatabaseIntegrityException(
                "Database integrity constraint failed: " + type,
                type, source.getSQLState(), source.getErrorCode(), exception);
    }

    private static Optional<SQLException> firstIntegrity(SQLException exception) {
        for (SQLException current = exception; current != null; current = current.getNextException()) {
            if (classifyOne(current).isPresent()) return Optional.of(current);
        }
        return Optional.empty();
    }

    private static Optional<DatabaseIntegrityException.ConstraintType> classifyOne(SQLException exception) {
        String state = exception.getSQLState();
        int code = exception.getErrorCode();
        String message = Optional.ofNullable(exception.getMessage()).orElse("").toLowerCase(Locale.ROOT);
        boolean integrityState = state != null && state.startsWith("23");
        int sqliteBaseCode = code & 0xff;
        boolean sqliteConstraint = sqliteBaseCode == 19
                || message.contains("sqlite_constraint");
        boolean mysqlConstraint = code == 1_062 || code == 1_048 || code == 1_452
                || code == 1_451 || code == 1_364 || code == 3_811;
        if (!integrityState && !sqliteConstraint && !mysqlConstraint) return Optional.empty();
        if (code == 787 || code == 1_452 || code == 1_451
                || message.contains("foreign key")
                || message.contains("constraint_foreignkey")) {
            return Optional.of(DatabaseIntegrityException.ConstraintType.FOREIGN_KEY);
        }
        if (code == 1_555 || code == 2_067 || code == 1_062
                || message.contains("unique constraint")
                || message.contains("primary key")
                || message.contains("duplicate entry")
                || message.contains("constraint_unique")
                || message.contains("constraint_primarykey")) {
            return Optional.of(DatabaseIntegrityException.ConstraintType.UNIQUE);
        }
        if (code == 1_299 || code == 1_048 || code == 1_364
                || message.contains("not null constraint")
                || message.contains("cannot be null")) {
            return Optional.of(DatabaseIntegrityException.ConstraintType.NOT_NULL);
        }
        if (code == 275 || code == 3_811
                || message.contains("check constraint")
                || message.contains("constraint_check")) {
            return Optional.of(DatabaseIntegrityException.ConstraintType.CHECK);
        }
        return Optional.of(DatabaseIntegrityException.ConstraintType.OTHER);
    }
}
