package com.verdantvice.storage;

import com.verdantvice.database.DatabaseManager;
import com.verdantvice.database.AmbiguousCommitException;
import com.verdantvice.database.SqlSession;
import com.verdantvice.database.item.CasStatus;
import com.verdantvice.database.item.ItemRegistration;
import com.verdantvice.database.item.ItemRegistryRecord;
import com.verdantvice.database.item.ItemRegistryRepository;
import com.verdantvice.database.item.ItemRegistryState;
import com.verdantvice.database.item.RegistrationStatus;
import com.verdantvice.genetics.CustomStrainRecord;
import com.verdantvice.genetics.GeneticLine;
import com.verdantvice.genetics.PendingGeneticsSeed;
import com.verdantvice.security.CustomItemType;
import com.verdantvice.security.ProcessingItemValidator;
import com.verdantvice.security.SecureItemService.ValidatedIdentity;
import com.verdantvice.security.SignedItemData;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class CustomStrainRepository {
    private static final String PENDING_PREFIX = "{\"geneticsDelivery\":true,";
    private final DatabaseManager database;
    private final ItemRegistryRepository items;
    private final ProcessingItemValidator validator = new ProcessingItemValidator();

    public CustomStrainRepository(DatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
        this.items = new ItemRegistryRepository(database);
    }

    public CompletableFuture<List<CustomStrainRecord>> loadApproved() {
        return database.query(
                """
                SELECT * FROM custom_strains
                WHERE approved = ?
                ORDER BY generation ASC, created_at ASC
                """,
                this::map,
                true
        );
    }

    public CompletableFuture<Optional<CustomStrainRecord>> find(String strainId) {
        return database.queryOne(
                "SELECT * FROM custom_strains WHERE strain_id = ?",
                this::map,
                strainId
        );
    }

    public CompletableFuture<Void> create(CustomStrainRecord record) {
        Objects.requireNonNull(record, "record");
        return database.<Void>transaction(session -> {
            create(session, record);
            return null;
        });
    }

    private CompletableFuture<Boolean> committed(CustomStrainRecord record,
                                                 ValidatedIdentity first,
                                                 ValidatedIdentity second,
                                                 ItemRegistration child) {
        return database.read(session -> {
            ItemRegistryRecord output = items.find(session, child.instanceId(), false).orElse(null);
            if (output == null || !Objects.equals(output.ownerUuid(), child.ownerUuid())
                    || !Objects.equals(output.strainId(), child.strainId())
                    || !output.signature().equals(child.signature())
                    || !Objects.equals(output.metadataJson(), child.metadataJson())
                    || !session.exists("SELECT 1 FROM custom_strains WHERE strain_id = ? AND creator_uuid = ?",
                    record.strainId(), record.line().creatorUuid())) {
                return false;
            }
            String operation = "genetics:" + record.strainId();
            for (ValidatedIdentity parent : List.of(first, second)) {
                ItemRegistryRecord row = items.find(session, parent.data().uniqueInstanceId(), false).orElse(null);
                if (row == null || row.state() != ItemRegistryState.CONSUMED
                        || !operation.equals(row.consumedByOperation())) {
                    return false;
                }
            }
            return true;
        });
    }

    /** Both inputs, the line, output identity and delivery receipt commit together. */
    public CompletableFuture<Void> cross(
            CustomStrainRecord record,
            ValidatedIdentity first,
            ValidatedIdentity second,
            ItemRegistration child
    ) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        Objects.requireNonNull(child, "child");
        UUID owner = record.line().creatorUuid();
        if (first.data().uniqueInstanceId().equals(second.data().uniqueInstanceId())
                || first.data().strain().equals(second.data().strain())) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("same_parent_strain"));
        }
        if (!record.line().parentStrainIds().equals(Set.of(first.data().strain(), second.data().strain()))) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("invalid_parent_lineage"));
        }
        if (!owner.equals(child.ownerUuid())
                || !record.strainId().equals(child.strainId())
                || !CustomItemType.SEED.name().equals(child.itemType())
                || child.batchId() != null
                || child.instanceId().equals(first.data().uniqueInstanceId())
                || child.instanceId().equals(second.data().uniqueInstanceId())) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("invalid_child_identity"));
        }
        JsonObject receipt = new JsonObject();
        receipt.addProperty("geneticsDelivery", true);
        receipt.addProperty("firstParent", first.data().uniqueInstanceId().toString());
        receipt.addProperty("secondParent", second.data().uniqueInstanceId().toString());
        ItemRegistration durableChild = new ItemRegistration(
                child.instanceId(), child.itemType(), child.itemId(), child.batchId(),
                child.ownerUuid(), child.quality(), child.strainId(), child.createdAt(),
                child.signature(), child.schemaVersion(), receipt.toString());
        return database.<Void>transaction(session -> {
            Instant now = Instant.now();
            // Stable lock ordering prevents two reversed-parent requests deadlocking on MySQL.
            for (ValidatedIdentity parent : List.of(first, second).stream()
                    .sorted(Comparator.comparing(value -> value.data().uniqueInstanceId()))
                    .toList()) {
                consume(session, parent, owner, "genetics:" + record.strainId(), now);
            }
            create(session, record);
            if (items.register(session, durableChild).status() != RegistrationStatus.REGISTERED) {
                throw new IllegalStateException("child_identity_conflict");
            }
            return null;
        }).exceptionallyCompose(failure -> {
            Throwable cause = failure;
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (!(cause instanceof AmbiguousCommitException)) {
                return CompletableFuture.failedFuture(failure);
            }
            // Never rerun a transaction after an uncertain commit. Exact output,
            // line and parent operation evidence tell us whether it committed.
            return committed(record, first, second, durableChild).thenCompose(committed ->
                    committed ? CompletableFuture.completedFuture(null)
                            : CompletableFuture.failedFuture(failure));
        });
    }

    public CompletableFuture<List<PendingGeneticsSeed>> pendingSeeds(UUID owner) {
        Objects.requireNonNull(owner, "owner");
        return database.query("""
                SELECT instance_id, item_id, owner_uuid, quality, strain_id,
                       created_at, schema_version, metadata_json
                FROM item_registry
                WHERE owner_uuid = ? AND item_type = ? AND registry_state = ?
                  AND metadata_json LIKE ?
                ORDER BY created_at ASC, instance_id ASC
                """, result -> {
            JsonObject receipt = JsonParser.parseString(result.getString("metadata_json")).getAsJsonObject();
            SignedItemData data = new SignedItemData(
                    CustomItemType.SEED, result.getString("item_id"),
                    UUID.fromString(result.getString("instance_id")), null,
                    UUID.fromString(result.getString("owner_uuid")), result.getDouble("quality"),
                    result.getString("strain_id"), Instant.ofEpochMilli(result.getLong("created_at")),
                    result.getInt("schema_version"));
            return new PendingGeneticsSeed(data,
                    UUID.fromString(receipt.get("firstParent").getAsString()),
                    UUID.fromString(receipt.get("secondParent").getAsString()));
        }, owner, CustomItemType.SEED, ItemRegistryState.ACTIVE, PENDING_PREFIX + "%");
    }

    /** Called only after the inventory has been durably saved, never before delivery. */
    public CompletableFuture<Void> acknowledgeSeed(UUID owner, UUID childId) {
        return database.update("""
                UPDATE item_registry SET metadata_json = NULL, updated_at = ?,
                  row_version = row_version + 1
                WHERE instance_id = ? AND owner_uuid = ? AND metadata_json LIKE ?
                """, Instant.now(), childId, owner, PENDING_PREFIX + "%").thenApply(ignored -> null);
    }

    private void consume(SqlSession session, ValidatedIdentity identity, UUID owner,
                         String operation, Instant now) throws SQLException {
        validator.validateData(identity.data(), Set.of(CustomItemType.SEED), owner);
        ItemRegistryRecord row = items.find(session, identity.data().uniqueInstanceId(), true)
                .orElseThrow(() -> new IllegalStateException("unregistered_item"));
        validator.validateRecord(row, identity.data(), identity.payloadHash(), owner);
        UUID token = UUID.randomUUID();
        var reserved = items.reserve(session, row.instanceId(), row.version(), identity.payloadHash(),
                token, owner.toString(), now.plus(Duration.ofMinutes(1)), operation, now);
        if (reserved.status() != CasStatus.APPLIED) {
            throw new IllegalStateException("item_reservation_failed");
        }
        var consumed = items.consume(session, row.instanceId(), reserved.record().orElseThrow().version(),
                identity.payloadHash(), token, operation, now);
        if (consumed.status() != CasStatus.APPLIED) {
            throw new IllegalStateException("item_consumption_failed");
        }
    }

    private void create(SqlSession session, CustomStrainRecord record) throws SQLException {
            PlayerDataRepository.ensure(
                    session,
                    record.line().creatorUuid(),
                    null,
                    record.line().createdAt()
            );
            session.update(
                    """
                    INSERT INTO custom_strains (
                      strain_id, creator_uuid, strain_name, parent_strains_json,
                      dominant_traits_json, colors_json, effects_json, generation,
                      stability, approved, created_at, updated_at, row_version
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, 0)
                    """,
                    record.strainId(),
                    record.line().creatorUuid(),
                    record.line().name(),
                    DomainJson.stringSet(record.line().parentStrainIds()),
                    DomainJson.composition(record.line().dominantTraits()),
                    DomainJson.stringSet(record.line().colors()),
                    DomainJson.stringSet(record.line().effects()),
                    record.line().generation(),
                    record.line().stability(),
                    record.line().createdAt(),
                    record.updatedAt()
            );
    }

    private CustomStrainRecord map(ResultSet result) throws SQLException {
        String strainId = result.getString("strain_id");
        Instant createdAt = Instant.ofEpochMilli(result.getLong("created_at"));
        Instant updatedAt = Instant.ofEpochMilli(result.getLong("updated_at"));
        GeneticLine line = new GeneticLine(
                UUID.nameUUIDFromBytes(("verdantvice:line:" + strainId)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                result.getString("strain_name"),
                DomainJson.stringSet(result.getString("parent_strains_json")),
                UUID.fromString(result.getString("creator_uuid")),
                result.getInt("generation"),
                DomainJson.composition(result.getString("dominant_traits_json")),
                DomainJson.stringSet(result.getString("colors_json")),
                DomainJson.stringSet(result.getString("effects_json")),
                result.getDouble("stability"),
                createdAt
        );
        return new CustomStrainRecord(strainId, line, updatedAt, result.getLong("row_version"));
    }
}
