package com.verdantvice.contract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.verdantvice.database.DatabaseManager;
import com.verdantvice.database.SqlNull;
import com.verdantvice.database.SqlSession;
import com.verdantvice.database.item.CasStatus;
import com.verdantvice.database.item.ItemCasResult;
import com.verdantvice.database.item.ItemRegistryRecord;
import com.verdantvice.database.item.ItemRegistryRepository;
import com.verdantvice.database.item.ItemRegistryState;
import com.verdantvice.harvest.HarvestTiming;
import com.verdantvice.processing.BatchState;
import com.verdantvice.processing.ProductBatch;
import com.verdantvice.processing.QualityGrade;
import com.verdantvice.security.SignedItemData;
import com.verdantvice.security.ItemUsePolicy;
import com.verdantvice.storage.DomainJson;
import com.verdantvice.storage.BatchService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.JDBCType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class PaperContractRepository {
    private static final String COLUMNS = """
            contract_id, owner_uuid, herbalist_uuid, requirements_json, deadline,
            reward_json, reputation_reward, contract_status, reward_claimed,
            reward_operation_id, created_at, updated_at, completed_at, row_version
            """.replace('\n', ' ').strip();

    private final DatabaseManager database;
    private final ItemRegistryRepository itemRegistry;
    private final ContractService contractService = new ContractService();
    private final BatchService batchService;
    private final ItemUsePolicy itemUsePolicy;

    public PaperContractRepository(
            DatabaseManager database,
            ItemRegistryRepository itemRegistry,
            BatchService batchService
    ) {
        this(database, itemRegistry, batchService, new ItemUsePolicy(true));
    }

    public PaperContractRepository(
            DatabaseManager database,
            ItemRegistryRepository itemRegistry,
            BatchService batchService,
            ItemUsePolicy itemUsePolicy
    ) {
        this.database = Objects.requireNonNull(database, "database");
        this.itemRegistry = Objects.requireNonNull(itemRegistry, "itemRegistry");
        this.batchService = Objects.requireNonNull(batchService, "batchService");
        this.itemUsePolicy = Objects.requireNonNull(itemUsePolicy, "itemUsePolicy");
    }

    public CompletableFuture<StoredContract> create(Contract contract, int maximumActive) {
        Objects.requireNonNull(contract, "contract");
        return database.transaction(session -> {
            lockPlayer(session, contract.ownerUuid());
            expireOverdue(session, contract.ownerUuid(), Instant.now());
            int active = session.queryOne("""
                            SELECT COUNT(*) AS total FROM contracts
                            WHERE owner_uuid=? AND contract_status IN ('ACTIVE', 'COMPLETED')
                            """,
                    result -> result.getInt("total"),
                    contract.ownerUuid()).orElse(0);
            if (active >= maximumActive) throw new IllegalStateException("contract-limit");
            session.update("""
                            INSERT INTO contracts (
                              contract_id, owner_uuid, herbalist_uuid, requirements_json,
                              deadline, reward_json, reputation_reward, contract_status,
                              reward_claimed, reward_operation_id, created_at, updated_at,
                              completed_at, row_version
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, 0)
                            """,
                    contract.contractId(), contract.ownerUuid(), contract.herbalistUuid(),
                    requirementsJson(contract), contract.deadline(), rewardJson(contract.reward()),
                    contract.reputationReward(), contract.status(),
                    SqlNull.of(JDBCType.VARCHAR), contract.createdAt(), contract.createdAt(),
                    SqlNull.of(JDBCType.BIGINT));
            return find(session, contract.contractId(), false)
                    .orElseThrow(() -> new SQLException("Created contract disappeared"));
        });
    }

    public CompletableFuture<List<StoredContract>> list(UUID ownerUuid) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        return database.transaction(session -> {
            String suffix = session.databaseType().isNetworkDatabase() ? " FOR UPDATE" : "";
            if (!session.exists("SELECT player_uuid FROM players WHERE player_uuid=?" + suffix, ownerUuid)) {
                return List.of();
            }
            expireOverdue(session, ownerUuid, Instant.now());
            return session.query(
                    "SELECT " + COLUMNS + " FROM contracts WHERE owner_uuid=? ORDER BY created_at DESC",
                    PaperContractRepository::map,
                    ownerUuid);
        });
    }

    public CompletableFuture<Optional<StoredContract>> find(UUID contractId) {
        return database.queryOne(
                "SELECT " + COLUMNS + " FROM contracts WHERE contract_id=?",
                PaperContractRepository::map,
                contractId);
    }

    /**
     * Genetic objectives use a verified owned batch with an unconsumed output,
     * rather than an unrelated strain or an already sold genetic origin.
     */
    public CompletableFuture<Optional<UUID>> candidateGeneticOrigin(UUID ownerUuid) {
        return candidateGeneticBatch(ownerUuid).thenApply(batch -> batch.flatMap(value ->
                value.geneticOrigin().stream().sorted().findFirst()));
    }

    public CompletableFuture<Optional<ProductBatch>> candidateGeneticBatch(UUID ownerUuid) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        return database.read(session -> {
            List<UUID> candidates = session.query("""
                    SELECT batch_id FROM product_batches batch
                    WHERE owner_uuid=? AND batch_state IN ('DRIED', 'CURED') AND dry_weight>0
                      AND EXISTS (SELECT 1 FROM item_registry item
                          WHERE item.batch_id=batch.batch_id AND item.owner_uuid=batch.owner_uuid
                            AND item.registry_state='ACTIVE'
                            AND item.item_type IN ('DRY_BATCH', 'CURED_BATCH', 'PACKAGE'))
                    ORDER BY created_at DESC LIMIT 1
                    """, row -> UUID.fromString(row.getString("batch_id")), ownerUuid);
            if (candidates.isEmpty()) return Optional.empty();
            return Optional.of(batchService.require(session, candidates.get(0), ownerUuid,
                    Set.of(BatchState.DRIED, BatchState.CURED), false).batch());
        });
    }

    public CompletableFuture<Boolean> deliveryApplied(UUID instanceId, String operationId) {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(operationId, "operationId");
        return database.read(session -> itemUsePolicy.consumptionRecorded(
                session, itemRegistry, instanceId, operationId));
    }

    public CompletableFuture<ContractDeliveryResult> deliver(
            UUID contractId,
            SignedItemData data,
            String payloadHash,
            UUID playerUuid,
            String operationId,
            Instant now
    ) {
        return database.transaction(session -> {
            boolean previous = itemUsePolicy.consumptionRecorded(
                    session, itemRegistry, data.uniqueInstanceId(), operationId);
            StoredContract stored = find(session, contractId, true)
                    .orElseThrow(() -> new IllegalArgumentException("unknown-contract"));
            if (previous) {
                return new ContractDeliveryResult(
                        ContractDeliveryResult.Status.ALREADY_APPLIED,
                        stored.contract(), "", stored.contract().status() == ContractStatus.COMPLETED);
            }
            if (!stored.contract().ownerUuid().equals(playerUuid)) {
                return rejected(stored.contract(), "not-owner");
            }
            boolean enforce = itemUsePolicy.antiDupeEnabled();
            ItemRegistryRecord registered = itemRegistry.find(session, data.uniqueInstanceId(), true)
                    .orElse(null);
            if (enforce && registered == null) {
                throw new IllegalArgumentException("unregistered-item");
            }
            if (registered != null && ((enforce
                    && registered.state() != ItemRegistryState.ACTIVE)
                    || !constantEquals(registered.signature(), payloadHash)
                    || !registered.itemId().equals(data.itemId())
                    || !Objects.equals(registered.batchId(), data.batchId()))) {
                return rejected(stored.contract(), "invalid-signature");
            }
            ProductBatch batch = batchService.require(
                    session, data.batchId(), playerUuid,
                    java.util.EnumSet.allOf(BatchState.class), true).batch();
            batchService.remember(batch);
            ContractUpdate update = contractService.deliver(stored.contract(), batch, true, now);
            if (!update.accepted()) {
                saveIfExpired(session, stored, update.contract(), now);
                return rejected(update.contract(), update.reasonCode());
            }
            registered = itemUsePolicy.prepareConsumption(
                    session, itemRegistry, data, payloadHash, playerUuid, now);
            UUID reservation = UUID.randomUUID();
            ItemCasResult reserved = itemRegistry.reserve(
                    session, registered.instanceId(), registered.version(), payloadHash,
                    reservation, playerUuid.toString(), now.plus(Duration.ofMinutes(2)),
                    operationId, now);
            if (reserved.status() == CasStatus.REJECTED || reserved.current() == null) {
                return rejected(stored.contract(), "item-reservation-failed");
            }
            ItemCasResult consumed = itemRegistry.consume(
                    session, registered.instanceId(), reserved.current().version(), payloadHash,
                    reservation, operationId, now);
            if (consumed.status() == CasStatus.REJECTED) {
                return rejected(stored.contract(), "item-consumption-failed");
            }
            if (!save(session, stored, update.contract(), false, null, now)) {
                throw new SQLException("Concurrent contract update");
            }
            return new ContractDeliveryResult(
                    ContractDeliveryResult.Status.APPLIED,
                    update.contract(), "", update.newlyCompleted());
        });
    }

    public CompletableFuture<ContractClaimResult> claim(
            UUID contractId,
            UUID playerUuid,
            String operationId,
            Instant now
    ) {
        return database.transaction(session -> {
            lockPlayer(session, playerUuid);
            StoredContract stored = find(session, contractId, true)
                    .orElseThrow(() -> new IllegalArgumentException("unknown-contract"));
            if (!stored.contract().ownerUuid().equals(playerUuid)) {
                return new ContractClaimResult(
                        ContractClaimResult.Status.REJECTED, stored.contract(),
                        stored.contract().reward(), "not-owner");
            }
            if (stored.rewardClaimed()) {
                ContractClaimResult.Status status = operationId.equals(stored.rewardOperationId())
                        ? ContractClaimResult.Status.ALREADY_CLAIMED
                        : ContractClaimResult.Status.REJECTED;
                return new ContractClaimResult(
                        status, stored.contract(), stored.contract().reward(), "already-claimed");
            }
            if (stored.contract().status() != ContractStatus.COMPLETED) {
                return new ContractClaimResult(
                        ContractClaimResult.Status.REJECTED, stored.contract(),
                        stored.contract().reward(), "contract-not-completed");
            }
            Contract claimed = contractService.claim(stored.contract());
            if (!save(session, stored, claimed, true, operationId, now)) {
                throw new SQLException("Concurrent reward claim");
            }
            double reputation = claimed.reputationReward();
            session.update("""
                            UPDATE players
                            SET reputation=CASE WHEN reputation+?>100 THEN 100 ELSE reputation+? END,
                                updated_at=?, row_version=row_version+1
                            WHERE player_uuid=?
                            """,
                    reputation, reputation, now, playerUuid);
            return new ContractClaimResult(
                    ContractClaimResult.Status.CLAIMED, claimed, claimed.reward(), "");
        });
    }

    private Optional<StoredContract> find(SqlSession session, UUID contractId, boolean forUpdate)
            throws SQLException {
        String suffix = forUpdate && session.databaseType().isNetworkDatabase() ? " FOR UPDATE" : "";
        return session.queryOne(
                "SELECT " + COLUMNS + " FROM contracts WHERE contract_id=?" + suffix,
                PaperContractRepository::map,
                contractId);
    }

    private boolean save(
            SqlSession session,
            StoredContract expected,
            Contract replacement,
            boolean claimed,
            String operationId,
            Instant now
    ) throws SQLException {
        int changed = session.update("""
                        UPDATE contracts
                        SET requirements_json=?, contract_status=?, reward_claimed=?,
                            reward_operation_id=?, completed_at=?, updated_at=?,
                            row_version=row_version+1
                        WHERE contract_id=? AND row_version=?
                        """,
                requirementsJson(replacement), replacement.status(), claimed,
                operationId == null ? SqlNull.of(JDBCType.VARCHAR) : operationId,
                replacement.completedAt().<Object>map(value -> value)
                        .orElseGet(() -> SqlNull.of(JDBCType.BIGINT)),
                now, replacement.contractId(), expected.version());
        return changed == 1;
    }

    private void saveIfExpired(
            SqlSession session,
            StoredContract stored,
            Contract replacement,
            Instant now
    ) throws SQLException {
        if (replacement.status() == ContractStatus.EXPIRED
                && stored.contract().status() == ContractStatus.ACTIVE) {
            save(session, stored, replacement, false, null, now);
        }
    }

    private static void expireOverdue(SqlSession session, UUID ownerUuid, Instant now) throws SQLException {
        // Completion keeps its claimable reward even after the delivery deadline.
        // Equality is still on time, matching ContractService.deliver's isAfter check.
        session.update("""
                        UPDATE contracts SET contract_status='EXPIRED', updated_at=?,
                            row_version=row_version+1
                        WHERE owner_uuid=? AND contract_status='ACTIVE' AND deadline<?
                        """, now, ownerUuid, now);
    }

    private static StoredContract map(ResultSet result) throws SQLException {
        try {
            UUID id = UUID.fromString(result.getString("contract_id"));
            UUID owner = UUID.fromString(result.getString("owner_uuid"));
            UUID herbalist = UUID.fromString(result.getString("herbalist_uuid"));
            Instant created = Instant.ofEpochMilli(result.getLong("created_at"));
            Instant deadline = Instant.ofEpochMilli(result.getLong("deadline"));
            ContractStatus status = ContractStatus.valueOf(result.getString("contract_status"));
            JsonObject requirementsJson = JsonParser.parseString(
                    result.getString("requirements_json")).getAsJsonObject();
            ContractType type = ContractType.valueOf(requirementsJson.get("type").getAsString());
            List<ContractRequirement> requirements = parseRequirements(requirementsJson);
            Map<Integer, Double> delivered = parseDelivered(requirementsJson);
            Set<UUID> batches = parseBatchIds(requirementsJson);
            ContractReward reward = parseReward(result.getString("reward_json"));
            long completedValue = result.getLong("completed_at");
            Optional<Instant> completed = result.wasNull()
                    ? Optional.empty() : Optional.of(Instant.ofEpochMilli(completedValue));
            Contract contract = new Contract(
                    id, owner, herbalist, type, requirements, deadline, reward,
                    result.getDouble("reputation_reward"), status, created, completed,
                    delivered, batches);
            return new StoredContract(
                    contract, result.getBoolean("reward_claimed"),
                    result.getString("reward_operation_id"), result.getLong("row_version"));
        } catch (RuntimeException exception) {
            throw new SQLException("Malformed contract row", exception);
        }
    }

    private static String requirementsJson(Contract contract) {
        JsonObject root = new JsonObject();
        root.addProperty("type", contract.type().name());
        JsonArray requirements = new JsonArray();
        for (ContractRequirement requirement : contract.requirements()) {
            JsonObject json = new JsonObject();
            requirement.strainId().ifPresent(value -> json.addProperty("strainId", value));
            json.addProperty("requiredWeight", requirement.requiredWeight());
            json.addProperty("minimumQuality", requirement.minimumQuality().name());
            json.addProperty("curedRequired", requirement.curedRequired());
            requirement.geneticOrigin().ifPresent(value -> json.addProperty("geneticOrigin", value.toString()));
            requirements.add(json);
        }
        root.add("requirements", requirements);
        JsonObject delivered = new JsonObject();
        contract.deliveredWeight().forEach((index, weight) ->
                delivered.addProperty(Integer.toString(index), weight));
        root.add("deliveredWeight", delivered);
        JsonArray batches = new JsonArray();
        contract.deliveredBatchIds().stream().map(UUID::toString).sorted().forEach(batches::add);
        root.add("deliveredBatchIds", batches);
        return root.toString();
    }

    private static String rewardJson(ContractReward reward) {
        JsonObject json = new JsonObject();
        json.addProperty("emeralds", reward.emeralds());
        json.addProperty("economyAmount", reward.economyAmount());
        json.addProperty("reputation", reward.reputation());
        return json.toString();
    }

    private static List<ContractRequirement> parseRequirements(JsonObject root) {
        List<ContractRequirement> result = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("requirements")) {
            JsonObject json = element.getAsJsonObject();
            result.add(new ContractRequirement(
                    json.has("strainId") ? Optional.of(json.get("strainId").getAsString()) : Optional.empty(),
                    json.get("requiredWeight").getAsDouble(),
                    QualityGrade.valueOf(json.get("minimumQuality").getAsString()),
                    json.get("curedRequired").getAsBoolean(),
                    json.has("geneticOrigin")
                            ? Optional.of(UUID.fromString(json.get("geneticOrigin").getAsString()))
                            : Optional.empty()));
        }
        return List.copyOf(result);
    }

    private static Map<Integer, Double> parseDelivered(JsonObject root) {
        if (!root.has("deliveredWeight")) return Map.of();
        Map<Integer, Double> values = new HashMap<>();
        root.getAsJsonObject("deliveredWeight").entrySet().forEach(entry ->
                values.put(Integer.parseInt(entry.getKey()), entry.getValue().getAsDouble()));
        return Map.copyOf(values);
    }

    private static Set<UUID> parseBatchIds(JsonObject root) {
        if (!root.has("deliveredBatchIds")) return Set.of();
        Set<UUID> values = new HashSet<>();
        root.getAsJsonArray("deliveredBatchIds").forEach(element ->
                values.add(UUID.fromString(element.getAsString())));
        return Set.copyOf(values);
    }

    private static ContractReward parseReward(String source) {
        JsonObject json = JsonParser.parseString(source).getAsJsonObject();
        return new ContractReward(
                json.get("emeralds").getAsInt(),
                json.get("economyAmount").getAsDouble(),
                json.get("reputation").getAsDouble());
    }

    private static ContractDeliveryResult rejected(Contract contract, String reason) {
        return new ContractDeliveryResult(
                ContractDeliveryResult.Status.REJECTED, contract, reason, false);
    }

    private static boolean constantEquals(String first, String second) {
        return first != null && second != null && MessageDigest.isEqual(
                first.getBytes(StandardCharsets.US_ASCII),
                second.getBytes(StandardCharsets.US_ASCII));
    }

    private static void lockPlayer(SqlSession session, UUID playerUuid) throws SQLException {
        String suffix = session.databaseType().isNetworkDatabase() ? " FOR UPDATE" : "";
        if (!session.exists("SELECT player_uuid FROM players WHERE player_uuid=?" + suffix, playerUuid)) {
            throw new SQLException("Player row is missing for " + playerUuid);
        }
    }
}
