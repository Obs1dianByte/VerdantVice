package com.verdantvice.database.item;

import com.verdantvice.database.DatabaseManager;
import com.verdantvice.database.DatabaseType;
import com.verdantvice.database.SqlNull;
import com.verdantvice.database.SqlSession;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.JDBCType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class ItemRegistryRepository {
    private static final String COLUMNS = """
            instance_id, item_type, item_id, batch_id, owner_uuid, quality, strain_id,
            created_at, updated_at, signature, schema_version, registry_state,
            reservation_token, reserved_by, reserved_until, consumed_at,
            consumed_by_operation, last_operation_id, metadata_json, row_version
            """.replace('\n', ' ').strip();

    private final DatabaseManager database;

    public ItemRegistryRepository(DatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public CompletableFuture<RegistrationResult> register(ItemRegistration registration) {
        Objects.requireNonNull(registration, "registration");
        return database.transaction(session -> register(session, registration));
    }

    public RegistrationResult register(SqlSession session, ItemRegistration registration) throws SQLException {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(registration, "registration");
        Instant now = Instant.now();
        String verb = session.databaseType() == DatabaseType.SQLITE
                ? "INSERT OR IGNORE"
                : "INSERT IGNORE";
        int inserted = session.update(verb + """
                         INTO item_registry (
                            instance_id, item_type, item_id, batch_id, owner_uuid, quality, strain_id,
                            created_at, updated_at, signature, schema_version, registry_state,
                            reservation_token, reserved_by, reserved_until, consumed_at,
                            consumed_by_operation, last_operation_id, metadata_json, row_version
                         ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                registration.instanceId(),
                registration.itemType(),
                registration.itemId(),
                nullable(registration.batchId(), JDBCType.VARCHAR),
                nullable(registration.ownerUuid(), JDBCType.VARCHAR),
                nullable(registration.quality(), JDBCType.DOUBLE),
                nullable(registration.strainId(), JDBCType.VARCHAR),
                registration.createdAt(),
                now,
                registration.signature(),
                registration.schemaVersion(),
                ItemRegistryState.ACTIVE,
                SqlNull.of(JDBCType.VARCHAR),
                SqlNull.of(JDBCType.VARCHAR),
                SqlNull.of(JDBCType.BIGINT),
                SqlNull.of(JDBCType.BIGINT),
                SqlNull.of(JDBCType.VARCHAR),
                SqlNull.of(JDBCType.VARCHAR),
                nullable(registration.metadataJson(), JDBCType.LONGVARCHAR),
                0L);
        ItemRegistryRecord record = require(session, registration.instanceId(), true);
        if (inserted == 1) {
            return new RegistrationResult(RegistrationStatus.REGISTERED, record);
        }
        return new RegistrationResult(
                sameIdentity(registration, record)
                        ? RegistrationStatus.ALREADY_REGISTERED
                        : RegistrationStatus.CONFLICT,
                record
        );
    }

    public CompletableFuture<ItemCasResult> reserve(
            UUID instanceId,
            long expectedVersion,
            String expectedSignature,
            UUID reservationToken,
            String reservedBy,
            Instant reservedUntil,
            String operationId
    ) {
        validateCas(instanceId, expectedVersion, expectedSignature, operationId);
        Objects.requireNonNull(reservationToken, "reservationToken");
        String safeReservedBy = requireLength(reservedBy, "reservedBy", 128);
        Objects.requireNonNull(reservedUntil, "reservedUntil");
        Instant now = Instant.now();
        if (!reservedUntil.isAfter(now) || reservedUntil.isAfter(now.plus(Duration.ofDays(7)))) {
            throw new IllegalArgumentException("reservedUntil must be in the future and at most 7 days away");
        }
        return database.transaction(session -> reserve(
                session, instanceId, expectedVersion, expectedSignature,
                reservationToken, safeReservedBy, reservedUntil, operationId, now
        ));
    }

    public ItemCasResult reserve(
            SqlSession session,
            UUID instanceId,
            long expectedVersion,
            String expectedSignature,
            UUID reservationToken,
            String reservedBy,
            Instant reservedUntil,
            String operationId,
            Instant now
    ) throws SQLException {
        validateCas(instanceId, expectedVersion, expectedSignature, operationId);
        Objects.requireNonNull(reservationToken, "reservationToken");
        requireLength(reservedBy, "reservedBy", 128);
        Objects.requireNonNull(reservedUntil, "reservedUntil");
        Objects.requireNonNull(now, "now");
        String normalizedSignature = expectedSignature.toLowerCase(Locale.ROOT);
        int updated = session.update("""
                        UPDATE item_registry
                        SET registry_state = ?, reservation_token = ?, reserved_by = ?,
                            reserved_until = ?, last_operation_id = ?, updated_at = ?,
                            row_version = row_version + 1
                        WHERE instance_id = ? AND signature = ? AND row_version = ?
                            AND (registry_state = ? OR
                                (registry_state = ? AND (reserved_until IS NULL OR reserved_until <= ?)))
                        """,
                ItemRegistryState.RESERVED, reservationToken, reservedBy,
                reservedUntil, operationId, now,
                instanceId, normalizedSignature, expectedVersion,
                ItemRegistryState.ACTIVE, ItemRegistryState.RESERVED, now);
        ItemRegistryRecord current = find(session, instanceId, true).orElse(null);
        if (updated == 1) {
            return new ItemCasResult(CasStatus.APPLIED, current);
        }
        if (current != null
                && current.state() == ItemRegistryState.RESERVED
                && reservationToken.equals(current.reservationToken())
                && operationId.equals(current.lastOperationId())) {
            return new ItemCasResult(CasStatus.ALREADY_APPLIED, current);
        }
        return new ItemCasResult(CasStatus.REJECTED, current);
    }

    public CompletableFuture<ItemCasResult> consume(
            UUID instanceId,
            long expectedVersion,
            String expectedSignature,
            UUID reservationToken,
            String operationId,
            Instant consumedAt
    ) {
        validateCas(instanceId, expectedVersion, expectedSignature, operationId);
        Objects.requireNonNull(reservationToken, "reservationToken");
        Objects.requireNonNull(consumedAt, "consumedAt");
        return database.transaction(session -> consume(
                session, instanceId, expectedVersion, expectedSignature,
                reservationToken, operationId, consumedAt
        ));
    }

    public ItemCasResult consume(
            SqlSession session,
            UUID instanceId,
            long expectedVersion,
            String expectedSignature,
            UUID reservationToken,
            String operationId,
            Instant consumedAt
    ) throws SQLException {
        validateCas(instanceId, expectedVersion, expectedSignature, operationId);
        Objects.requireNonNull(reservationToken, "reservationToken");
        Objects.requireNonNull(consumedAt, "consumedAt");
        String normalizedSignature = expectedSignature.toLowerCase(Locale.ROOT);
        int updated = session.update("""
                        UPDATE item_registry
                        SET registry_state = ?, reservation_token = NULL, reserved_by = NULL,
                            reserved_until = NULL, consumed_at = ?,
                            consumed_by_operation = ?, last_operation_id = ?,
                            updated_at = ?, row_version = row_version + 1
                        WHERE instance_id = ? AND signature = ? AND row_version = ?
                            AND registry_state = ? AND reservation_token = ?
                        """,
                ItemRegistryState.CONSUMED, consumedAt, operationId, operationId, consumedAt,
                instanceId, normalizedSignature, expectedVersion,
                ItemRegistryState.RESERVED, reservationToken);
        ItemRegistryRecord current = find(session, instanceId, true).orElse(null);
        if (updated == 1) {
            return new ItemCasResult(CasStatus.APPLIED, current);
        }
        if (current != null
                && current.state() == ItemRegistryState.CONSUMED
                && operationId.equals(current.consumedByOperation())) {
            return new ItemCasResult(CasStatus.ALREADY_APPLIED, current);
        }
        return new ItemCasResult(CasStatus.REJECTED, current);
    }

    public CompletableFuture<ItemCasResult> release(
            UUID instanceId,
            long expectedVersion,
            String expectedSignature,
            UUID reservationToken,
            String operationId,
            Instant releasedAt
    ) {
        validateCas(instanceId, expectedVersion, expectedSignature, operationId);
        Objects.requireNonNull(reservationToken, "reservationToken");
        Objects.requireNonNull(releasedAt, "releasedAt");
        return database.transaction(session -> release(
                session, instanceId, expectedVersion, expectedSignature,
                reservationToken, operationId, releasedAt
        ));
    }

    public ItemCasResult release(
            SqlSession session,
            UUID instanceId,
            long expectedVersion,
            String expectedSignature,
            UUID reservationToken,
            String operationId,
            Instant releasedAt
    ) throws SQLException {
        validateCas(instanceId, expectedVersion, expectedSignature, operationId);
        Objects.requireNonNull(reservationToken, "reservationToken");
        Objects.requireNonNull(releasedAt, "releasedAt");
        String normalizedSignature = expectedSignature.toLowerCase(Locale.ROOT);
        int updated = session.update("""
                        UPDATE item_registry
                        SET registry_state = ?, reservation_token = NULL, reserved_by = NULL,
                            reserved_until = NULL, last_operation_id = ?, updated_at = ?,
                            row_version = row_version + 1
                        WHERE instance_id = ? AND signature = ? AND row_version = ?
                            AND registry_state = ? AND reservation_token = ?
                        """,
                ItemRegistryState.ACTIVE, operationId, releasedAt,
                instanceId, normalizedSignature, expectedVersion,
                ItemRegistryState.RESERVED, reservationToken);
        ItemRegistryRecord current = find(session, instanceId, true).orElse(null);
        if (updated == 1) {
            return new ItemCasResult(CasStatus.APPLIED, current);
        }
        if (current != null
                && current.state() == ItemRegistryState.ACTIVE
                && operationId.equals(current.lastOperationId())) {
            return new ItemCasResult(CasStatus.ALREADY_APPLIED, current);
        }
        return new ItemCasResult(CasStatus.REJECTED, current);
    }

    public CompletableFuture<Optional<ItemRegistryRecord>> find(UUID instanceId) {
        Objects.requireNonNull(instanceId, "instanceId");
        return database.read(session -> find(session, instanceId, false));
    }

    public Optional<ItemRegistryRecord> find(
            SqlSession session,
            UUID instanceId,
            boolean forUpdate
    ) throws SQLException {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(instanceId, "instanceId");
        String suffix = forUpdate && session.databaseType().isNetworkDatabase() ? " FOR UPDATE" : "";
        return session.queryOne(
                "SELECT " + COLUMNS + " FROM item_registry WHERE instance_id = ?" + suffix,
                ItemRegistryRepository::map,
                instanceId
        );
    }

    private ItemRegistryRecord require(
            SqlSession session,
            UUID instanceId,
            boolean forUpdate
    ) throws SQLException {
        return find(session, instanceId, forUpdate).orElseThrow(() ->
                new SQLException("Item registry row disappeared for " + instanceId));
    }

    private static ItemRegistryRecord map(ResultSet resultSet) throws SQLException {
        return new ItemRegistryRecord(
                uuid(resultSet.getString("instance_id")),
                resultSet.getString("item_type"),
                resultSet.getString("item_id"),
                nullableUuid(resultSet.getString("batch_id")),
                nullableUuid(resultSet.getString("owner_uuid")),
                nullableDouble(resultSet, "quality"),
                resultSet.getString("strain_id"),
                Instant.ofEpochMilli(resultSet.getLong("created_at")),
                Instant.ofEpochMilli(resultSet.getLong("updated_at")),
                resultSet.getString("signature"),
                resultSet.getInt("schema_version"),
                parseState(resultSet.getString("registry_state")),
                nullableUuid(resultSet.getString("reservation_token")),
                resultSet.getString("reserved_by"),
                nullableInstant(resultSet, "reserved_until"),
                nullableInstant(resultSet, "consumed_at"),
                resultSet.getString("consumed_by_operation"),
                resultSet.getString("last_operation_id"),
                resultSet.getString("metadata_json"),
                resultSet.getLong("row_version")
        );
    }

    private static ItemRegistryState parseState(String state) throws SQLException {
        try {
            return ItemRegistryState.valueOf(state);
        } catch (IllegalArgumentException exception) {
            throw new SQLException("Unknown item registry state: " + state, exception);
        }
    }

    private static UUID uuid(String value) throws SQLException {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new SQLException("Invalid UUID stored in item registry: " + value, exception);
        }
    }

    private static UUID nullableUuid(String value) throws SQLException {
        return value == null ? null : uuid(value);
    }

    private static Double nullableDouble(ResultSet resultSet, String column) throws SQLException {
        double value = resultSet.getDouble(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    private static boolean sameIdentity(ItemRegistration registration, ItemRegistryRecord record) {
        return registration.instanceId().equals(record.instanceId())
                && registration.itemType().equals(record.itemType())
                && registration.itemId().equals(record.itemId())
                && Objects.equals(registration.batchId(), record.batchId())
                && Objects.equals(registration.ownerUuid(), record.ownerUuid())
                && Objects.equals(registration.quality(), record.quality())
                && Objects.equals(registration.strainId(), record.strainId())
                && registration.createdAt().equals(record.createdAt())
                && registration.schemaVersion() == record.schemaVersion()
                && Objects.equals(registration.metadataJson(), record.metadataJson())
                && constantTimeEquals(registration.signature(), record.signature());
    }

    private static void validateCas(
            UUID instanceId,
            long expectedVersion,
            String expectedSignature,
            String operationId
    ) {
        Objects.requireNonNull(instanceId, "instanceId");
        if (expectedVersion < 0L) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        Objects.requireNonNull(expectedSignature, "expectedSignature");
        if (!expectedSignature.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("expectedSignature must be a SHA-256 HMAC hex digest");
        }
        String normalizedOperationId = requireLength(operationId, "operationId", 128);
        if (!operationId.equals(normalizedOperationId)) {
            throw new IllegalArgumentException("operationId must not contain surrounding whitespace");
        }
    }

    private static Object nullable(Object value, JDBCType type) {
        return value == null ? SqlNull.of(type) : value;
    }

    private static String requireLength(String value, String name, int maximum) {
        Objects.requireNonNull(value, name);
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maximum || normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " must contain 1 to " + maximum + " safe characters");
        }
        return normalized;
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
}
