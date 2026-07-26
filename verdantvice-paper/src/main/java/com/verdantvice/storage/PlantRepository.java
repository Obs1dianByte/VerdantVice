package com.verdantvice.storage;

import com.verdantvice.common.BlockPosition;
import com.verdantvice.database.DatabaseManager;
import com.verdantvice.genetics.GeneticProfile;
import com.verdantvice.plant.GrowthStage;
import com.verdantvice.plant.Plant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class PlantRepository {
    private static final String SELECT_ALL = """
            SELECT p.*, g.genetic_class, g.traits_json,
                   g.created_at AS genetic_created_at
            FROM plants p
            JOIN genetic_profiles g
              ON g.genetic_profile_id = p.genetic_profile_id
            """;

    private final DatabaseManager database;
    private final Map<UUID, Long> versions = new ConcurrentHashMap<>();

    public PlantRepository(DatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public CompletableFuture<List<StoredPlant>> loadAll() {
        return database.query(SELECT_ALL, this::map).thenApply(plants -> {
            plants.forEach(stored -> versions.put(
                    stored.plant().uniqueId(),
                    stored.rowVersion()
            ));
            return plants;
        });
    }

    public CompletableFuture<Optional<StoredPlant>> find(UUID plantId) {
        return database.queryOne(
                SELECT_ALL + " WHERE p.plant_uuid=?",
                this::map,
                plantId
        ).thenApply(optional -> {
            optional.ifPresent(stored -> versions.put(plantId, stored.rowVersion()));
            return optional;
        });
    }

    public CompletableFuture<Void> create(Plant plant, GeneticProfile genetics) {
        String traits = DomainJson.genetics(genetics);
        String geneticSignature = sha256(traits);
        return database.transaction(session -> {
            PlayerDataRepository.ensure(
                    session,
                    plant.ownerUuid(),
                    null,
                    plant.plantedAt()
            );
            if (!session.exists(
                    "SELECT 1 FROM genetic_profiles WHERE genetic_profile_id=?",
                    genetics.id()
            )) {
                session.update(
                        "INSERT INTO genetic_profiles "
                                + "(genetic_profile_id, owner_uuid, genetic_class, traits_json, "
                                + "parent_profiles_json, signature, schema_version, created_at, "
                                + "updated_at, row_version) VALUES (?, ?, ?, ?, NULL, ?, 1, ?, ?, 0)",
                        genetics.id(),
                        plant.ownerUuid(),
                        genetics.geneticClass(),
                        traits,
                        geneticSignature,
                        genetics.createdAt(),
                        genetics.createdAt()
                );
            }
            insertPlant(session, plant);
            session.update(
                    "INSERT INTO plant_events "
                            + "(plant_uuid, event_type, event_data_json, actor_uuid, "
                            + "idempotency_key, created_at) VALUES (?, 'PLANTED', ?, ?, ?, ?)",
                    plant.uniqueId(),
                    DomainJson.stringMap(Map.of("strain", plant.strainId())),
                    plant.ownerUuid(),
                    "plant:" + plant.uniqueId(),
                    plant.plantedAt()
            );
            return null;
        }).thenRun(() -> versions.put(plant.uniqueId(), 0L));
    }

    public CompletableFuture<Void> save(Plant plant, String eventType) {
        long expected = versions.getOrDefault(plant.uniqueId(), 0L);
        return database.transaction(session -> {
            int changed = session.update(
                    """
                    UPDATE plants SET
                      growth_stage=?, growth_progress=?, health=?, hydration=?, nutrition=?,
                      light_level=?, stress=?, cleanliness=?, disease_id=?, pest_level=?,
                      resistance=?, yield_potential=?, quality_potential=?,
                      last_updated_at=?, last_watered_at=?, harvest_window_start=?,
                      harvest_window_end=?, farm_id=?, worker_assigned=?,
                      row_version=row_version+1
                    WHERE plant_uuid=? AND row_version=?
                    """,
                    plant.growthStage(),
                    plant.growthProgress(),
                    plant.health(),
                    plant.hydration(),
                    plant.nutrition(),
                    plant.lightLevel(),
                    plant.stress(),
                    plant.cleanliness(),
                    plant.diseaseId().orElse(null),
                    plant.pestLevel(),
                    plant.resistance(),
                    plant.yieldPotential(),
                    plant.qualityPotential(),
                    plant.lastUpdatedAt(),
                    plant.lastWateredAt(),
                    plant.harvestWindowStart(),
                    plant.harvestWindowEnd(),
                    plant.farmId().orElse(null),
                    plant.workerAssigned().orElse(null),
                    plant.uniqueId(),
                    expected
            );
            if (changed != 1) {
                throw new SQLException("Concurrent plant modification: " + plant.uniqueId());
            }
            if (eventType != null && !eventType.isBlank()) {
                session.update(
                        "INSERT INTO plant_events "
                                + "(plant_uuid, event_type, event_data_json, actor_uuid, "
                                + "idempotency_key, created_at) VALUES (?, ?, NULL, NULL, NULL, ?)",
                        plant.uniqueId(),
                        eventType,
                        plant.lastUpdatedAt()
                );
            }
            return null;
        }).thenRun(() -> versions.replace(plant.uniqueId(), expected, expected + 1L));
    }

    public CompletableFuture<Boolean> delete(UUID plantId, String reason, UUID actor) {
        long expected = versions.getOrDefault(plantId, 0L);
        return database.transaction(session -> {
            if (reason != null) {
                session.update(
                        "INSERT INTO plant_events "
                                + "(plant_uuid, event_type, event_data_json, actor_uuid, "
                                + "idempotency_key, created_at) VALUES (?, 'REMOVED', ?, ?, ?, ?)",
                        plantId,
                        DomainJson.stringMap(Map.of("reason", reason)),
                        actor,
                        "remove:" + plantId,
                        Instant.now()
                );
            }
            int changed = session.update(
                    "DELETE FROM plants WHERE plant_uuid=? AND row_version=?",
                    plantId,
                    expected
            );
            return changed == 1;
        }).thenApply(deleted -> {
            if (deleted) {
                versions.remove(plantId);
            }
            return deleted;
        });
    }

    private void insertPlant(com.verdantvice.database.SqlSession session, Plant plant)
            throws SQLException {
        session.update(
                """
                INSERT INTO plants (
                  plant_uuid, owner_uuid, world_uuid, block_x, block_y, block_z,
                  chunk_x, chunk_z, strain_id, genetic_profile_id, growth_stage,
                  growth_progress, health, hydration, nutrition, light_level, stress,
                  cleanliness, disease_id, pest_level, resistance, yield_potential,
                  quality_potential, planted_at, last_updated_at, last_watered_at,
                  harvest_window_start, harvest_window_end, farm_id, worker_assigned,
                  state_json, row_version
                ) VALUES (
                  ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                  ?, ?, ?, ?, ?, ?, ?, ?, NULL, 0
                )
                """,
                plant.uniqueId(),
                plant.ownerUuid(),
                plant.position().worldUuid(),
                plant.position().x(),
                plant.position().y(),
                plant.position().z(),
                Math.floorDiv(plant.position().x(), 16),
                Math.floorDiv(plant.position().z(), 16),
                plant.strainId(),
                plant.geneticProfileId(),
                plant.growthStage(),
                plant.growthProgress(),
                plant.health(),
                plant.hydration(),
                plant.nutrition(),
                plant.lightLevel(),
                plant.stress(),
                plant.cleanliness(),
                plant.diseaseId().orElse(null),
                plant.pestLevel(),
                plant.resistance(),
                plant.yieldPotential(),
                plant.qualityPotential(),
                plant.plantedAt(),
                plant.lastUpdatedAt(),
                plant.lastWateredAt(),
                plant.harvestWindowStart(),
                plant.harvestWindowEnd(),
                plant.farmId().orElse(null),
                plant.workerAssigned().orElse(null)
        );
    }

    private StoredPlant map(ResultSet result) throws SQLException {
        UUID geneticId = UUID.fromString(result.getString("genetic_profile_id"));
        GeneticProfile genetics = DomainJson.genetics(
                geneticId,
                result.getString("genetic_class"),
                result.getString("traits_json"),
                Instant.ofEpochMilli(result.getLong("genetic_created_at"))
        );
        Instant plantedAt = Instant.ofEpochMilli(result.getLong("planted_at"));
        Plant plant = new Plant(
                UUID.fromString(result.getString("plant_uuid")),
                UUID.fromString(result.getString("owner_uuid")),
                new BlockPosition(
                        UUID.fromString(result.getString("world_uuid")),
                        result.getInt("block_x"),
                        result.getInt("block_y"),
                        result.getInt("block_z")
                ),
                result.getString("strain_id"),
                geneticId,
                GrowthStage.valueOf(result.getString("growth_stage")),
                result.getDouble("growth_progress"),
                result.getDouble("health"),
                result.getDouble("hydration"),
                result.getDouble("nutrition"),
                result.getInt("light_level"),
                result.getDouble("stress"),
                result.getDouble("cleanliness"),
                Optional.ofNullable(result.getString("disease_id")),
                result.getDouble("pest_level"),
                result.getDouble("resistance"),
                result.getDouble("yield_potential"),
                result.getDouble("quality_potential"),
                plantedAt,
                Instant.ofEpochMilli(result.getLong("last_updated_at")),
                nullableInstant(result, "last_watered_at").orElse(plantedAt),
                requiredInstant(result, "harvest_window_start"),
                requiredInstant(result, "harvest_window_end"),
                optionalUuid(result.getString("farm_id")),
                optionalUuid(result.getString("worker_assigned"))
        );
        return new StoredPlant(plant, genetics, result.getLong("row_version"));
    }

    private Optional<Instant> nullableInstant(ResultSet result, String column)
            throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? Optional.empty() : Optional.of(Instant.ofEpochMilli(value));
    }

    private Instant requiredInstant(ResultSet result, String column) throws SQLException {
        return nullableInstant(result, column)
                .orElseThrow(() -> new SQLException("Required timestamp is null: " + column));
    }

    private Optional<UUID> optionalUuid(String value) {
        return value == null || value.isBlank()
                ? Optional.empty()
                : Optional.of(UUID.fromString(value));
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record StoredPlant(Plant plant, GeneticProfile genetics, long rowVersion) {
        public StoredPlant {
            Objects.requireNonNull(plant, "plant");
            Objects.requireNonNull(genetics, "genetics");
            if (rowVersion < 0L) {
                throw new IllegalArgumentException("rowVersion cannot be negative");
            }
        }
    }
}
