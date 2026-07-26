package com.verdantvice.worker;

import com.verdantvice.database.DatabaseManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Idempotent salary ledger. A worker/day pair and an operation id are both
 * unique in the schema.
 */
public final class WorkerPaymentRepository {
    private static final String COLUMNS = """
            payment_id, worker_uuid, payer_uuid, amount, currency, period_key,
            payment_status, operation_id, created_at, paid_at
            """.replace('\n', ' ').strip();

    private final DatabaseManager database;

    public WorkerPaymentRepository(DatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public CompletableFuture<PaymentRecord> createPending(
            UUID workerUuid,
            UUID payerUuid,
            long amount,
            long minecraftDay,
            Instant now
    ) {
        Objects.requireNonNull(workerUuid, "workerUuid");
        Objects.requireNonNull(payerUuid, "payerUuid");
        Objects.requireNonNull(now, "now");
        if (amount < 1L) {
            throw new IllegalArgumentException("Payment amount must be positive");
        }
        String period = periodKey(minecraftDay);
        return database.transaction(session -> {
            Optional<PaymentRecord> existing = session.queryOne(
                    "SELECT " + COLUMNS
                            + " FROM worker_payments WHERE worker_uuid=? AND period_key=?",
                    WorkerPaymentRepository::map,
                    workerUuid,
                    period
            );
            if (existing.isPresent()) {
                return existing.get();
            }
            UUID paymentId = UUID.randomUUID();
            String operationId = "worker-salary:" + workerUuid + ':' + minecraftDay;
            session.update("""
                            INSERT INTO worker_payments (
                              payment_id, worker_uuid, payer_uuid, amount, currency,
                              period_key, payment_status, operation_id, created_at, paid_at
                            ) VALUES (?, ?, ?, ?, 'EMERALD', ?, 'PENDING', ?, ?, NULL)
                            """,
                    paymentId,
                    workerUuid,
                    payerUuid,
                    amount,
                    period,
                    operationId,
                    now
            );
            return new PaymentRecord(
                    paymentId,
                    workerUuid,
                    payerUuid,
                    amount,
                    "EMERALD",
                    period,
                    PaymentStatus.PENDING,
                    operationId,
                    now,
                    Optional.empty()
            );
        });
    }

    public CompletableFuture<Optional<PaymentRecord>> findForDay(
            UUID workerUuid,
            long minecraftDay
    ) {
        Objects.requireNonNull(workerUuid, "workerUuid");
        return database.queryOne(
                "SELECT " + COLUMNS
                        + " FROM worker_payments WHERE worker_uuid=? AND period_key=?",
                WorkerPaymentRepository::map,
                workerUuid,
                periodKey(minecraftDay)
        );
    }

    public CompletableFuture<Optional<PaymentRecord>> findByOperation(
            String operationId
    ) {
        String operation = requireOperation(operationId);
        return database.queryOne(
                "SELECT " + COLUMNS
                        + " FROM worker_payments WHERE operation_id=?",
                WorkerPaymentRepository::map,
                operation
        );
    }

    public CompletableFuture<List<PaymentRecord>> history(UUID workerUuid, int limit) {
        Objects.requireNonNull(workerUuid, "workerUuid");
        int bounded = Math.max(1, Math.min(500, limit));
        return database.query(
                "SELECT " + COLUMNS
                        + " FROM worker_payments WHERE worker_uuid=? "
                        + "ORDER BY created_at DESC",
                WorkerPaymentRepository::map,
                workerUuid
        ).thenApply(rows -> rows.size() <= bounded
                ? rows
                : List.copyOf(rows.subList(0, bounded)));
    }

    public CompletableFuture<PaymentRecord> markPaid(
            UUID paymentId,
            Instant paidAt
    ) {
        return changeStatus(paymentId, PaymentStatus.PAID, Optional.of(paidAt));
    }

    public CompletableFuture<PaymentRecord> markFailed(UUID paymentId) {
        return changeStatus(paymentId, PaymentStatus.FAILED, Optional.empty());
    }

    private CompletableFuture<PaymentRecord> changeStatus(
            UUID paymentId,
            PaymentStatus status,
            Optional<Instant> paidAt
    ) {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(paidAt, "paidAt");
        return database.transaction(session -> {
            Optional<PaymentRecord> current = session.queryOne(
                    "SELECT " + COLUMNS + " FROM worker_payments WHERE payment_id=?",
                    WorkerPaymentRepository::map,
                    paymentId
            );
            PaymentRecord payment = current.orElseThrow(
                    () -> new SQLException("Unknown worker payment " + paymentId)
            );
            if (payment.status() == status) {
                return payment;
            }
            if (payment.status() != PaymentStatus.PENDING) {
                throw new SQLException(
                        "Worker payment is already terminal: " + payment.status()
                );
            }
            int changed = session.update("""
                            UPDATE worker_payments SET payment_status=?, paid_at=?
                            WHERE payment_id=? AND payment_status='PENDING'
                            """,
                    status,
                    paidAt.orElse(null),
                    paymentId
            );
            if (changed != 1) {
                throw new SQLException("Worker payment changed concurrently");
            }
            return new PaymentRecord(
                    payment.paymentId(),
                    payment.workerUuid(),
                    payment.payerUuid(),
                    payment.amount(),
                    payment.currency(),
                    payment.periodKey(),
                    status,
                    payment.operationId(),
                    payment.createdAt(),
                    paidAt
            );
        });
    }

    private static PaymentRecord map(ResultSet result) throws SQLException {
        long paidMillis = result.getLong("paid_at");
        Optional<Instant> paidAt = result.wasNull()
                ? Optional.empty()
                : Optional.of(Instant.ofEpochMilli(paidMillis));
        try {
            return new PaymentRecord(
                    UUID.fromString(result.getString("payment_id")),
                    UUID.fromString(result.getString("worker_uuid")),
                    UUID.fromString(result.getString("payer_uuid")),
                    result.getLong("amount"),
                    result.getString("currency"),
                    result.getString("period_key"),
                    PaymentStatus.valueOf(result.getString("payment_status")),
                    result.getString("operation_id"),
                    Instant.ofEpochMilli(result.getLong("created_at")),
                    paidAt
            );
        } catch (IllegalArgumentException exception) {
            throw new SQLException("Invalid worker payment row", exception);
        }
    }

    private static String periodKey(long minecraftDay) {
        return "minecraft-day:" + minecraftDay;
    }

    private static String requireOperation(String operationId) {
        String normalized = Objects.requireNonNull(operationId, "operationId").strip();
        if (normalized.isEmpty() || normalized.length() > 128) {
            throw new IllegalArgumentException(
                    "Payment operation id must contain 1 to 128 characters"
            );
        }
        return normalized;
    }

    public enum PaymentStatus {
        PENDING,
        PAID,
        FAILED
    }

    public record PaymentRecord(
            UUID paymentId,
            UUID workerUuid,
            UUID payerUuid,
            long amount,
            String currency,
            String periodKey,
            PaymentStatus status,
            String operationId,
            Instant createdAt,
            Optional<Instant> paidAt
    ) {
        public PaymentRecord {
            Objects.requireNonNull(paymentId, "paymentId");
            Objects.requireNonNull(workerUuid, "workerUuid");
            Objects.requireNonNull(payerUuid, "payerUuid");
            if (amount < 1L) {
                throw new IllegalArgumentException("Payment amount must be positive");
            }
            currency = Objects.requireNonNull(currency, "currency");
            periodKey = Objects.requireNonNull(periodKey, "periodKey");
            Objects.requireNonNull(status, "status");
            operationId = requireOperation(operationId);
            Objects.requireNonNull(createdAt, "createdAt");
            paidAt = Objects.requireNonNull(paidAt, "paidAt");
        }
    }
}
