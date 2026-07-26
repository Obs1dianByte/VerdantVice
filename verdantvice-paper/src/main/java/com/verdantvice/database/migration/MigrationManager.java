package com.verdantvice.database.migration;

import com.verdantvice.database.DatabaseType;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class MigrationManager {
    private static final String HISTORY_TABLE = "vv_schema_history";

    private final DataSource dataSource;
    private final DatabaseType databaseType;
    private final Logger logger;
    private final List<SchemaMigration> migrations;

    public MigrationManager(DataSource dataSource, DatabaseType databaseType, Logger logger) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.databaseType = Objects.requireNonNull(databaseType, "databaseType");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.migrations = validateCatalog(MigrationCatalog.forDatabase(databaseType));
    }

    public MigrationReport migrate() {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(false);
            connection.setAutoCommit(true);
            ensureHistoryTable(connection);
            Map<Integer, HistoryRow> history = readHistory(connection);
            validateHistory(history);

            List<Integer> applied = new ArrayList<>();
            for (SchemaMigration migration : migrations) {
                if (!history.containsKey(migration.version())) {
                    apply(connection, migration);
                    applied.add(migration.version());
                }
            }
            int currentVersion = migrations.isEmpty() ? 0 : migrations.get(migrations.size() - 1).version();
            return new MigrationReport(currentVersion, applied);
        } catch (MigrationIntegrityException exception) {
            throw exception;
        } catch (SQLException exception) {
            throw new MigrationIntegrityException("Unable to inspect or migrate the database schema", exception);
        }
    }

    private void ensureHistoryTable(Connection connection) throws SQLException {
        String suffix = databaseType == DatabaseType.SQLITE
                ? ""
                : " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
        String sql = """
                CREATE TABLE IF NOT EXISTS vv_schema_history (
                    version INTEGER NOT NULL PRIMARY KEY,
                    description VARCHAR(200) NOT NULL,
                    checksum CHAR(64) NOT NULL,
                    installed_at BIGINT NOT NULL,
                    execution_time_ms BIGINT NOT NULL,
                    success SMALLINT NOT NULL
                )%s
                """.formatted(suffix);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        }
    }

    private Map<Integer, HistoryRow> readHistory(Connection connection) throws SQLException {
        Map<Integer, HistoryRow> rows = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT version, description, checksum, success FROM " + HISTORY_TABLE + " ORDER BY version"
        ); ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                HistoryRow row = new HistoryRow(
                        resultSet.getInt("version"),
                        resultSet.getString("description"),
                        resultSet.getString("checksum"),
                        resultSet.getInt("success") == 1
                );
                if (rows.put(row.version(), row) != null) {
                    throw new MigrationIntegrityException(
                            "Duplicate schema history entry for migration " + row.version()
                    );
                }
            }
        }
        return rows;
    }

    private void validateHistory(Map<Integer, HistoryRow> history) {
        Map<Integer, SchemaMigration> known = new HashMap<>();
        for (SchemaMigration migration : migrations) {
            known.put(migration.version(), migration);
        }
        int highestApplied = history.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);

        for (HistoryRow row : history.values()) {
            if (!row.success()) {
                throw new MigrationIntegrityException(
                        "Migration " + row.version() + " ('" + row.description()
                                + "') is marked as failed. Restore a backup or repair the schema explicitly; "
                                + "VerdantVice will not continue against a partially migrated database."
                );
            }
            SchemaMigration expected = known.get(row.version());
            if (expected == null) {
                throw new MigrationIntegrityException(
                        "Database contains unknown migration version " + row.version()
                                + "; it may have been created by a newer or incompatible plugin build"
                );
            }
            if (!constantTimeEquals(expected.checksum(), row.checksum())) {
                throw new MigrationIntegrityException(
                        "Checksum mismatch for migration " + row.version() + " ('"
                                + expected.description() + "'). Applied migrations must never be edited."
                );
            }
        }

        for (SchemaMigration migration : migrations) {
            if (migration.version() < highestApplied && !history.containsKey(migration.version())) {
                throw new MigrationIntegrityException(
                        "Schema history has a gap: migration " + migration.version()
                                + " is missing below already applied version " + highestApplied
                );
            }
        }
    }

    private void apply(Connection connection, SchemaMigration migration) throws SQLException {
        logger.log(Level.INFO, "Applying database migration {0}: {1}",
                new Object[]{migration.version(), migration.description()});
        long startedNanos = System.nanoTime();
        insertFailureMarker(connection, migration);

        boolean previousAutoCommit = connection.getAutoCommit();
        try {
            connection.setAutoCommit(false);
            for (String sql : migration.statements()) {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.execute();
                }
            }
            long executionMillis = elapsedMillis(startedNanos);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE " + HISTORY_TABLE
                            + " SET success = 1, execution_time_ms = ? WHERE version = ? AND success = 0"
            )) {
                statement.setLong(1, executionMillis);
                statement.setInt(2, migration.version());
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("Migration completion marker was not updated");
                }
            }
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            rollbackQuietly(connection, migration.version());
            recordFailureDuration(connection, migration.version(), elapsedMillis(startedNanos));
            throw new MigrationIntegrityException(
                    "Migration " + migration.version() + " failed and has been marked as corrupted",
                    exception
            );
        } finally {
            try {
                connection.setAutoCommit(previousAutoCommit);
            } catch (SQLException exception) {
                logger.log(Level.WARNING, "Unable to restore migration connection auto-commit state", exception);
            }
        }
    }

    private void insertFailureMarker(Connection connection, SchemaMigration migration) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + HISTORY_TABLE
                        + " (version, description, checksum, installed_at, execution_time_ms, success)"
                        + " VALUES (?, ?, ?, ?, ?, ?)"
        )) {
            statement.setInt(1, migration.version());
            statement.setString(2, migration.description());
            statement.setString(3, migration.checksum());
            statement.setLong(4, System.currentTimeMillis());
            statement.setLong(5, 0L);
            statement.setInt(6, 0);
            statement.executeUpdate();
        }
    }

    private void recordFailureDuration(Connection connection, int version, long durationMillis) {
        try {
            connection.setAutoCommit(true);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE " + HISTORY_TABLE
                            + " SET execution_time_ms = ? WHERE version = ? AND success = 0"
            )) {
                statement.setLong(1, durationMillis);
                statement.setInt(2, version);
                statement.executeUpdate();
            }
        } catch (SQLException exception) {
            logger.log(Level.SEVERE,
                    "Could not update the failure marker for migration " + version
                            + "; startup will still fail when the marker is read",
                    exception);
        }
    }

    private void rollbackQuietly(Connection connection, int version) {
        try {
            connection.rollback();
        } catch (SQLException exception) {
            logger.log(Level.SEVERE, "Rollback failed for migration " + version, exception);
        }
    }

    private static List<SchemaMigration> validateCatalog(List<SchemaMigration> source) {
        List<SchemaMigration> sorted = source.stream()
                .sorted(java.util.Comparator.comparingInt(SchemaMigration::version))
                .toList();
        Set<Integer> versions = new HashSet<>();
        int previous = 0;
        for (SchemaMigration migration : sorted) {
            if (!versions.add(migration.version())) {
                throw new IllegalStateException("Duplicate migration version " + migration.version());
            }
            if (migration.version() != previous + 1) {
                throw new IllegalStateException(
                        "Migration versions must be contiguous; expected " + (previous + 1)
                                + " but found " + migration.version()
                );
            }
            previous = migration.version();
        }
        return List.copyOf(sorted);
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII)
        );
    }

    private record HistoryRow(int version, String description, String checksum, boolean success) {
    }
}
