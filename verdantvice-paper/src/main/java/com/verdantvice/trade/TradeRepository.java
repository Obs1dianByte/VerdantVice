package com.verdantvice.trade;

import com.verdantvice.config.ConfigManager;
import com.verdantvice.database.DatabaseManager;
import com.verdantvice.database.SqlSession;
import com.verdantvice.database.item.CasStatus;
import com.verdantvice.database.item.ItemCasResult;
import com.verdantvice.database.item.ItemRegistryRecord;
import com.verdantvice.database.item.ItemRegistryRepository;
import com.verdantvice.database.item.ItemRegistryState;
import com.verdantvice.harvest.HarvestTiming;
import com.verdantvice.herbalist.HerbalistTier;
import com.verdantvice.market.MarketPricingService;
import com.verdantvice.market.MarketQuote;
import com.verdantvice.market.MarketRepository;
import com.verdantvice.market.MarketState;
import com.verdantvice.market.PaperMarketRecord;
import com.verdantvice.processing.BatchState;
import com.verdantvice.processing.ProductBatch;
import com.verdantvice.processing.QualityGrade;
import com.verdantvice.security.CustomItemType;
import com.verdantvice.security.SignedItemData;
import com.verdantvice.storage.DomainJson;
import com.verdantvice.storage.BatchService;
import com.verdantvice.strain.Strain;
import com.verdantvice.strain.StrainRegistry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class TradeRepository {
    private final DatabaseManager database;
    private final ItemRegistryRepository itemRegistry;
    private final MarketRepository markets;
    private final StrainRegistry strains;
    private final ConfigManager configs;
    private final BatchService batchService;
    private final MarketPricingService pricing = new MarketPricingService();
    private final TradeLimitService limits = new TradeLimitService();
    private final SaleItemPolicy saleItems = new SaleItemPolicy();

    public TradeRepository(
            DatabaseManager database,
            ItemRegistryRepository itemRegistry,
            MarketRepository markets,
            StrainRegistry strains,
            ConfigManager configs,
            BatchService batchService
    ) {
        this.database = Objects.requireNonNull(database, "database");
        this.itemRegistry = Objects.requireNonNull(itemRegistry, "itemRegistry");
        this.markets = Objects.requireNonNull(markets, "markets");
        this.strains = Objects.requireNonNull(strains, "strains");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.batchService = Objects.requireNonNull(batchService, "batchService");
    }

    public CompletableFuture<PreparedSale> prepareSale(
            SignedItemData data,
            String payloadHash,
            UUID playerUuid,
            UUID herbalistUuid,
            Instant now
    ) {
        validateIdentity(data, payloadHash, playerUuid);
        return database.read(session -> buildQuote(
                session, data, payloadHash, playerUuid, herbalistUuid, now, false))
                .thenApply(quote -> {
                    batchService.remember(quote.batch());
                    return quote;
                });
    }

    public CompletableFuture<SaleCommitResult> commit(PreparedSale quote, String operationId, Instant now) {
        Objects.requireNonNull(quote, "quote");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(now, "now");
        if (!now.isBefore(quote.expiresAt())) {
            return CompletableFuture.completedFuture(
                    new SaleCommitResult(SaleCommitResult.Status.PRICE_CHANGED, 0, "quote-expired"));
        }
        return database.transaction(session -> {
            Optional<Long> already = session.queryOne(
                    "SELECT total_price FROM trade_history WHERE operation_id=?",
                    result -> Math.round(result.getDouble("total_price")),
                    operationId);
            if (already.isPresent()) {
                return new SaleCommitResult(SaleCommitResult.Status.ALREADY_COMMITTED,
                        Math.toIntExact(already.get()), "");
            }
            lockPlayer(session, quote.playerUuid());
            PreparedSale current = buildQuote(
                    session, quote.itemData(), quote.itemPayloadHash(), quote.playerUuid(),
                    quote.herbalistUuid(), now, true);
            if (current.payout() != quote.payout()
                    || !current.marketVersions().equals(quote.marketVersions())) {
                return new SaleCommitResult(SaleCommitResult.Status.PRICE_CHANGED,
                        current.payout(), "price-changed");
            }

            ItemRegistryRecord registered = itemRegistry.find(session, quote.itemInstanceId(), true)
                    .orElseThrow(() -> new IllegalStateException("unregistered-item"));
            if (registered.state() != ItemRegistryState.ACTIVE
                    || !constantEquals(registered.signature(), quote.itemPayloadHash())
                    || !Objects.equals(registered.batchId(), quote.batch().batchId())) {
                return new SaleCommitResult(SaleCommitResult.Status.REJECTED, 0,
                        "item-already-used-or-modified");
            }
            UUID reservation = UUID.randomUUID();
            ItemCasResult reserved = itemRegistry.reserve(
                    session,
                    registered.instanceId(),
                    registered.version(),
                    quote.itemPayloadHash(),
                    reservation,
                    quote.playerUuid().toString(),
                    now.plus(Duration.ofMinutes(2)),
                    operationId,
                    now);
            if (reserved.status() == CasStatus.REJECTED || reserved.current() == null) {
                return new SaleCommitResult(SaleCommitResult.Status.REJECTED, 0,
                        "item-reservation-failed");
            }
            ItemCasResult consumed = itemRegistry.consume(
                    session,
                    registered.instanceId(),
                    reserved.current().version(),
                    quote.itemPayloadHash(),
                    reservation,
                    operationId,
                    now);
            if (consumed.status() == CasStatus.REJECTED) {
                return new SaleCommitResult(SaleCommitResult.Status.REJECTED, 0,
                        "item-consumption-failed");
            }

            String dominant = dominantStrain(quote.batch());
            session.update("""
                            INSERT INTO trade_history (
                              operation_id, player_uuid, herbalist_uuid, batch_id, strain_id,
                              quantity, unit_price, total_price, trade_direction,
                              quality_score, created_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'SELL', ?, ?)
                            """,
                    operationId, quote.playerUuid(), quote.herbalistUuid(), quote.batch().batchId(),
                    dominant, quote.quantity(), quote.unitPrice(), quote.payout(),
                    quote.batch().qualityScore(), now);
            updateMarketAfterSale(session, quote.batch(), quote.quantity(), now);
            String lockSuffix = session.databaseType().isNetworkDatabase()
                    ? " FOR UPDATE" : "";
            long currentExperience = session.queryOne(
                    "SELECT experience FROM herbalists WHERE herbalist_uuid=?" + lockSuffix,
                    result -> result.getLong("experience"),
                    quote.herbalistUuid()
            ).orElseThrow(() -> new SQLException("Unknown herbalist " + quote.herbalistUuid()));
            long resultingExperience = Math.addExact(currentExperience, 1L);
            HerbalistTier resultingTier = HerbalistTier.forExperience(
                    resultingExperience, configs.get("villagers.yml"));
            session.update("""
                            UPDATE herbalists
                            SET experience=?, reputation=CASE
                                  WHEN reputation>=100 THEN 100 ELSE reputation+1 END,
                                herbalist_level=?, updated_at=?, row_version=row_version+1
                            WHERE herbalist_uuid=?
                            """,
                    resultingExperience, resultingTier.databaseLevel(),
                    now, quote.herbalistUuid());
            session.update("""
                            UPDATE players
                            SET reputation=CASE
                                  WHEN reputation>=100 THEN 100 ELSE reputation+1 END,
                                updated_at=?, row_version=row_version+1
                            WHERE player_uuid=?
                            """,
                    now, quote.playerUuid());
            return new SaleCommitResult(SaleCommitResult.Status.COMMITTED, quote.payout(), "");
        });
    }

    public CompletableFuture<Optional<Integer>> committedPayout(String operationId) {
        Objects.requireNonNull(operationId, "operationId");
        return database.queryOne(
                "SELECT total_price FROM trade_history WHERE operation_id=?",
                result -> Math.toIntExact(Math.round(result.getDouble("total_price"))),
                operationId);
    }

    private PreparedSale buildQuote(
            SqlSession session,
            SignedItemData data,
            String payloadHash,
            UUID playerUuid,
            UUID herbalistUuid,
            Instant now,
            boolean forUpdate
    ) throws SQLException {
        ItemRegistryRecord registered = itemRegistry.find(session, data.uniqueInstanceId(), forUpdate)
                .orElseThrow(() -> failure(TradeFailureReason.UNREGISTERED_ITEM));
        if (registered.state() == ItemRegistryState.CONSUMED) {
            throw failure(TradeFailureReason.ITEM_ALREADY_CONSUMED);
        }
        if (registered.state() == ItemRegistryState.RESERVED) {
            throw failure(TradeFailureReason.ITEM_RESERVED);
        }
        if (!constantEquals(registered.signature(), payloadHash)
                || !registered.itemType().equals(data.itemType().name())
                || !Objects.equals(registered.batchId(), data.batchId())) {
            throw failure(TradeFailureReason.SIGNATURE_REGISTRY_MISMATCH);
        }
        if (!Objects.equals(registered.ownerUuid(), playerUuid)
                || !Objects.equals(data.ownerUuid(), playerUuid)) {
            throw failure(TradeFailureReason.WRONG_OWNER);
        }
        if (data.batchId() == null) {
            throw failure(TradeFailureReason.MISSING_BATCH);
        }
        ProductBatch batch;
        try {
            batch = batchService.require(
                    session, data.batchId(), playerUuid,
                    java.util.EnumSet.allOf(BatchState.class), forUpdate).batch();
        } catch (com.verdantvice.storage.BatchAccessException access) {
            throw switch (access.reason()) {
                case MISSING_BATCH -> failure(TradeFailureReason.MISSING_BATCH);
                case WRONG_OWNER -> failure(TradeFailureReason.WRONG_OWNER);
                case INVALID_BATCH_SIGNATURE -> failure(TradeFailureReason.INVALID_SIGNATURE);
                case WRONG_BATCH_STATE -> failure(TradeFailureReason.BATCH_STATE_MISMATCH);
            };
        }
        TradeFailureReason rejection = saleItems.rejectionReason(
                data.itemType(), batch.state(), acceptedItemTypes());
        if (rejection != null) throw failure(rejection);
        double quantity = saleItems.saleWeight(data.itemType(), batch);
        if (quantity <= 0.0) {
            throw failure(TradeFailureReason.BATCH_STATE_MISMATCH);
        }
        double reputation = session.queryOne(
                "SELECT reputation FROM players WHERE player_uuid=?",
                result -> result.getDouble("reputation"),
                playerUuid).orElse(0.0);
        Map<String, Long> versions = new HashMap<>();
        double weightedPrice = 0.0;
        for (Map.Entry<String, Double> component : batch.strainComposition().entrySet()) {
            PaperMarketRecord market = markets.find(session, component.getKey(), forUpdate)
                    .orElseThrow(() -> new SQLException("Missing market row for " + component.getKey()));
            Strain strain = strains.require(component.getKey());
            MarketQuote componentQuote = pricing.quote(
                    strain, market.state(), batch.qualityScore(), batch.freshness(), reputation);
            weightedPrice += componentQuote.unitPrice() * component.getValue();
            versions.put(component.getKey(), market.version());
        }
        double sellMultiplier = configs.get("economy.yml").getDouble("sell-price-multiplier", 0.85);
        if (data.itemType() == CustomItemType.FRESH_BATCH) {
            sellMultiplier *= Math.max(0.0, Math.min(1.0, configs.get("market.yml")
                    .getDouble("trade.fresh-price-multiplier", 0.35)));
        }
        int minimum = Math.max(1,
                configs.get("economy.yml").getInt("minimum-transaction-price", 1));
        int maximum = Math.max(minimum,
                configs.get("economy.yml").getInt("maximum-transaction-price", 4096));
        LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
        TradeLedger ledger = ledger(session, playerUuid, day);
        double repeatPenalty = Math.max(0.0,
                configs.get("market.yml").getDouble("repeat-sale-penalty", 0.025));
        double minimumRepeat = Math.max(0.0, Math.min(1.0,
                configs.get("market.yml").getDouble("minimum-repeat-multiplier", 0.35)));
        double repeatMultiplier = Math.max(
                minimumRepeat, 1.0 - ledger.soldBatches() * repeatPenalty);
        int payout = (int) Math.max(minimum, Math.min(maximum,
                Math.floor(weightedPrice * quantity * sellMultiplier * repeatMultiplier)));
        int dailyLimit = Math.max(1,
                configs.get("villagers.yml").getInt("herbalist.daily-buy-limit", 64));
        TradeLimits configuredLimits = new TradeLimits(
                dailyLimit, dailyLimit, (double) dailyLimit * maximum);
        TradeDecision decision = limits.evaluate(
                batch, quantity, quantity, payout, true, true, ledger, configuredLimits, day);
        if (!decision.accepted()) throw new IllegalArgumentException(decision.reasonCode());
        return new PreparedSale(
                UUID.randomUUID(), playerUuid, herbalistUuid, data,
                registered.instanceId(), payloadHash, batch, quantity, weightedPrice, payout,
                versions, now, now.plus(Duration.ofSeconds(30)));
    }

    private TradeLedger ledger(SqlSession session, UUID playerUuid, LocalDate day) throws SQLException {
        Instant start = day.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant end = day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        LedgerTotals totals = session.queryOne("""
                        SELECT COUNT(*) AS batches,
                               COALESCE(SUM(quantity), 0) AS weight,
                               COALESCE(SUM(total_price), 0) AS payout
                        FROM trade_history
                        WHERE player_uuid=? AND trade_direction='SELL'
                          AND created_at>=? AND created_at<?
                        """,
                result -> new LedgerTotals(
                        result.getInt("batches"),
                        result.getDouble("weight"),
                        result.getDouble("payout")),
                playerUuid, start, end).orElse(new LedgerTotals(0, 0.0, 0.0));
        Set<UUID> ids = new HashSet<>(session.query("""
                        SELECT batch_id FROM trade_history
                        WHERE player_uuid=? AND trade_direction='SELL'
                          AND batch_id IS NOT NULL AND created_at>=? AND created_at<?
                        """,
                result -> UUID.fromString(result.getString("batch_id")),
                playerUuid, start, end));
        return new TradeLedger(playerUuid, day, ids.size(), totals.weight(), totals.payout(), ids);
    }

    private void updateMarketAfterSale(
            SqlSession session, ProductBatch batch, double quantity, Instant now
    ) throws SQLException {
        for (Map.Entry<String, Double> component : batch.strainComposition().entrySet()) {
            PaperMarketRecord current = markets.find(session, component.getKey(), true)
                    .orElseThrow(() -> new SQLException("Missing market row for " + component.getKey()));
            double weight = quantity * component.getValue();
            MarketState decayed = pricing.recordSale(current.state(), weight, now);
            double configuredSupply = Math.max(0.0,
                    configs.get("market.yml").getDouble("supply-impact", 0.015));
            double configuredDemand = Math.max(0.0,
                    configs.get("market.yml").getDouble("demand-impact", 0.020));
            double defaultSupply = 0.01;
            double defaultDemand = 0.002;
            MarketState changed = new MarketState(
                    decayed.strainId(),
                    Math.max(0.05, decayed.supplyIndex()
                            + weight * (configuredSupply - defaultSupply)),
                    Math.max(0.05, decayed.demandIndex()
                            - weight * (configuredDemand - defaultDemand)),
                    decayed.serverSalesWeight(),
                    decayed.event(),
                    decayed.eventMultiplier(),
                    decayed.minimumPrice(),
                    decayed.maximumPrice(),
                    now);
            Strain strain = strains.require(component.getKey());
            double currentPrice = pricing.quote(strain, changed, 65.0, 100.0, 0.0).unitPrice();
            PaperMarketRecord replacement = new PaperMarketRecord(
                    current.strainId(), current.basePrice(), currentPrice, changed,
                    current.event(), current.version() + 1);
            if (!markets.save(session, current, replacement)) {
                throw new SQLException("Concurrent market update for " + current.strainId());
            }
        }
    }

    private static void validateIdentity(SignedItemData data, String payloadHash, UUID playerUuid) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(payloadHash, "payloadHash");
        Objects.requireNonNull(playerUuid, "playerUuid");
        if (data.batchId() == null) throw failure(TradeFailureReason.MISSING_BATCH);
        if (!Objects.equals(data.ownerUuid(), playerUuid)) {
            throw failure(TradeFailureReason.WRONG_OWNER);
        }
    }

    private Set<CustomItemType> acceptedItemTypes() {
        List<String> configured = configs.get("market.yml")
                .getStringList("trade.accepted-item-types");
        if (configured.isEmpty()) {
            return Set.of(CustomItemType.DRY_BATCH, CustomItemType.CURED_BATCH,
                    CustomItemType.PACKAGE);
        }
        return saleItems.acceptedTypes(configured);
    }

    private static TradeValidationException failure(TradeFailureReason reason) {
        return new TradeValidationException(reason);
    }

    private static void lockPlayer(SqlSession session, UUID playerUuid) throws SQLException {
        String suffix = session.databaseType().isNetworkDatabase() ? " FOR UPDATE" : "";
        if (!session.exists("SELECT player_uuid FROM players WHERE player_uuid=?" + suffix, playerUuid)) {
            throw new SQLException("Player row is missing for " + playerUuid);
        }
    }

    private static String dominantStrain(ProductBatch batch) {
        return batch.strainComposition().entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElseThrow()
                .getKey();
    }

    private static boolean constantEquals(String first, String second) {
        return first != null && second != null && MessageDigest.isEqual(
                first.getBytes(StandardCharsets.US_ASCII),
                second.getBytes(StandardCharsets.US_ASCII));
    }

    private record LedgerTotals(int batches, double weight, double payout) {
    }
}
