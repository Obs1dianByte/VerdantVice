package com.verdantvice.worker;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.verdantvice.common.BlockPosition;
import com.verdantvice.database.DatabaseManager;
import com.verdantvice.database.SqlSession;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Durable task queue with token-and-version compare-and-set leases.
 */
public final class WorkerTaskRepository {
    private static final String COLUMNS = """
            task_id, farm_id, worker_uuid, task_type, priority, target_json,
            reservation_token, reservation_expires_at, timeout_at, attempt_count,
            task_status, idempotency_key, created_at, updated_at, row_version
            """.replace('\n', ' ').strip();

    private final DatabaseManager database;
    private final WorkerTaskService taskService = new WorkerTaskService();

    public WorkerTaskRepository(DatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public CompletableFuture<StoredTask> createQueued(
            UUID farmId,
            WorkerTask task,
            String idempotencyKey
    ) {
        Objects.requireNonNull(farmId, "farmId");
        Objects.requireNonNull(task, "task");
        String key = requireKey(idempotencyKey);
        if (task.status() != WorkerTaskStatus.QUEUED
                || task.workerUuid().isPresent()
                || task.reservation().isPresent()) {
            throw new IllegalArgumentException("Only a pristine queued task can be created");
        }
        return database.transaction(session -> {
            Optional<StoredTask> existing = findByIdempotency(session, key);
            if (existing.isPresent()) {
                return existing.get();
            }
            session.update("""
                            INSERT INTO worker_tasks (
                              task_id, farm_id, worker_uuid, task_type, priority,
                              target_json, reservation_token, reservation_expires_at,
                              timeout_at, attempt_count, task_status, idempotency_key,
                              created_at, updated_at, row_version
                            ) VALUES (?, ?, NULL, ?, ?, ?, NULL, NULL, ?, ?, ?, ?, ?, ?, 0)
                            """,
                    task.taskId(),
                    farmId,
                    task.type(),
                    task.priority(),
                    targetJson(task.target(), task.maximumAttempts()),
                    task.timeoutAt(),
                    task.attempts(),
                    task.status(),
                    key,
                    task.createdAt(),
                    task.createdAt()
            );
            return new StoredTask(
                    task,
                    farmId,
                    key,
                    Optional.empty(),
                    task.createdAt(),
                    0L
            );
        });
    }

    public CompletableFuture<Optional<StoredTask>> find(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId");
        return database.queryOne(
                "SELECT " + COLUMNS + " FROM worker_tasks WHERE task_id=?",
                WorkerTaskRepository::map,
                taskId
        );
    }

    public CompletableFuture<List<StoredTask>> findForFarm(UUID farmId) {
        Objects.requireNonNull(farmId, "farmId");
        return database.query(
                "SELECT " + COLUMNS + " FROM worker_tasks WHERE farm_id=? "
                        + "ORDER BY priority DESC, created_at, task_id",
                WorkerTaskRepository::map,
                farmId
        );
    }

    public CompletableFuture<List<StoredTask>> findForWorker(UUID workerUuid) {
        Objects.requireNonNull(workerUuid, "workerUuid");
        return database.query(
                "SELECT " + COLUMNS + " FROM worker_tasks WHERE worker_uuid=? "
                        + "ORDER BY updated_at DESC, task_id",
                WorkerTaskRepository::map,
                workerUuid
        );
    }

    public CompletableFuture<Optional<TaskLease>> reserveNext(
            UUID farmId,
            UUID workerUuid,
            Set<WorkerTaskType> allowedTypes,
            Instant now,
            Duration reservationDuration
    ) {
        Objects.requireNonNull(farmId, "farmId");
        Objects.requireNonNull(workerUuid, "workerUuid");
        Set<WorkerTaskType> allowed = Set.copyOf(
                Objects.requireNonNull(allowedTypes, "allowedTypes")
        );
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(reservationDuration, "reservationDuration");
        if (allowed.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return database.transaction(session -> {
            releaseExpired(session, now);
            expireTimedOut(session, now);
            List<StoredTask> candidates = session.query(
                    "SELECT " + COLUMNS + " FROM worker_tasks "
                            + "WHERE farm_id=? AND task_status='QUEUED' AND timeout_at>? "
                            + "ORDER BY priority DESC, created_at, task_id",
                    WorkerTaskRepository::map,
                    farmId,
                    now
            );
            for (StoredTask candidate : candidates) {
                if (!allowed.contains(candidate.task().type())) {
                    continue;
                }
                WorkerTask reserved = taskService.reserve(
                        candidate.task(),
                        workerUuid,
                        now,
                        reservationDuration
                );
                UUID token = UUID.randomUUID();
                int changed = session.update("""
                                UPDATE worker_tasks SET
                                  worker_uuid=?, reservation_token=?,
                                  reservation_expires_at=?, task_status=?,
                                  updated_at=?, row_version=row_version+1
                                WHERE task_id=? AND row_version=?
                                  AND task_status='QUEUED'
                                  AND reservation_token IS NULL
                                """,
                        workerUuid,
                        token,
                        reserved.reservation().orElseThrow().expiresAt(),
                        reserved.status(),
                        now,
                        candidate.task().taskId(),
                        candidate.version()
                );
                if (changed == 1) {
                    StoredTask stored = new StoredTask(
                            reserved,
                            candidate.farmId(),
                            candidate.idempotencyKey(),
                            Optional.of(token),
                            now,
                            candidate.version() + 1L
                    );
                    return Optional.of(new TaskLease(stored, token));
                }
            }
            return Optional.empty();
        });
    }

    public CompletableFuture<TaskLease> start(TaskLease lease, Instant now) {
        return transition(lease, now, Transition.START);
    }

    public CompletableFuture<TaskLease> complete(TaskLease lease, Instant now) {
        return transition(lease, now, Transition.COMPLETE);
    }

    public CompletableFuture<TaskLease> failAttempt(TaskLease lease, Instant now) {
        return transition(lease, now, Transition.FAIL);
    }

    public CompletableFuture<TaskLease> renew(
            TaskLease lease,
            Instant now,
            Duration duration
    ) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(duration, "duration");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("Lease duration must be positive");
        }
        return database.transaction(session -> {
            StoredTask current = requireLease(session, lease);
            WorkerTask task = current.task();
            UUID workerUuid = task.workerUuid().orElseThrow();
            TaskReservation reservation =
                    new TaskReservation(workerUuid, now, now.plus(duration));
            WorkerTask renewed = new WorkerTask(
                    task.taskId(),
                    task.type(),
                    task.priority(),
                    task.target(),
                    Optional.of(reservation),
                    task.timeoutAt(),
                    task.attempts(),
                    task.maximumAttempts(),
                    task.status(),
                    task.workerUuid(),
                    task.createdAt()
            );
            int changed = session.update("""
                            UPDATE worker_tasks SET reservation_expires_at=?,
                              updated_at=?, row_version=row_version+1
                            WHERE task_id=? AND worker_uuid=? AND reservation_token=?
                              AND row_version=? AND task_status IN ('RESERVED', 'IN_PROGRESS')
                            """,
                    reservation.expiresAt(),
                    now,
                    task.taskId(),
                    workerUuid,
                    lease.token(),
                    current.version()
            );
            if (changed != 1) {
                throw new SQLException("Task lease changed while it was being renewed");
            }
            return new TaskLease(
                    new StoredTask(
                            renewed,
                            current.farmId(),
                            current.idempotencyKey(),
                            current.reservationToken(),
                            now,
                            current.version() + 1L
                    ),
                    lease.token()
            );
        });
    }

    public CompletableFuture<StoredTask> markBlocked(
            TaskLease lease,
            Instant now
    ) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(now, "now");
        return database.transaction(session -> {
            StoredTask current = requireLease(session, lease);
            WorkerTask task = current.task();
            int attempts = Math.max(task.attempts(), task.maximumAttempts());
            WorkerTask blocked = new WorkerTask(
                    task.taskId(),
                    task.type(),
                    task.priority(),
                    task.target(),
                    Optional.empty(),
                    task.timeoutAt(),
                    attempts,
                    task.maximumAttempts(),
                    WorkerTaskStatus.BLOCKED,
                    task.workerUuid(),
                    task.createdAt()
            );
            int changed = updateLeaseRow(session, current, lease.token(), blocked, now);
            if (changed != 1) {
                throw new SQLException("Task lease changed while task was being blocked");
            }
            return new StoredTask(
                    blocked,
                    current.farmId(),
                    current.idempotencyKey(),
                    Optional.empty(),
                    now,
                    current.version() + 1L
            );
        });
    }

    public CompletableFuture<Integer> releaseForWorker(UUID workerUuid, Instant now) {
        Objects.requireNonNull(workerUuid, "workerUuid");
        Objects.requireNonNull(now, "now");
        return database.update("""
                        UPDATE worker_tasks SET
                          worker_uuid=NULL, reservation_token=NULL,
                          reservation_expires_at=NULL, task_status='QUEUED',
                          updated_at=?, row_version=row_version+1
                        WHERE worker_uuid=? AND task_status IN ('RESERVED', 'IN_PROGRESS')
                          AND timeout_at>?
                        """,
                now,
                workerUuid,
                now
        );
    }

    public CompletableFuture<Integer> cancelTarget(
            UUID farmId,
            String targetType,
            UUID targetId,
            Instant now
    ) {
        Objects.requireNonNull(farmId, "farmId");
        Objects.requireNonNull(targetType, "targetType");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(now, "now");
        return findForFarm(farmId).thenCompose(tasks -> {
            List<UUID> matches = tasks.stream()
                    .map(StoredTask::task)
                    .filter(task -> task.target().targetType().equals(targetType))
                    .filter(task -> task.target().targetId().equals(targetId))
                    .filter(task -> !terminal(task.status()))
                    .map(WorkerTask::taskId)
                    .toList();
            if (matches.isEmpty()) {
                return CompletableFuture.completedFuture(0);
            }
            return database.transaction(session -> {
                int changed = 0;
                for (UUID taskId : matches) {
                    changed += session.update("""
                                    UPDATE worker_tasks SET task_status='CANCELLED',
                                      reservation_token=NULL, reservation_expires_at=NULL,
                                      updated_at=?, row_version=row_version+1
                                    WHERE task_id=? AND task_status NOT IN
                                      ('COMPLETED', 'FAILED', 'BLOCKED', 'CANCELLED')
                                    """,
                            now,
                            taskId
                    );
                }
                return changed;
            });
        });
    }

    public CompletableFuture<Integer> cleanupExpired(Instant now) {
        Objects.requireNonNull(now, "now");
        return database.transaction(session -> {
            int released = releaseExpired(session, now);
            int expired = expireTimedOut(session, now);
            return released + expired;
        });
    }

    private CompletableFuture<TaskLease> transition(
            TaskLease lease,
            Instant now,
            Transition transition
    ) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(now, "now");
        return database.transaction(session -> {
            StoredTask current = requireLease(session, lease);
            UUID workerUuid = current.task().workerUuid().orElseThrow();
            WorkerTask changedTask = switch (transition) {
                case START -> taskService.start(current.task(), workerUuid, now);
                case COMPLETE -> taskService.complete(current.task(), workerUuid, now);
                case FAIL -> taskService.failAttempt(current.task(), workerUuid, now);
            };
            int changed = updateLeaseRow(session, current, lease.token(), changedTask, now);
            if (changed != 1) {
                throw new SQLException("Task lease changed during " + transition.name());
            }
            Optional<UUID> resultingToken = changedTask.reservation().isPresent()
                    ? Optional.of(lease.token())
                    : Optional.empty();
            StoredTask stored = new StoredTask(
                    changedTask,
                    current.farmId(),
                    current.idempotencyKey(),
                    resultingToken,
                    now,
                    current.version() + 1L
            );
            return new TaskLease(stored, lease.token());
        });
    }

    private StoredTask requireLease(SqlSession session, TaskLease lease) throws SQLException {
        StoredTask current = find(session, lease.task().task().taskId())
                .orElseThrow(() -> new SQLException("Unknown worker task"));
        if (current.reservationToken().isEmpty()
                || !current.reservationToken().get().equals(lease.token())
                || current.task().workerUuid().isEmpty()
                || lease.task().task().workerUuid().isEmpty()
                || !current.task().workerUuid().get()
                .equals(lease.task().task().workerUuid().get())) {
            throw new SQLException("Task lease is no longer owned by this worker");
        }
        return current;
    }

    private int updateLeaseRow(
            SqlSession session,
            StoredTask current,
            UUID token,
            WorkerTask task,
            Instant now
    ) throws SQLException {
        return session.update("""
                        UPDATE worker_tasks SET
                          worker_uuid=?, reservation_token=?, reservation_expires_at=?,
                          attempt_count=?, task_status=?, updated_at=?,
                          row_version=row_version+1
                        WHERE task_id=? AND row_version=? AND reservation_token=?
                        """,
                task.workerUuid().orElse(null),
                task.reservation().isPresent() ? token : null,
                task.reservation().map(TaskReservation::expiresAt).orElse(null),
                task.attempts(),
                task.status(),
                now,
                task.taskId(),
                current.version(),
                token
        );
    }

    private static int releaseExpired(SqlSession session, Instant now) throws SQLException {
        return session.update("""
                        UPDATE worker_tasks SET
                          worker_uuid=NULL, reservation_token=NULL,
                          reservation_expires_at=NULL, task_status='QUEUED',
                          updated_at=?, row_version=row_version+1
                        WHERE task_status='RESERVED'
                          AND reservation_expires_at IS NOT NULL
                          AND reservation_expires_at<=? AND timeout_at>?
                        """,
                now,
                now,
                now
        );
    }

    private static int expireTimedOut(SqlSession session, Instant now) throws SQLException {
        return session.update("""
                        UPDATE worker_tasks SET
                          reservation_token=NULL, reservation_expires_at=NULL,
                          task_status='FAILED', updated_at=?,
                          row_version=row_version+1
                        WHERE timeout_at<=?
                          AND task_status IN ('QUEUED', 'RESERVED', 'IN_PROGRESS')
                        """,
                now,
                now
        );
    }

    private static Optional<StoredTask> find(SqlSession session, UUID taskId)
            throws SQLException {
        return session.queryOne(
                "SELECT " + COLUMNS + " FROM worker_tasks WHERE task_id=?",
                WorkerTaskRepository::map,
                taskId
        );
    }

    private static Optional<StoredTask> findByIdempotency(
            SqlSession session,
            String key
    ) throws SQLException {
        return session.queryOne(
                "SELECT " + COLUMNS + " FROM worker_tasks WHERE idempotency_key=?",
                WorkerTaskRepository::map,
                key
        );
    }

    private static StoredTask map(ResultSet result) throws SQLException {
        try {
            Instant createdAt = Instant.ofEpochMilli(result.getLong("created_at"));
            Instant updatedAt = Instant.ofEpochMilli(result.getLong("updated_at"));
            Instant timeoutAt = Instant.ofEpochMilli(result.getLong("timeout_at"));
            JsonObject targetJson = JsonParser.parseString(
                    result.getString("target_json")
            ).getAsJsonObject();
            TaskTarget target = target(targetJson);
            String workerValue = result.getString("worker_uuid");
            Optional<UUID> worker = workerValue == null
                    ? Optional.empty()
                    : Optional.of(UUID.fromString(workerValue));
            String tokenValue = result.getString("reservation_token");
            Optional<UUID> token = tokenValue == null
                    ? Optional.empty()
                    : Optional.of(UUID.fromString(tokenValue));
            long expiresValue = result.getLong("reservation_expires_at");
            Optional<TaskReservation> reservation;
            if (result.wasNull() || worker.isEmpty()) {
                reservation = Optional.empty();
            } else {
                Instant expires = Instant.ofEpochMilli(expiresValue);
                Instant reservedAt = updatedAt.isBefore(expires)
                        ? updatedAt
                        : expires.minusMillis(1L);
                reservation = Optional.of(
                        new TaskReservation(worker.get(), reservedAt, expires)
                );
            }
            WorkerTask task = new WorkerTask(
                    UUID.fromString(result.getString("task_id")),
                    WorkerTaskType.valueOf(result.getString("task_type")),
                    result.getInt("priority"),
                    target,
                    reservation,
                    timeoutAt,
                    result.getInt("attempt_count"),
                    maximumAttempts(targetJson),
                    WorkerTaskStatus.valueOf(result.getString("task_status")),
                    worker,
                    createdAt
            );
            return new StoredTask(
                    task,
                    UUID.fromString(result.getString("farm_id")),
                    result.getString("idempotency_key"),
                    token,
                    updatedAt,
                    result.getLong("row_version")
            );
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new SQLException("Invalid worker task row", exception);
        }
    }

    private static TaskTarget target(JsonObject json) {
        Optional<BlockPosition> position = Optional.empty();
        if (json.has("position") && json.get("position").isJsonObject()) {
            JsonObject location = json.getAsJsonObject("position");
            position = Optional.of(new BlockPosition(
                    UUID.fromString(location.get("worldUuid").getAsString()),
                    location.get("x").getAsInt(),
                    location.get("y").getAsInt(),
                    location.get("z").getAsInt()
            ));
        }
        return new TaskTarget(
                json.get("targetType").getAsString(),
                UUID.fromString(json.get("targetId").getAsString()),
                position
        );
    }

    private static String targetJson(TaskTarget target, int maximumAttempts) {
        JsonObject json = new JsonObject();
        json.addProperty("targetType", target.targetType());
        json.addProperty("targetId", target.targetId().toString());
        json.addProperty("maximumAttempts", maximumAttempts);
        target.position().ifPresent(position -> {
            JsonObject location = new JsonObject();
            location.addProperty("worldUuid", position.worldUuid().toString());
            location.addProperty("x", position.x());
            location.addProperty("y", position.y());
            location.addProperty("z", position.z());
            json.add("position", location);
        });
        return json.toString();
    }

    private static int maximumAttempts(JsonObject target) {
        return Math.max(
                1,
                target.has("maximumAttempts")
                        ? target.get("maximumAttempts").getAsInt()
                        : 3
        );
    }

    private static boolean terminal(WorkerTaskStatus status) {
        return status == WorkerTaskStatus.COMPLETED
                || status == WorkerTaskStatus.FAILED
                || status == WorkerTaskStatus.BLOCKED
                || status == WorkerTaskStatus.CANCELLED;
    }

    private static String requireKey(String key) {
        String normalized = Objects.requireNonNull(key, "idempotencyKey").strip();
        if (normalized.isEmpty() || normalized.length() > 128) {
            throw new IllegalArgumentException(
                    "Task idempotency key must contain 1 to 128 characters"
            );
        }
        return normalized;
    }

    private enum Transition {
        START,
        COMPLETE,
        FAIL
    }

    public record StoredTask(
            WorkerTask task,
            UUID farmId,
            String idempotencyKey,
            Optional<UUID> reservationToken,
            Instant updatedAt,
            long version
    ) {
        public StoredTask {
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(farmId, "farmId");
            requireKey(idempotencyKey);
            reservationToken = Objects.requireNonNull(
                    reservationToken,
                    "reservationToken"
            );
            Objects.requireNonNull(updatedAt, "updatedAt");
            if (version < 0L) {
                throw new IllegalArgumentException("Task row version cannot be negative");
            }
        }
    }

    public record TaskLease(StoredTask task, UUID token) {
        public TaskLease {
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(token, "token");
            if (task.reservationToken().isPresent()
                    && !task.reservationToken().get().equals(token)) {
                throw new IllegalArgumentException("Task lease does not own its task");
            }
        }
    }
}
