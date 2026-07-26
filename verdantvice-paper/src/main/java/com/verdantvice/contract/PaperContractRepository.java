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
import com.verdantvice.storage.DomainJson;

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

    public PaperContractRepository(DatabaseManager database, ItemRegistryRepository itemRegistry) {
        this.database = Objects.requireNonNull(database, "database");
        this.itemRegistry = Objects.requireNonNull(itemRegistry, "itemRegistry");
    }

    public CompletableFuture<StoredContract> create(Contract contract, int maximumActive) {
        Objects.requireNonNull(contract, "contract");
        return database.transaction(session -> {
            lockPlayer(session, contract.ownerUuid());
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
        return database.query(
                "SELECT " + COLUMNS + " FROM contracts WHERE owner_uuid=? ORDER BY created_at DESC",
                PaperContractRepository::map,
                ownerUuid);
    }

    public CompletableFuture<Optional<StoredContract>> find(UUID contractId) {
        return database.queryOne(
                "SELECT " + COLUMNS + " FROM contracts WHERE contract_id=?",
                PaperContractRepository::map,
                contractId);
    }

    /**
     * Genetic contracts are offered only for an origin the player has already
     * produced, so every generated contract is achievable.
     */
    public CompletableFuture<Optional<UUID>> candidateGeneticOrigin(UUID ownerUuid) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        return database.queryOne("""
                        SELECT genetic_origin_json FROM product_batches
                        WHERE owner_uuid=? AND batch_state IN ('DRIED', 'CURED')
                        ORDER BY created_at DESC LIMIT 1
                        """,
                result -> DomainJson.uuidSet(result.getString("genetic_origin_json"))
                        .stream().findFirst()
                        .orElseThrow(() -> new SQLException(
                                "Product batch has no genetic origin")),
                ownerUuid);
    }

    public CompletableFuture<Boolean> deliveryApplied(UUID instanceId, String operationId) {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(operationId, "operationId");
        return database.read(session -> session.exists(
                "SELECT instance_id FROM item_registry "
                        + "WHERE instance_id=? AND consumed_by_operation=?",
                instanceId, operationId));
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
            Optional<String> previous = session.queryOne(
                    "SELECT consumed_by_operation FROM item_registry "
                            + "WHERE instance_id=? AND consumed_by_operation=?",
                    result -> result.getString("consumed_by_operation"),
                    data.uniqueInstanceId(), operationId);
            StoredContract stored = find(session, contractId, true)
                    .orElseThrow(() -> new IllegalArgumentException("unknown-contract"));
            if (previous.isPresent()) {
                return new ContractDeliveryResult(
                        ContractDeliveryResult.Status.ALREADY_APPLIED,
                        stored.contract(), "", stored.contract().status() == ContractStatus.COMPLETED);
            }
            if (!stored.contract().ownerUuid().equals(playerUuid)) {
                return rejected(stored.contract(), "not-owner");
            }
            ItemRegistryRecord registered = itemRegistry.find(session, data.uniqueInstanceId(), true)
                    .orElseThrow(() -> new IllegalArgumentException("unregistered-item"));
            if (registered.state() != ItemRegistryState.ACTIVE
                    || !constantEquals(registered.signature(), payloadHash)
                    || !Objects.equals(registered.batchId(), data.batchId())) {
                return rejected(stored.contract(), "invalid-signature");
            }
            ProductBatch batch = loadBatch(session, data.batchId(), true)
                    .orElseThrow(() -> new IllegalArgumentException("unknown-batch"));
            ContractUpdate update = contractService.deliver(stored.contract(), batch, true, now);
            if (!update.accepted()) {
                saveIfExpired(session, stored, update.contract(), now);
                return rejected(update.contract(), update.reasonCode());
            }
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

    private Optional<ProductBatch> loadBatch(SqlSession session, UUID batchId, boolean forUpdate)
            throws SQLException {
        if (batchId == null) return Optional.empty();
        String suffix = forUpdate && session.databaseType().isNetworkDatabase() ? " FOR UPDATE" : "";
        return session.queryOne("""
                        SELECT batch_id, owner_uuid, strain_composition_json, genetic_origin_json,
                               fresh_weight, dry_weight, quality_score, quality_grade,
                               contamination, freshness, harvest_timing, created_at,
                               signature, batch_state
                        FROM product_batches WHERE batch_id=?
                        """ + suffix,
                PaperContractRepository::mapBatch,
                batchId);
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

    private static ProductBatch mapBatch(ResultSet result) throws SQLException {
        try {
            double dry = result.getDouble("dry_weight");
            if (result.wasNull()) dry = 0.0;
            double quality = result.getDouble("quality_score");
            return new ProductBatch(
                    UUID.fromString(result.getString("batch_id")),
                    UUID.fromString(result.getString("owner_uuid")),
                    DomainJson.composition(result.getString("strain_composition_json")),
                    DomainJson.uuidSet(result.getString("genetic_origin_json")),
                    result.getDouble("fresh_weight"), dry, quality,
                    QualityGrade.valueOf(result.getString("quality_grade")),
                    result.getDouble("contamination"), result.getDouble("freshness"),
                    HarvestTiming.valueOf(result.getString("harvest_timing")),
                    Optional.empty(), Optional.empty(),
                    Instant.ofEpochMilli(result.getLong("created_at")),
                    result.getString("signature"),
                    BatchState.valueOf(result.getString("batch_state")));
        } catch (RuntimeException exception) {
            throw new SQLException("Malformed product batch row", exception);
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
