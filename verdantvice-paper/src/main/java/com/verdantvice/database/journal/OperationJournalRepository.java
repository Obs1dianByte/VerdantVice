package com.verdantvice.database.journal;

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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class OperationJournalRepository {
    private static final String COLUMNS = """
            operation_id, operation_type, actor_uuid, scope_key, request_hash,
            operation_state, claim_token, lease_until, result_json, error_code,
            attempt_count, created_at, updated_at, completed_at, row_version
            """.replace('\n', ' ').strip();

    private final DatabaseManager database;

    public OperationJournalRepository(DatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    /**
     * Executes database-only work and writes its result in the same transaction.
     * Repeating the same operation id and request hash returns the stored result.
     */
    public CompletableFuture<JournalExecution> executeTransactionally(
            OperationKey key,
            Duration lease,
            JournalWork work
    ) {
        validateLease(lease);
        Objects.requireNonNull(work, "work");
        return database.transaction(session -> {
            Instant now = Instant.now();
            UUID token = UUID.randomUUID();
            boolean acquired = insertPending(session, key, token, now.plus(lease), now);
            OperationEntry existing = null;
            if (!acquired) {
                existing = requireEntry(session, key.operationId(), true);
                validateIdentity(key, existing.key());
                if (existing.state() == OperationState.COMPLETED) {
                    return new JournalExecution(JournalExecution.Status.REPLAYED, existing.resultJson());
                }
                if (existing.state() == OperationState.PENDING
                        && existing.leaseUntil() != null
                        && existing.leaseUntil().isAfter(now)) {
                    throw new OperationInProgressException(
                            "Operation " + key.operationId() + " is already in progress"
                    );
                }
                acquired = reclaim(session, existing, token, now.plus(lease), now);
                if (!acquired) {
                    throw new OperationInProgressException(
                            "Operation " + key.operationId() + " was claimed concurrently"
                    );
                }
            }

            String result = normalizeResult(work.execute(session));
            int updated = session.update("""
                            UPDATE operation_journal
                            SET operation_state = ?, result_json = ?, error_code = NULL,
                                claim_token = NULL, lease_until = NULL, completed_at = ?,
                                updated_at = ?, row_version = row_version + 1
                            WHERE operation_id = ? AND request_hash = ?
                                AND operation_state = ? AND claim_token = ?
                            """,
                    OperationState.COMPLETED, result, now, now,
                    key.operationId(), key.requestHash(), OperationState.PENDING, token);
            if (updated != 1) {
                throw new OperationConflictException(
                        "Operation " + key.operationId() + " lost its journal claim"
                );
            }
            return new JournalExecution(JournalExecution.Status.EXECUTED, result);
        });
    }

    public CompletableFuture<OperationClaim> claim(OperationKey key, Duration lease) {
        validateLease(lease);
        return database.transaction(session -> {
            Instant now = Instant.now();
            UUID token = UUID.randomUUID();
            if (insertPending(session, key, token, now.plus(lease), now)) {
                return new OperationClaim(ClaimStatus.ACQUIRED, token, null, 0L);
            }

            OperationEntry entry = requireEntry(session, key.operationId(), true);
            validateIdentity(key, entry.key());
            if (entry.state() == OperationState.COMPLETED) {
                return new OperationClaim(ClaimStatus.COMPLETED, null, entry.resultJson(), entry.version());
            }
            if (entry.state() == OperationState.PENDING
                    && entry.leaseUntil() != null
                    && entry.leaseUntil().isAfter(now)) {
                return new OperationClaim(ClaimStatus.IN_PROGRESS, null, null, entry.version());
            }
            if (!reclaim(session, entry, token, now.plus(lease), now)) {
                return new OperationClaim(ClaimStatus.IN_PROGRESS, null, null, entry.version());
            }
            return new OperationClaim(ClaimStatus.ACQUIRED, token, null, entry.version() + 1L);
        });
    }

    public CompletableFuture<JournalExecution> complete(
            OperationKey key,
            UUID claimToken,
            String resultJson
    ) {
        Objects.requireNonNull(claimToken, "claimToken");
        String result = normalizeResult(resultJson);
        return database.transaction(session -> {
            Instant now = Instant.now();
            int updated = session.update("""
                            UPDATE operation_journal
                            SET operation_state = ?, result_json = ?, error_code = NULL,
                                claim_token = NULL, lease_until = NULL, completed_at = ?,
                                updated_at = ?, row_version = row_version + 1
                            WHERE operation_id = ? AND request_hash = ?
                                AND operation_state = ? AND claim_token = ?
                            """,
                    OperationState.COMPLETED, result, now, now,
                    key.operationId(), key.requestHash(), OperationState.PENDING, claimToken);
            if (updated == 1) {
                return new JournalExecution(JournalExecution.Status.EXECUTED, result);
            }
            OperationEntry entry = requireEntry(session, key.operationId(), true);
            validateIdentity(key, entry.key());
            if (entry.state() == OperationState.COMPLETED
                    && constantTimeEquals(result, entry.resultJson())) {
                return new JournalExecution(JournalExecution.Status.REPLAYED, entry.resultJson());
            }
            throw new OperationConflictException(
                    "Operation " + key.operationId() + " is not owned by this claim"
            );
        });
    }

    public CompletableFuture<Boolean> fail(
            OperationKey key,
            UUID claimToken,
            String errorCode
    ) {
        Objects.requireNonNull(claimToken, "claimToken");
        String safeError = requireLength(errorCode, "errorCode", 128);
        return database.transaction(session -> {
            Instant now = Instant.now();
            int updated = session.update("""
                            UPDATE operation_journal
                            SET operation_state = ?, error_code = ?, claim_token = NULL,
                                lease_until = NULL, updated_at = ?, row_version = row_version + 1
                            WHERE operation_id = ? AND request_hash = ?
                                AND operation_state = ? AND claim_token = ?
                            """,
                    OperationState.FAILED, safeError, now,
                    key.operationId(), key.requestHash(), OperationState.PENDING, claimToken);
            if (updated == 1) {
                return true;
            }
            OperationEntry entry = requireEntry(session, key.operationId(), true);
            validateIdentity(key, entry.key());
            return entry.state() == OperationState.FAILED
                    && Objects.equals(entry.errorCode(), safeError);
        });
    }

    public CompletableFuture<Optional<OperationEntry>> find(String operationId) {
        String safeId = requireLength(operationId, "operationId", 128);
        return database.read(session -> load(session, safeId, false));
    }

    private boolean insertPending(
            SqlSession session,
            OperationKey key,
            UUID token,
            Instant leaseUntil,
            Instant now
    ) throws SQLException {
        String verb = session.databaseType() == DatabaseType.SQLITE
                ? "INSERT OR IGNORE"
                : "INSERT IGNORE";
        String sql = verb + """
                 INTO operation_journal (
                    operation_id, operation_type, actor_uuid, scope_key, request_hash,
                    operation_state, claim_token, lease_until, result_json, error_code,
                    attempt_count, created_at, updated_at, completed_at, row_version
                 ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        return session.update(sql,
                key.operationId(),
                key.operationType(),
                nullable(key.actorUuid(), JDBCType.VARCHAR),
                nullable(key.scopeKey(), JDBCType.VARCHAR),
                key.requestHash(),
                OperationState.PENDING,
                token,
                leaseUntil,
                SqlNull.of(JDBCType.LONGVARCHAR),
                SqlNull.of(JDBCType.VARCHAR),
                1,
                now,
                now,
                SqlNull.of(JDBCType.BIGINT),
                0L) == 1;
    }

    private boolean reclaim(
            SqlSession session,
            OperationEntry existing,
            UUID token,
            Instant leaseUntil,
            Instant now
    ) throws SQLException {
        int updated = session.update("""
                        UPDATE operation_journal
                        SET operation_state = ?, claim_token = ?, lease_until = ?,
                            result_json = NULL, error_code = NULL, completed_at = NULL,
                            attempt_count = attempt_count + 1, updated_at = ?,
                            row_version = row_version + 1
                        WHERE operation_id = ? AND request_hash = ? AND row_version = ?
                            AND (operation_state = ? OR
                                (operation_state = ? AND (lease_until IS NULL OR lease_until <= ?)))
                        """,
                OperationState.PENDING, token, leaseUntil, now,
                existing.key().operationId(), existing.key().requestHash(), existing.version(),
                OperationState.FAILED, OperationState.PENDING, now);
        return updated == 1;
    }

    private OperationEntry requireEntry(
            SqlSession session,
            String operationId,
            boolean forUpdate
    ) throws SQLException {
        return load(session, operationId, forUpdate).orElseThrow(() ->
                new OperationConflictException(
                        "Operation " + operationId + " disappeared during journal arbitration"
                ));
    }

    private Optional<OperationEntry> load(
            SqlSession session,
            String operationId,
            boolean forUpdate
    ) throws SQLException {
        String suffix = forUpdate && session.databaseType().isNetworkDatabase() ? " FOR UPDATE" : "";
        return session.queryOne(
                "SELECT " + COLUMNS + " FROM operation_journal WHERE operation_id = ?" + suffix,
                OperationJournalRepository::map,
                operationId
        );
    }

    private static OperationEntry map(ResultSet resultSet) throws SQLException {
        String actor = resultSet.getString("actor_uuid");
        String token = resultSet.getString("claim_token");
        return new OperationEntry(
                new OperationKey(
                        resultSet.getString("operation_id"),
                        resultSet.getString("operation_type"),
                        actor == null ? null : UUID.fromString(actor),
                        resultSet.getString("scope_key"),
                        resultSet.getString("request_hash")
                ),
                parseState(resultSet.getString("operation_state")),
                token == null ? null : UUID.fromString(token),
                instant(resultSet, "lease_until"),
                resultSet.getString("result_json"),
                resultSet.getString("error_code"),
                resultSet.getInt("attempt_count"),
                Instant.ofEpochMilli(resultSet.getLong("created_at")),
                Instant.ofEpochMilli(resultSet.getLong("updated_at")),
                instant(resultSet, "completed_at"),
                resultSet.getLong("row_version")
        );
    }

    private static OperationState parseState(String value) throws SQLException {
        try {
            return OperationState.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new SQLException("Unknown operation journal state: " + value, exception);
        }
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    private static Object nullable(Object value, JDBCType type) {
        return value == null ? SqlNull.of(type) : value;
    }

    private static void validateIdentity(OperationKey expected, OperationKey actual) {
        boolean matches = expected.operationType().equals(actual.operationType())
                && Objects.equals(expected.actorUuid(), actual.actorUuid())
                && Objects.equals(expected.scopeKey(), actual.scopeKey())
                && constantTimeEquals(expected.requestHash(), actual.requestHash());
        if (!matches) {
            throw new OperationConflictException(
                    "Operation id " + expected.operationId() + " was reused with a different request"
            );
        }
    }

    private static void validateLease(Duration lease) {
        Objects.requireNonNull(lease, "lease");
        if (lease.isZero() || lease.isNegative() || lease.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("lease must be positive and no longer than 7 days");
        }
    }

    private static String normalizeResult(String value) {
        return value == null ? "null" : value;
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
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8)
        );
    }
}
