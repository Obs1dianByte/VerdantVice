package com.verdantvice.storage;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.verdantvice.database.DatabaseManager;
import com.verdantvice.harvest.HarvestTiming;
import com.verdantvice.processing.BatchState;
import com.verdantvice.processing.CuringData;
import com.verdantvice.processing.CuringStage;
import com.verdantvice.processing.DryingData;
import com.verdantvice.processing.DryingStage;
import com.verdantvice.processing.ProductBatch;
import com.verdantvice.processing.QualityGrade;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Database-owned product batches. Inventory items are only signed references
 * to these rows and can never override persisted quality or processing state.
 */
public final class BatchRepository implements BatchPersistence {
    private static final String SELECT = """
            SELECT batch_id, owner_uuid, strain_composition_json,
                   genetic_origin_json, fresh_weight, dry_weight, quality_score,
                   quality_grade, contamination, freshness, harvest_timing,
                   drying_data_json, curing_data_json, batch_state, created_at,
                   updated_at, signature, row_version
            FROM product_batches
            """;

    private final DatabaseManager database;

    public BatchRepository(DatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public CompletableFuture<List<ProductBatch>> loadAll() {
        return database.query(SELECT, this::map).thenApply(rows ->
                rows.stream().map(StoredBatch::batch).toList());
    }

    @Override
    public CompletableFuture<Optional<ProductBatch>> find(UUID batchId) {
        return database.queryOne(
                SELECT + " WHERE batch_id=?",
                this::map,
                batchId
        ).thenApply(row -> row.map(StoredBatch::batch));
    }

    @Override
    public CompletableFuture<Void> create(ProductBatch batch) {
        Objects.requireNonNull(batch, "batch");
        if (batch.signature().isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("A persisted batch must be signed")
            );
        }
        Instant now = Instant.now();
        return database.transaction(session -> {
            PlayerDataRepository.ensure(session, batch.ownerUuid(), null, now);
            session.update(
                    """
                    INSERT INTO product_batches (
                      batch_id, owner_uuid, strain_composition_json,
                      genetic_origin_json, fresh_weight, dry_weight, quality_score,
                      quality_grade, contamination, freshness, harvest_timing,
                      drying_data_json, curing_data_json, batch_state, created_at,
                      updated_at, signature, schema_version, row_version
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 0)
                    """,
                    batch.batchId(),
                    batch.ownerUuid(),
                    DomainJson.composition(batch.strainComposition()),
                    DomainJson.uuidSet(batch.geneticOrigin()),
                    batch.freshWeight(),
                    batch.dryWeight(),
                    batch.qualityScore(),
                    batch.qualityGrade(),
                    batch.contamination(),
                    batch.freshness(),
                    batch.harvestTiming(),
                    batch.dryingData().map(BatchRepository::dryingJson).orElse(null),
                    batch.curingData().map(BatchRepository::curingJson).orElse(null),
                    batch.state(),
                    batch.createdAt(),
                    now,
                    batch.signature()
            );
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> save(ProductBatch batch) {
        Objects.requireNonNull(batch, "batch");
        if (batch.signature().isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("A persisted batch must be signed")
            );
        }
        return database.transaction(session -> {
            BatchPersistence.StoredBatch current = find(session, batch.batchId(), true)
                    .orElseThrow(() -> new SQLException(
                            "Missing product batch: " + batch.batchId()));
            save(session, current, batch, Instant.now());
            return null;
        });
    }

    @Override
    public Optional<BatchPersistence.StoredBatch> find(
            com.verdantvice.database.SqlSession session,
            UUID batchId,
            boolean forUpdate
    ) throws SQLException {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(batchId, "batchId");
        String suffix = forUpdate && session.databaseType().isNetworkDatabase()
                ? " FOR UPDATE" : "";
        return session.queryOne(
                SELECT + " WHERE batch_id=?" + suffix,
                this::map,
                batchId
        ).map(value -> new BatchPersistence.StoredBatch(value.batch(), value.version()));
    }

    @Override
    public void save(
            com.verdantvice.database.SqlSession session,
            BatchPersistence.StoredBatch expected,
            ProductBatch batch,
            Instant now
    ) throws SQLException {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(now, "now");
        int changed = session.update(
                """
                UPDATE product_batches SET
                  strain_composition_json=?, genetic_origin_json=?,
                  fresh_weight=?, dry_weight=?, quality_score=?, quality_grade=?,
                  contamination=?, freshness=?, harvest_timing=?,
                  drying_data_json=?, curing_data_json=?, batch_state=?,
                  updated_at=?, signature=?, row_version=row_version+1
                WHERE batch_id=? AND row_version=?
                """,
                DomainJson.composition(batch.strainComposition()),
                DomainJson.uuidSet(batch.geneticOrigin()),
                batch.freshWeight(),
                batch.dryWeight(),
                batch.qualityScore(),
                batch.qualityGrade(),
                batch.contamination(),
                batch.freshness(),
                batch.harvestTiming(),
                batch.dryingData().map(BatchRepository::dryingJson).orElse(null),
                batch.curingData().map(BatchRepository::curingJson).orElse(null),
                batch.state(),
                now,
                batch.signature(),
                batch.batchId(),
                expected.version()
        );
        if (changed != 1) {
            throw new SQLException("Concurrent batch modification: " + batch.batchId());
        }
    }

    private StoredBatch map(ResultSet result) throws SQLException {
        String drying = result.getString("drying_data_json");
        String curing = result.getString("curing_data_json");
        ProductBatch batch = new ProductBatch(
                UUID.fromString(result.getString("batch_id")),
                UUID.fromString(result.getString("owner_uuid")),
                DomainJson.composition(result.getString("strain_composition_json")),
                DomainJson.uuidSet(result.getString("genetic_origin_json")),
                result.getDouble("fresh_weight"),
                result.getDouble("dry_weight"),
                result.getDouble("quality_score"),
                QualityGrade.valueOf(result.getString("quality_grade")),
                result.getDouble("contamination"),
                result.getDouble("freshness"),
                HarvestTiming.valueOf(result.getString("harvest_timing")),
                drying == null ? Optional.empty() : Optional.of(drying(drying)),
                curing == null ? Optional.empty() : Optional.of(curing(curing)),
                Instant.ofEpochMilli(result.getLong("created_at")),
                result.getString("signature").strip(),
                BatchState.valueOf(result.getString("batch_state"))
        );
        return new StoredBatch(batch, result.getLong("row_version"));
    }

    private static String dryingJson(DryingData data) {
        JsonObject json = new JsonObject();
        json.addProperty("stage", data.stage().name());
        json.addProperty("startedAt", data.startedAt().toEpochMilli());
        json.addProperty("lastUpdatedAt", data.lastUpdatedAt().toEpochMilli());
        json.addProperty("targetMillis", data.targetDuration().toMillis());
        json.addProperty("cleanliness", data.rackCleanliness());
        json.addProperty("stability", data.rackStability());
        json.addProperty("speed", data.rackSpeed());
        json.addProperty("startingQuality", data.startingQuality());
        json.addProperty("startingContamination", data.startingContamination());
        return json.toString();
    }

    private static DryingData drying(String source) {
        JsonObject json = JsonParser.parseString(source).getAsJsonObject();
        return new DryingData(
                DryingStage.valueOf(text(json, "stage")),
                instant(json, "startedAt"),
                instant(json, "lastUpdatedAt"),
                Duration.ofMillis(number(json, "targetMillis").longValue()),
                number(json, "cleanliness").doubleValue(),
                number(json, "stability").doubleValue(),
                number(json, "speed").doubleValue(),
                number(json, "startingQuality").doubleValue(),
                number(json, "startingContamination").doubleValue()
        );
    }

    private static String curingJson(CuringData data) {
        JsonObject json = new JsonObject();
        json.addProperty("stage", data.stage().name());
        json.addProperty("startedAt", data.startedAt().toEpochMilli());
        json.addProperty("lastUpdatedAt", data.lastUpdatedAt().toEpochMilli());
        json.addProperty("targetMillis", data.targetDuration().toMillis());
        json.addProperty("cleanliness", data.containerCleanliness());
        json.addProperty("stability", data.containerStability());
        json.addProperty("startingQuality", data.startingQuality());
        json.addProperty("startingContamination", data.startingContamination());
        json.addProperty("startingFreshness", data.startingFreshness());
        return json.toString();
    }

    private static CuringData curing(String source) {
        JsonObject json = JsonParser.parseString(source).getAsJsonObject();
        return new CuringData(
                CuringStage.valueOf(text(json, "stage")),
                instant(json, "startedAt"),
                instant(json, "lastUpdatedAt"),
                Duration.ofMillis(number(json, "targetMillis").longValue()),
                number(json, "cleanliness").doubleValue(),
                number(json, "stability").doubleValue(),
                number(json, "startingQuality").doubleValue(),
                number(json, "startingContamination").doubleValue(),
                number(json, "startingFreshness").doubleValue()
        );
    }

    private static String text(JsonObject json, String key) {
        return json.get(key).getAsString();
    }

    private static Number number(JsonObject json, String key) {
        return json.get(key).getAsNumber();
    }

    private static Instant instant(JsonObject json, String key) {
        return Instant.ofEpochMilli(number(json, key).longValue());
    }

    private record StoredBatch(ProductBatch batch, long version) {
    }
}
