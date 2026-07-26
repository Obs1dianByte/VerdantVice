package com.verdantvice.herbalist;

import com.verdantvice.database.DatabaseManager;
import com.verdantvice.database.SqlNull;
import com.verdantvice.database.SqlSession;
import org.bukkit.configuration.file.YamlConfiguration;

import java.sql.JDBCType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class HerbalistRepository {
    private static final String COLUMNS = """
            herbalist_uuid, entity_uuid, owner_uuid, world_uuid, block_x, block_y, block_z,
            herbalist_level, experience, reputation, offers_json, created_at, updated_at, row_version
            """.replace('\n', ' ').strip();

    private final DatabaseManager database;

    public HerbalistRepository(DatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public CompletableFuture<Optional<HerbalistRecord>> findByEntity(UUID entityUuid) {
        Objects.requireNonNull(entityUuid, "entityUuid");
        return database.queryOne(
                "SELECT " + COLUMNS + " FROM herbalists WHERE entity_uuid=?",
                HerbalistRepository::map,
                entityUuid);
    }

    public CompletableFuture<Optional<HerbalistRecord>> find(UUID herbalistUuid) {
        Objects.requireNonNull(herbalistUuid, "herbalistUuid");
        return database.queryOne(
                "SELECT " + COLUMNS + " FROM herbalists WHERE herbalist_uuid=?",
                HerbalistRepository::map,
                herbalistUuid);
    }

    /**
     * Count and insert happen in one transaction. The facade serializes conversion
     * requests as well, preventing two villagers from crossing the configured cap.
     */
    public CompletableFuture<CreationResult> createIfBelowLimit(
            UUID herbalistUuid,
            UUID entityUuid,
            UUID worldUuid,
            int x,
            int y,
            int z,
            int radius,
            int maximumNearby,
            int startingReputation,
            Instant now
    ) {
        return database.transaction(session -> {
            Optional<HerbalistRecord> existing = findByEntity(session, entityUuid);
            if (existing.isPresent()) {
                return new CreationResult(CreationStatus.ALREADY_EXISTS, existing.get());
            }
            int nearby = session.queryOne("""
                            SELECT COUNT(*) AS total FROM herbalists
                            WHERE world_uuid=? AND block_x BETWEEN ? AND ?
                              AND block_y BETWEEN ? AND ? AND block_z BETWEEN ? AND ?
                            """,
                    result -> result.getInt("total"),
                    worldUuid, x - radius, x + radius, y - radius, y + radius, z - radius, z + radius
            ).orElse(0);
            if (nearby >= maximumNearby) {
                return new CreationResult(CreationStatus.VILLAGE_LIMIT, null);
            }
            session.update("""
                            INSERT INTO herbalists (
                              herbalist_uuid, entity_uuid, owner_uuid, world_uuid,
                              block_x, block_y, block_z, herbalist_level, experience,
                              reputation, offers_json, created_at, updated_at, row_version
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, 0, ?, ?, ?, ?, 0)
                            """,
                    herbalistUuid, entityUuid, SqlNull.of(JDBCType.VARCHAR), worldUuid,
                    x, y, z, startingReputation, SqlNull.of(JDBCType.LONGVARCHAR), now, now);
            HerbalistRecord created = findByEntity(session, entityUuid)
                    .orElseThrow(() -> new SQLException("Created herbalist row disappeared"));
            return new CreationResult(CreationStatus.CREATED, created);
        });
    }

    public CompletableFuture<HerbalistRecord> addProgress(
            UUID herbalistUuid,
            long experienceDelta,
            int reputationDelta,
            HerbalistTier resultingTier,
            Instant now
    ) {
        if (experienceDelta < 0L || reputationDelta < 0) {
            throw new IllegalArgumentException("Progress deltas cannot be negative");
        }
        Objects.requireNonNull(resultingTier, "resultingTier");
        return database.transaction(session -> {
            session.update("""
                            UPDATE herbalists
                            SET experience=experience+?, reputation=reputation+?,
                                herbalist_level=?, updated_at=?, row_version=row_version+1
                            WHERE herbalist_uuid=?
                            """,
                    experienceDelta, reputationDelta, resultingTier.databaseLevel(), now, herbalistUuid);
            return find(session, herbalistUuid)
                    .orElseThrow(() -> new SQLException("Unknown herbalist " + herbalistUuid));
        });
    }

    /**
     * Atomically derives the resulting tier from the persisted experience. This
     * overload is safe when several players trade with the same herbalist.
     */
    public CompletableFuture<HerbalistRecord> addProgress(
            UUID herbalistUuid,
            long experienceDelta,
            int reputationDelta,
            YamlConfiguration tierConfiguration,
            Instant now
    ) {
        Objects.requireNonNull(herbalistUuid, "herbalistUuid");
        Objects.requireNonNull(tierConfiguration, "tierConfiguration");
        Objects.requireNonNull(now, "now");
        if (experienceDelta < 0L || reputationDelta < 0) {
            throw new IllegalArgumentException("Progress deltas cannot be negative");
        }
        return database.transaction(session -> {
            HerbalistRecord current = find(session, herbalistUuid, true)
                    .orElseThrow(() -> new SQLException("Unknown herbalist " + herbalistUuid));
            long resultingExperience = Math.addExact(current.experience(), experienceDelta);
            HerbalistTier resultingTier = HerbalistTier.forExperience(
                    resultingExperience, tierConfiguration);
            int changed = session.update("""
                            UPDATE herbalists
                            SET experience=?, reputation=CASE
                                  WHEN reputation+?>100 THEN 100 ELSE reputation+? END,
                                herbalist_level=?, updated_at=?, row_version=row_version+1
                            WHERE herbalist_uuid=? AND row_version=?
                            """,
                    resultingExperience, reputationDelta, reputationDelta,
                    resultingTier.databaseLevel(), now, herbalistUuid, current.version());
            if (changed != 1) {
                throw new SQLException("Concurrent herbalist progress update");
            }
            return find(session, herbalistUuid)
                    .orElseThrow(() -> new SQLException("Updated herbalist disappeared"));
        });
    }

    Optional<HerbalistRecord> find(SqlSession session, UUID herbalistUuid) throws SQLException {
        return find(session, herbalistUuid, false);
    }

    Optional<HerbalistRecord> find(
            SqlSession session,
            UUID herbalistUuid,
            boolean forUpdate
    ) throws SQLException {
        String suffix = forUpdate && session.databaseType().isNetworkDatabase()
                ? " FOR UPDATE" : "";
        return session.queryOne(
                "SELECT " + COLUMNS + " FROM herbalists WHERE herbalist_uuid=?" + suffix,
                HerbalistRepository::map,
                herbalistUuid);
    }

    Optional<HerbalistRecord> findByEntity(SqlSession session, UUID entityUuid) throws SQLException {
        return session.queryOne(
                "SELECT " + COLUMNS + " FROM herbalists WHERE entity_uuid=?",
                HerbalistRepository::map,
                entityUuid);
    }

    private static HerbalistRecord map(ResultSet result) throws SQLException {
        String owner = result.getString("owner_uuid");
        return new HerbalistRecord(
                uuid(result.getString("herbalist_uuid")),
                uuid(result.getString("entity_uuid")),
                owner == null ? null : uuid(owner),
                uuid(result.getString("world_uuid")),
                result.getInt("block_x"),
                result.getInt("block_y"),
                result.getInt("block_z"),
                HerbalistTier.fromDatabaseLevel(result.getInt("herbalist_level")),
                result.getLong("experience"),
                result.getInt("reputation"),
                result.getString("offers_json"),
                Instant.ofEpochMilli(result.getLong("created_at")),
                Instant.ofEpochMilli(result.getLong("updated_at")),
                result.getLong("row_version"));
    }

    private static UUID uuid(String value) throws SQLException {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new SQLException("Invalid UUID in herbalists table: " + value, exception);
        }
    }

    public enum CreationStatus {
        CREATED,
        ALREADY_EXISTS,
        VILLAGE_LIMIT
    }

    public record CreationResult(CreationStatus status, HerbalistRecord record) {
        public Optional<HerbalistRecord> optionalRecord() {
            return Optional.ofNullable(record);
        }
    }
}
