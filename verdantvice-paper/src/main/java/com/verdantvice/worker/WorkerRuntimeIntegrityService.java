package com.verdantvice.worker;

import com.verdantvice.database.DatabaseManager;
import com.verdantvice.database.DatabaseType;
import com.verdantvice.storage.PlayerDataRepository;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Repairs safe legacy worker references before runtime maps are populated. */
public final class WorkerRuntimeIntegrityService {
    private final DatabaseManager database;
    private final WorkerTaskRepository tasks;
    private final Logger logger;

    public WorkerRuntimeIntegrityService(DatabaseManager database,
            WorkerTaskRepository tasks, Logger logger) {
        this.database = Objects.requireNonNull(database, "database");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public CompletableFuture<IntegrityReport> migrate(String fallbackLocale) {
        String locale = Objects.requireNonNull(fallbackLocale, "fallbackLocale");
        return scan().thenCombine(
                tasks.interruptedCount(),
                IntegrityScan::new
        ).thenCompose(scan -> {
            boolean repairNeeded = scan.interruptedTasks() > 0
                    || scan.rows().stream().anyMatch(row ->
                    !row.ownerExists() || row.currentTaskId().isPresent());
            CompletableFuture<Optional<Path>> backup = repairNeeded
                    && database.diagnostics().type() == DatabaseType.SQLITE
                    ? database.backupNow().thenApply(Optional::of)
                    : CompletableFuture.completedFuture(Optional.empty());
            return backup.thenCompose(path -> repair(scan.rows(), locale)
                    .thenCompose(repair -> tasks.recoverInterrupted(Instant.now())
                            .thenApply(released -> new IntegrityReport(
                                    repair.disabledWorkerIds(),
                                    repair.repairedPlayerParents(),
                                    repair.clearedTaskPointers(),
                                    released,
                                    path))));
        }).whenComplete((report, failure) -> {
            if (failure != null || report == null) return;
            if (report.repairedPlayerParents() > 0
                    || report.clearedTaskPointers() > 0
                    || report.releasedInterruptedTasks() > 0
                    || !report.disabledWorkerIds().isEmpty()) {
                logger.log(Level.INFO,
                        "Worker integrity check: repaired players={0}, cleared tasks={1}, "
                                + "requeued tasks={2}, disabled runtimes={3}, backup={4}",
                        new Object[] { report.repairedPlayerParents(),
                                report.clearedTaskPointers(),
                                report.releasedInterruptedTasks(),
                                report.disabledWorkerIds().size(),
                                report.backup().map(Path::getFileName).orElse(null) });
            }
        });
    }

    private CompletableFuture<List<ReferenceRow>> scan() {
        return database.query("""
                        SELECT w.worker_uuid, w.owner_uuid, w.farm_id, w.entity_uuid,
                               w.current_task_id, w.worker_state, w.row_version,
                               (SELECT COUNT(*) FROM players p
                                WHERE p.player_uuid=w.owner_uuid) AS owner_count,
                               (SELECT COUNT(*) FROM farms f
                                WHERE f.farm_id=w.farm_id) AS farm_count,
                               (SELECT COUNT(*) FROM workers d
                                WHERE d.entity_uuid=w.entity_uuid) AS entity_count
                        FROM workers w ORDER BY w.worker_uuid
                        """,
                result -> new ReferenceRow(
                        UUID.fromString(result.getString("worker_uuid")),
                        UUID.fromString(result.getString("owner_uuid")),
                        UUID.fromString(result.getString("farm_id")),
                        UUID.fromString(result.getString("entity_uuid")),
                        Optional.ofNullable(result.getString("current_task_id")).map(UUID::fromString),
                        WorkerState.valueOf(result.getString("worker_state")),
                        result.getLong("row_version"),
                        result.getInt("owner_count") == 1,
                        result.getInt("farm_count") == 1,
                        result.getInt("entity_count") == 1));
    }

    private CompletableFuture<RepairResult> repair(List<ReferenceRow> rows,
            String fallbackLocale) {
        Instant now = Instant.now();
        return database.transaction(session -> {
            Set<UUID> disabled = new LinkedHashSet<>();
            int repairedPlayers = 0;
            int clearedTasks = 0;
            for (ReferenceRow row : rows) {
                if (!row.farmExists() || !row.entityUnique()) {
                    disabled.add(row.workerUuid());
                    continue;
                }
                if (!row.ownerExists()) {
                    PlayerDataRepository.ensure(session, row.ownerUuid(), fallbackLocale, now);
                    repairedPlayers++;
                }
                if (row.currentTaskId().isPresent()) {
                    WorkerState stable = switch (row.state()) {
                        case WAITING_FOR_PAYMENT -> WorkerState.WAITING_FOR_PAYMENT;
                        case BLOCKED -> WorkerState.BLOCKED;
                        default -> WorkerState.IDLE;
                    };
                    int changed = session.update("""
                                    UPDATE workers SET current_task_id=NULL,
                                      worker_state=?, updated_at=?, row_version=row_version+1
                                    WHERE worker_uuid=? AND row_version=?
                                    """,
                            stable, now, row.workerUuid(), row.version());
                    if (changed != 1) {
                        throw new SQLException("Concurrent worker integrity repair: " + row.workerUuid());
                    }
                    clearedTasks++;
                }
            }
            return new RepairResult(Set.copyOf(disabled), repairedPlayers, clearedTasks);
        });
    }

    public record IntegrityReport(Set<UUID> disabledWorkerIds,
            int repairedPlayerParents, int clearedTaskPointers,
            int releasedInterruptedTasks, Optional<Path> backup) {
        public IntegrityReport {
            disabledWorkerIds = Set.copyOf(Objects.requireNonNull(disabledWorkerIds, "disabledWorkerIds"));
            if (repairedPlayerParents < 0 || clearedTaskPointers < 0 || releasedInterruptedTasks < 0) {
                throw new IllegalArgumentException("Integrity counters cannot be negative");
            }
            backup = Objects.requireNonNull(backup, "backup");
        }
    }
    private record IntegrityScan(List<ReferenceRow> rows, int interruptedTasks) {
        private IntegrityScan {
            rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
            if (interruptedTasks < 0) {
                throw new IllegalArgumentException("Interrupted task count cannot be negative");
            }
        }
    }
    private record RepairResult(Set<UUID> disabledWorkerIds,
            int repairedPlayerParents, int clearedTaskPointers) {}
    private record ReferenceRow(UUID workerUuid, UUID ownerUuid, UUID farmId,
            UUID entityUuid, Optional<UUID> currentTaskId, WorkerState state,
            long version, boolean ownerExists, boolean farmExists,
            boolean entityUnique) {}
}
