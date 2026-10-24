package com.verdantvice.worker;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.verdantvice.common.BlockPosition;
import com.verdantvice.database.DatabaseManager;
import com.verdantvice.database.SqlNull;
import com.verdantvice.herbalist.HerbalistRecord;
import com.verdantvice.storage.PlayerDataRepository;
import com.verdantvice.text.ExternalNameSanitizer;
import com.verdantvice.security.CustomItemType;
import com.verdantvice.security.ItemConsumptionGateway;
import com.verdantvice.security.SecureItemService;

import java.sql.JDBCType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Versioned worker persistence. Every mutable write is a compare-and-set.
 */
public final class WorkerRepository {
    private static final String COLUMNS = """
            worker_uuid, entity_uuid, farm_id, owner_uuid, worker_name,
            specialization, worker_state, morale, experience, worker_level,
            daily_wage, unpaid_days, current_task_id, schedule_json,
            worker_data_json, home_world_uuid, home_x, home_y, home_z,
            last_world_uuid, last_x, last_y, last_z, created_at, updated_at,
            row_version
            """.replace('\n', ' ').strip();

    private final DatabaseManager database;

    public WorkerRepository(DatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public CompletableFuture<List<WorkerRecord>> loadAll() {
        return database.query("SELECT " + COLUMNS + " FROM workers", WorkerRepository::map);
    }

    public CompletableFuture<Optional<WorkerRecord>> find(UUID workerUuid) {
        Objects.requireNonNull(workerUuid, "workerUuid");
        return database.queryOne(
                "SELECT " + COLUMNS + " FROM workers WHERE worker_uuid=?",
                WorkerRepository::map,
                workerUuid
        );
    }

    public CompletableFuture<Optional<WorkerRecord>> findByEntity(UUID entityUuid) {
        Objects.requireNonNull(entityUuid, "entityUuid");
        return database.queryOne(
                "SELECT " + COLUMNS + " FROM workers WHERE entity_uuid=?",
                WorkerRepository::map,
                entityUuid
        );
    }

    public CompletableFuture<List<WorkerRecord>> findByFarm(UUID farmId) {
        Objects.requireNonNull(farmId, "farmId");
        return database.query(
                "SELECT " + COLUMNS + " FROM workers WHERE farm_id=? ORDER BY worker_name, worker_uuid",
                WorkerRepository::map,
                farmId
        );
    }

    public CompletableFuture<Integer> legacyNameCount(String fallbackName) {
        String fallback = ExternalNameSanitizer.name(fallbackName, "Worker", 64);
        return database.query(
                "SELECT worker_uuid, worker_name, row_version FROM workers",
                WorkerRepository::legacyName
        ).thenApply(rows -> (int) rows.stream()
                .filter(row -> !row.name().equals(
                        ExternalNameSanitizer.name(row.name(), fallback, 64)))
                .count());
    }

    /**
     * Sanitizes only the persisted worker_name column. Identity, wages, morale,
     * experience, tasks and every other worker field remain unchanged.
     */
    public CompletableFuture<Integer> sanitizeLegacyNames(
            String fallbackName,
            Instant now
    ) {
        String fallback = ExternalNameSanitizer.name(fallbackName, "Worker", 64);
        Objects.requireNonNull(now, "now");
        return database.transaction(session -> {
            List<LegacyNameRow> rows = session.query(
                    "SELECT worker_uuid, worker_name, row_version FROM workers",
                    WorkerRepository::legacyName
            );
            int changedRows = 0;
            for (LegacyNameRow row : rows) {
                String cleaned = ExternalNameSanitizer.name(row.name(), fallback, 64);
                if (cleaned.equals(row.name())) {
                    continue;
                }
                int changed = session.update(
                        "UPDATE workers SET worker_name=?, updated_at=?, "
                                + "row_version=row_version+1 "
                                + "WHERE worker_uuid=? AND row_version=?",
                        cleaned,
                        now,
                        row.workerUuid(),
                        row.version()
                );
                if (changed != 1) {
                    throw new SQLException(
                            "Concurrent worker name migration: " + row.workerUuid());
                }
                changedRows++;
            }
            return changedRows;
        });
    }

    /**
     * Claims an existing persisted herbalist and creates its worker row in the
     * same database transaction.
     */
    public CompletableFuture<WorkerRecord> hireHerbalist(
            HerbalistRecord herbalist,
            UUID ownerUuid,
            UUID farmId,
            String workerName,
            WorkerSpecialization specialization,
            int dailyWage,
            long minecraftDay,
            WorkerSchedule schedule,
            BlockPosition home,
            int farmWorkerLimit,
            String ownerLocale,
            Instant now
    ) {
        return hireHerbalist(herbalist, ownerUuid, farmId, workerName, specialization,
                dailyWage, minecraftDay, schedule, home, farmWorkerLimit, ownerLocale,
                now, null, null, null);
    }

    /** Claims the villager, creates its worker, and consumes its contract in one transaction. */
    public CompletableFuture<WorkerRecord> hireHerbalist(
            HerbalistRecord herbalist,
            UUID ownerUuid,
            UUID farmId,
            String workerName,
            WorkerSpecialization specialization,
            int dailyWage,
            long minecraftDay,
            WorkerSchedule schedule,
            BlockPosition home,
            int farmWorkerLimit,
            String ownerLocale,
            Instant now,
            SecureItemService.ValidatedIdentity contract,
            ItemConsumptionGateway items,
            String operationId
    ) {
        Objects.requireNonNull(herbalist, "herbalist");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(farmId, "farmId");
        Objects.requireNonNull(specialization, "specialization");
        Objects.requireNonNull(schedule, "schedule");
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(ownerLocale, "ownerLocale");
        Objects.requireNonNull(now, "now");
        if (contract != null) {
            Objects.requireNonNull(items, "items");
            Objects.requireNonNull(operationId, "operationId");
        }
        String name = requireName(workerName);
        if (dailyWage < 1 || farmWorkerLimit < 1) {
            throw new IllegalArgumentException("Invalid wage or farm worker limit");
        }

        UUID workerUuid = UUID.randomUUID();
        Worker worker = new Worker(
                workerUuid,
                ownerUuid,
                farmId,
                specialization,
                1,
                0L,
                75.0,
                dailyWage,
                0,
                minecraftDay - 1L,
                WorkerState.IDLE,
                Optional.empty()
        );
        return database.transaction(session -> {
            PlayerDataRepository.ensure(session, ownerUuid, ownerLocale, now);
            String lock = session.databaseType().isNetworkDatabase() ? " FOR UPDATE" : "";
            if (!session.exists("SELECT farm_id FROM farms WHERE farm_id=?" + lock, farmId)) {
                throw new SQLException("unknown_farm");
            }
            int workers = session.queryOne(
                    "SELECT COUNT(*) AS total FROM workers WHERE farm_id=?",
                    result -> result.getInt("total"),
                    farmId
            ).orElse(0);
            if (workers >= farmWorkerLimit) {
                throw new SQLException("worker_limit");
            }
            if (session.exists("SELECT 1 FROM workers WHERE entity_uuid=?", herbalist.entityUuid())) {
                throw new SQLException("already_employed");
            }
            int claimed = session.update("""
                            UPDATE herbalists
                            SET owner_uuid=?, updated_at=?, row_version=row_version+1
                            WHERE herbalist_uuid=? AND entity_uuid=?
                              AND (owner_uuid IS NULL OR owner_uuid=?)
                            """,
                    ownerUuid,
                    now,
                    herbalist.herbalistUuid(),
                    herbalist.entityUuid(),
                    ownerUuid
            );
            if (claimed != 1) {
                throw new SQLException("already_employed");
            }
            session.update("""
                            INSERT INTO workers (
                              worker_uuid, entity_uuid, farm_id, owner_uuid, worker_name,
                              specialization, worker_state, morale, experience, worker_level,
                              daily_wage, unpaid_days, current_task_id, schedule_json,
                              worker_data_json, home_world_uuid, home_x, home_y, home_z,
                              last_world_uuid, last_x, last_y, last_z, created_at, updated_at,
                              row_version
                            ) VALUES (
                              ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?,
                              ?, ?, ?, ?, ?, ?, 0
                            )
                            """,
                    worker.workerUuid(),
                    herbalist.entityUuid(),
                    worker.farmId(),
                    worker.ownerUuid(),
                    name,
                    worker.specialization(),
                    worker.state(),
                    worker.morale(),
                    worker.experience(),
                    worker.level(),
                    worker.salaryPerMinecraftDay(),
                    worker.unpaidDays(),
                    scheduleJson(schedule),
                    workerDataJson(worker),
                    home.worldUuid(),
                    home.x(),
                    home.y(),
                    home.z(),
                    home.worldUuid(),
                    home.x(),
                    home.y(),
                    home.z(),
                    now,
                    now
            );
            if (contract != null) {
                items.consume(session, contract, java.util.Set.of(CustomItemType.PLANTATION_CONTRACT),
                        ownerUuid, operationId, now);
            }
            return new WorkerRecord(
                    worker,
                    herbalist.entityUuid(),
                    name,
                    schedule,
                    Optional.of(home),
                    Optional.of(home),
                    now,
                    now,
                    0L
            );
        });
    }

    public CompletableFuture<WorkerRecord> save(WorkerRecord record) {
        Objects.requireNonNull(record, "record");
        Instant now = record.updatedAt();
        return database.transaction(session -> {
            Worker worker = record.worker();
            int changed = session.update("""
                            UPDATE workers SET
                              farm_id=?, owner_uuid=?, worker_name=?, specialization=?,
                              worker_state=?, morale=?, experience=?, worker_level=?,
                              daily_wage=?, unpaid_days=?, current_task_id=?, schedule_json=?,
                              worker_data_json=?, home_world_uuid=?, home_x=?, home_y=?, home_z=?,
                              last_world_uuid=?, last_x=?, last_y=?, last_z=?, updated_at=?,
                              row_version=row_version+1
                            WHERE worker_uuid=? AND row_version=?
                            """,
                    worker.farmId(),
                    worker.ownerUuid(),
                    record.name(),
                    worker.specialization(),
                    worker.state(),
                    worker.morale(),
                    worker.experience(),
                    worker.level(),
                    worker.salaryPerMinecraftDay(),
                    worker.unpaidDays(),
                    worker.activeTaskId().orElse(null),
                    scheduleJson(record.schedule()),
                    workerDataJson(worker),
                    world(record.home()),
                    coordinate(record.home(), Axis.X),
                    coordinate(record.home(), Axis.Y),
                    coordinate(record.home(), Axis.Z),
                    world(record.lastKnownPosition()),
                    coordinate(record.lastKnownPosition(), Axis.X),
                    coordinate(record.lastKnownPosition(), Axis.Y),
                    coordinate(record.lastKnownPosition(), Axis.Z),
                    now,
                    worker.workerUuid(),
                    record.version()
            );
            if (changed != 1) {
                throw new SQLException("Concurrent worker modification: " + worker.workerUuid());
            }
            return record.persisted(now);
        });
    }

    public CompletableFuture<Boolean> dismiss(UUID workerUuid, UUID ownerUuid, Instant now) {
        Objects.requireNonNull(workerUuid, "workerUuid");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(now, "now");
        return database.transaction(session -> {
            Optional<UUID> entity = session.queryOne(
                    "SELECT entity_uuid FROM workers WHERE worker_uuid=? AND owner_uuid=?",
                    result -> UUID.fromString(result.getString("entity_uuid")),
                    workerUuid,
                    ownerUuid
            );
            if (entity.isEmpty()) {
                return false;
            }
            int removed = session.update(
                    "DELETE FROM workers WHERE worker_uuid=? AND owner_uuid=?",
                    workerUuid,
                    ownerUuid
            );
            if (removed == 1) {
                session.update("""
                                UPDATE herbalists SET owner_uuid=NULL, updated_at=?,
                                  row_version=row_version+1
                                WHERE entity_uuid=? AND owner_uuid=?
                                """,
                        now,
                        entity.get(),
                        ownerUuid
                );
            }
            return removed == 1;
        });
    }

    private static WorkerRecord map(ResultSet result) throws SQLException {
        Instant createdAt = Instant.ofEpochMilli(result.getLong("created_at"));
        Instant updatedAt = Instant.ofEpochMilli(result.getLong("updated_at"));
        JsonObject data = object(result.getString("worker_data_json"));
        long lastPaidDay = data.has("lastPaidMinecraftDay")
                ? data.get("lastPaidMinecraftDay").getAsLong()
                : -1L;
        String currentTask = result.getString("current_task_id");
        Worker worker = new Worker(
                uuid(result, "worker_uuid"),
                uuid(result, "owner_uuid"),
                uuid(result, "farm_id"),
                WorkerSpecialization.valueOf(result.getString("specialization")),
                result.getInt("worker_level"),
                result.getLong("experience"),
                result.getDouble("morale"),
                result.getInt("daily_wage"),
                result.getInt("unpaid_days"),
                lastPaidDay,
                WorkerState.valueOf(result.getString("worker_state")),
                currentTask == null ? Optional.empty() : Optional.of(UUID.fromString(currentTask))
        );
        return new WorkerRecord(
                worker,
                uuid(result, "entity_uuid"),
                result.getString("worker_name"),
                schedule(result.getString("schedule_json")),
                position(result, "home"),
                position(result, "last"),
                createdAt,
                updatedAt,
                result.getLong("row_version")
        );
    }

    private static WorkerSchedule schedule(String source) throws SQLException {
        try {
            JsonObject json = object(source);
            return new WorkerSchedule(
                    integer(json, "wakeUp", 6_000),
                    integer(json, "workStart", 7_000),
                    integer(json, "breakStart", 12_000),
                    integer(json, "breakEnd", 13_000),
                    integer(json, "workEnd", 18_000),
                    integer(json, "sleep", 22_000)
            );
        } catch (RuntimeException exception) {
            throw new SQLException("Invalid worker schedule JSON", exception);
        }
    }

    private static String scheduleJson(WorkerSchedule schedule) {
        JsonObject json = new JsonObject();
        json.addProperty("wakeUp", schedule.wakeUp());
        json.addProperty("workStart", schedule.workStart());
        json.addProperty("breakStart", schedule.breakStart());
        json.addProperty("breakEnd", schedule.breakEnd());
        json.addProperty("workEnd", schedule.workEnd());
        json.addProperty("sleep", schedule.sleep());
        return json.toString();
    }

    private static String workerDataJson(Worker worker) {
        JsonObject json = new JsonObject();
        json.addProperty("lastPaidMinecraftDay", worker.lastPaidMinecraftDay());
        return json.toString();
    }

    private static Optional<BlockPosition> position(ResultSet result, String prefix)
            throws SQLException {
        String world = result.getString(prefix + "_world_uuid");
        if (world == null) {
            return Optional.empty();
        }
        return Optional.of(new BlockPosition(
                UUID.fromString(world),
                result.getInt(prefix + "_x"),
                result.getInt(prefix + "_y"),
                result.getInt(prefix + "_z")
        ));
    }

    private static Object world(Optional<BlockPosition> position) {
        return position.<Object>map(BlockPosition::worldUuid)
                .orElseGet(() -> SqlNull.of(JDBCType.VARCHAR));
    }

    private static Object coordinate(Optional<BlockPosition> position, Axis axis) {
        if (position.isEmpty()) {
            return SqlNull.of(JDBCType.DOUBLE);
        }
        BlockPosition value = position.get();
        return switch (axis) {
            case X -> value.x();
            case Y -> value.y();
            case Z -> value.z();
        };
    }

    private static JsonObject object(String source) {
        return source == null || source.isBlank()
                ? new JsonObject()
                : JsonParser.parseString(source).getAsJsonObject();
    }

    private static int integer(JsonObject json, String key, int fallback) {
        return json.has(key) ? json.get(key).getAsInt() : fallback;
    }

    private static UUID uuid(ResultSet result, String column) throws SQLException {
        try {
            return UUID.fromString(result.getString(column));
        } catch (IllegalArgumentException exception) {
            throw new SQLException("Invalid UUID in workers." + column, exception);
        }
    }

    private static String requireName(String name) {
        return ExternalNameSanitizer.name(name, "Worker", 64);
    }

    private static LegacyNameRow legacyName(ResultSet result) throws SQLException {
        return new LegacyNameRow(
                uuid(result, "worker_uuid"),
                Objects.toString(result.getString("worker_name"), ""),
                result.getLong("row_version")
        );
    }

    private record LegacyNameRow(UUID workerUuid, String name, long version) {
    }

    private enum Axis {
        X,
        Y,
        Z
    }
}
