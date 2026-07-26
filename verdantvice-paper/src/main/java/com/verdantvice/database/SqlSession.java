package com.verdantvice.database;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A connection-scoped API which deliberately exposes only prepared statements.
 * Instances are valid only for the duration of the enclosing database callback.
 */
public final class SqlSession {
    private final Connection connection;
    private final DatabaseType databaseType;

    SqlSession(Connection connection, DatabaseType databaseType) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.databaseType = Objects.requireNonNull(databaseType, "databaseType");
    }

    public DatabaseType databaseType() {
        return databaseType;
    }

    public int update(String sql, Object... arguments) throws SQLException {
        validateSql(sql);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, arguments);
            return statement.executeUpdate();
        }
    }

    public long insertReturningKey(String sql, Object... arguments) throws SQLException {
        validateSql(sql);
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(statement, arguments);
            int affected = statement.executeUpdate();
            if (affected != 1) {
                throw new SQLException("Insert affected " + affected + " rows instead of exactly one");
            }
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Database did not return a generated key");
                }
                return keys.getLong(1);
            }
        }
    }

    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... arguments) throws SQLException {
        validateSql(sql);
        Objects.requireNonNull(mapper, "mapper");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, arguments);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (resultSet.next()) {
                    rows.add(Objects.requireNonNull(mapper.map(resultSet), "Row mapper returned null"));
                }
                return List.copyOf(rows);
            }
        }
    }

    public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... arguments) throws SQLException {
        validateSql(sql);
        Objects.requireNonNull(mapper, "mapper");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, arguments);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                T result = Objects.requireNonNull(mapper.map(resultSet), "Row mapper returned null");
                if (resultSet.next()) {
                    throw new SQLException("Expected at most one row");
                }
                return Optional.of(result);
            }
        }
    }

    public boolean exists(String sql, Object... arguments) throws SQLException {
        validateSql(sql);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, arguments);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    public int[] batch(String sql, List<? extends List<?>> argumentSets) throws SQLException {
        validateSql(sql);
        Objects.requireNonNull(argumentSets, "argumentSets");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (List<?> arguments : argumentSets) {
                bind(statement, arguments.toArray());
                statement.addBatch();
            }
            return statement.executeBatch();
        }
    }

    private static void validateSql(String sql) {
        Objects.requireNonNull(sql, "sql");
        if (sql.isBlank()) {
            throw new IllegalArgumentException("SQL must not be blank");
        }
        if (sql.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("SQL must not contain NUL characters");
        }
    }

    private static void bind(PreparedStatement statement, Object[] arguments) throws SQLException {
        Objects.requireNonNull(arguments, "arguments");
        for (int index = 0; index < arguments.length; index++) {
            bindOne(statement, index + 1, arguments[index]);
        }
    }

    private static void bindOne(PreparedStatement statement, int index, Object value) throws SQLException {
        if (value instanceof SqlNull sqlNull) {
            statement.setNull(index, sqlNull.type().getVendorTypeNumber());
        } else if (value == null) {
            statement.setNull(index, JDBCType.NULL.getVendorTypeNumber());
        } else if (value instanceof UUID uuid) {
            statement.setString(index, uuid.toString());
        } else if (value instanceof Instant instant) {
            statement.setLong(index, instant.toEpochMilli());
        } else if (value instanceof Enum<?> enumeration) {
            statement.setString(index, enumeration.name());
        } else if (value instanceof Boolean bool) {
            statement.setInt(index, bool ? 1 : 0);
        } else if (value instanceof byte[] bytes) {
            statement.setBytes(index, Arrays.copyOf(bytes, bytes.length));
        } else if (value instanceof BigDecimal decimal) {
            statement.setBigDecimal(index, decimal);
        } else if (value instanceof Character character) {
            statement.setString(index, character.toString());
        } else {
            statement.setObject(index, value);
        }
    }
}
