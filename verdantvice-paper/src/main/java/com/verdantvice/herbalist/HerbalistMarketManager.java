package com.verdantvice.herbalist;

import com.verdantvice.api.event.VerdantContractCompleteEvent;
import com.verdantvice.api.event.VerdantHerbalistCreatedEvent;
import com.verdantvice.api.event.VerdantMarketPriceChangeEvent;
import com.verdantvice.config.ConfigManager;
import com.verdantvice.contract.ContractClaimResult;
import com.verdantvice.contract.ContractDeliveryResult;
import com.verdantvice.contract.PaperContractRepository;
import com.verdantvice.contract.PaperContractService;
import com.verdantvice.contract.StoredContract;
import com.verdantvice.database.AmbiguousCommitException;
import com.verdantvice.database.DatabaseManager;
import com.verdantvice.database.item.ItemRegistryRepository;
import com.verdantvice.i18n.TranslationService;
import com.verdantvice.market.MarketRepository;
import com.verdantvice.market.MarketUpdateProposal;
import com.verdantvice.market.PaperMarketService;
import com.verdantvice.security.ItemSigner;
import com.verdantvice.security.SecureItemService;
import com.verdantvice.strain.Strain;
import com.verdantvice.strain.StrainRegistry;
import com.verdantvice.storage.BatchService;
import com.verdantvice.trade.EconomyService;
import com.verdantvice.trade.PaperTradeService;
import com.verdantvice.trade.PreparedSale;
import com.verdantvice.trade.SaleCommitResult;
import com.verdantvice.trade.TradeRepository;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Public Paper facade for the herbalist, market, trade and contract features.
 *
 * <p>The plugin bootstrap needs only to construct this class, call
 * {@link #start()} after the database has started and call {@link #close()}
 * during shutdown. Bukkit state is touched only on the primary thread; durable
 * mutations remain in repository transactions.</p>
 */
public final class HerbalistMarketManager implements AutoCloseable {
    private final JavaPlugin plugin;
    private final ConfigManager configs;
    private final TranslationService translations;
    private final StrainRegistry strains;
    private final EconomyService economy;
    private final ItemSigner signer;

    private final HerbalistRepository herbalists;
    private final MarketRepository marketRepository;
    private final PaperMarketService markets;
    private final TradeRepository tradeRepository;
    private final PaperTradeService trades;
    private final PaperContractRepository contractRepository;
    private final PaperContractService contracts;
    private final HerbalistConversionService conversion;
    private final HerbalistOfferCatalog offers;
    private final HerbalistPurchaseService purchases;
    private final HerbalistMenuService menus;

    private final HerbalistListener herbalistListener;
    private final HerbalistMenuListener menuListener;
    private final Map<UUID, PreparedSale> saleQuotes = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> mutatingPlayers = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile CompletableFuture<Void> ready = new CompletableFuture<>();
    private volatile BukkitTask marketTask;

    public HerbalistMarketManager(
            JavaPlugin plugin,
            ConfigManager configs,
            TranslationService translations,
            DatabaseManager database,
            StrainRegistry strains,
            EconomyService economy,
            SecureItemService secureItems,
            ItemSigner signer,
            ItemRegistryRepository itemRegistry,
            BatchService batchService,
            TutorialBookService books
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.translations = Objects.requireNonNull(translations, "translations");
        this.strains = Objects.requireNonNull(strains, "strains");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.signer = Objects.requireNonNull(signer, "signer");
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(secureItems, "secureItems");
        Objects.requireNonNull(itemRegistry, "itemRegistry");
        Objects.requireNonNull(batchService, "batchService");
        Objects.requireNonNull(books, "books");

        herbalists = new HerbalistRepository(database);
        marketRepository = new MarketRepository(database, configs);
        markets = new PaperMarketService(marketRepository, strains, configs);
        tradeRepository = new TradeRepository(
                database, itemRegistry, marketRepository, strains, configs, batchService);
        trades = new PaperTradeService(signer, tradeRepository);
        contractRepository = new PaperContractRepository(database, itemRegistry, batchService);
        contracts = new PaperContractService(
                contractRepository, markets, strains, configs, signer);
        conversion = new HerbalistConversionService(
                plugin, configs, translations, herbalists);
        offers = new HerbalistOfferCatalog(strains, configs);
        purchases = new HerbalistPurchaseService(
                plugin, configs, economy, secureItems, books, itemRegistry);
        menus = new HerbalistMenuService(plugin, translations, strains, markets);
        herbalistListener = new HerbalistListener(plugin, this);
        menuListener = new HerbalistMenuListener(this);
    }

    /**
     * Registers listeners immediately and asynchronously initializes market
     * rows. The returned future is the readiness signal for the feature.
     */
    public CompletableFuture<Void> start() {
        requireMainThread();
        if (!started.compareAndSet(false, true)) {
            return ready;
        }
        plugin.getServer().getPluginManager().registerEvents(herbalistListener, plugin);
        plugin.getServer().getPluginManager().registerEvents(menuListener, plugin);
        conversion.refreshLoadedHerbalists();
        long minutes = Math.max(1L,
                configs.get("market.yml").getLong("update-minutes", 30L));
        long ticks = Math.multiplyExact(minutes, 60L * 20L);
        marketTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::refreshMarket, ticks, ticks);
        ready = markets.initialize();
        ready.whenComplete((ignored, failure) -> {
            if (failure != null) {
                plugin.getLogger().log(
                        Level.SEVERE, "Herbalist market initialization failed", unwrap(failure));
            }
        });
        return ready;
    }

    public boolean isReady() {
        return started.get() && ready.isDone() && !ready.isCompletedExceptionally();
    }

    public boolean isHerbalist(Villager villager) {
        return conversion.isMarkedHerbalist(villager);
    }

    public boolean isEvaluated(Villager villager) {
        return conversion.isEvaluated(villager);
    }

    public PaperMarketService marketService() {
        return markets;
    }

    public PaperContractService contractService() {
        return contracts;
    }

    public PaperTradeService tradeService() {
        return trades;
    }

    public HerbalistRepository herbalistRepository() {
        return herbalists;
    }

    public HerbalistConversionService conversionService() {
        return conversion;
    }

    public void restoreOrRefreshHerbalist(Villager villager) {
        requireMainThread();
        conversion.restoreOrRefresh(villager).exceptionally(failure -> {
            plugin.getLogger().log(
                    Level.WARNING,
                    "Could not restore herbalist " + villager.getUniqueId(),
                    unwrap(failure));
            return Optional.empty();
        });
    }

    public void refreshHerbalistName(Villager villager) {
        requireMainThread();
        conversion.refreshName(villager);
    }

    public void refreshNames() {
        requireMainThread();
        conversion.refreshLoadedHerbalists();
    }

    public void handleInteraction(Player player, Villager villager) {
        requireMainThread();
        if (conversion.isMarkedHerbalist(villager)) conversion.refreshName(villager);
        conversion.evaluate(villager).whenComplete((evaluation, failure) ->
                runMain(() -> {
                    if (!player.isOnline()) return;
                    if (failure != null) {
                        reportFailure(player, failure, FailureContext.GENERAL);
                        return;
                    }
                    switch (evaluation.status()) {
                        case CREATED -> {
                            plugin.getServer().getPluginManager().callEvent(
                                    new VerdantHerbalistCreatedEvent(villager));
                            player.sendMessage(translations.component(player, "herbalist.created"));
                            evaluation.herbalist().ifPresent(record -> openRootWhenReady(player, record));
                        }
                        case EXISTING ->
                                evaluation.herbalist().ifPresent(record ->
                                        openRootWhenReady(player, record));
                        case NORMAL -> player.sendMessage(
                                translations.component(player, "herbalist.evaluated-normal"));
                        case VILLAGE_LIMIT -> player.sendMessage(
                                translations.component(player, "herbalist.village-limit"));
                        case NOT_FARMER, INVALID_ENTITY -> player.sendMessage(
                                translations.component(player, "errors.target-not-found"));
                    }
                }));
    }

    public void evaluateSilently(Villager villager) {
        requireMainThread();
        if (conversion.isEvaluated(villager)) return;
        conversion.evaluate(villager).whenComplete((evaluation, failure) ->
                runMain(() -> {
                    if (failure != null) {
                        plugin.getLogger().log(
                                Level.WARNING, "Herbalist conversion failed", unwrap(failure));
                    } else if (evaluation.status() == HerbalistEvaluation.Status.CREATED
                            && villager.isValid()) {
                        plugin.getServer().getPluginManager().callEvent(
                                new VerdantHerbalistCreatedEvent(villager));
                    }
                }));
    }

    public CompletableFuture<HerbalistEvaluation> forceCreate(Villager villager) {
        requireMainThread();
        return conversion.force(villager).thenApply(evaluation -> {
            runMain(() -> {
                if (evaluation.status() == HerbalistEvaluation.Status.CREATED
                        && villager.isValid()) {
                    plugin.getServer().getPluginManager().callEvent(
                            new VerdantHerbalistCreatedEvent(villager));
                }
            });
            return evaluation;
        });
    }

    public void handleMenuAction(
            Player player,
            HerbalistMenuHolder holder,
            String action
    ) {
        requireMainThread();
        if (!holder.playerUuid().equals(player.getUniqueId())) {
            player.closeInventory();
            return;
        }
        if (!isReady()) {
            player.sendMessage(translations.component(player, "errors.not-ready"));
            return;
        }
        if ("open:root".equals(action)) {
            openRoot(player, holder.herbalistUuid());
        } else if ("open:buy".equals(action)) {
            openBuy(player, holder.herbalistUuid());
        } else if ("open:market".equals(action)) {
            openMarket(player, holder.herbalistUuid());
        } else if ("open:contracts".equals(action)) {
            openContracts(player, holder.herbalistUuid());
        } else if ("sale:prepare".equals(action)) {
            prepareSale(player, holder.herbalistUuid());
        } else if (action.startsWith("sale:confirm:")) {
            parseUuid(action.substring("sale:confirm:".length()))
                    .ifPresentOrElse(
                            quoteId -> confirmSale(player, holder.herbalistUuid(), quoteId),
                            () -> player.sendMessage(
                                    translations.component(player, "errors.database-conflict")));
        } else if (action.startsWith("purchase:")) {
            purchase(player, holder.herbalistUuid(),
                    action.substring("purchase:".length()));
        } else if ("contract:new".equals(action)) {
            createContract(player, holder.herbalistUuid());
        } else if (action.startsWith("contract:deliver:")) {
            parseUuid(action.substring("contract:deliver:".length()))
                    .ifPresentOrElse(
                            contractId -> deliverContract(
                                    player, holder.herbalistUuid(), contractId),
                            () -> player.sendMessage(
                                    translations.component(player, "errors.database-conflict")));
        } else if (action.startsWith("contract:claim:")) {
            parseUuid(action.substring("contract:claim:".length()))
                    .ifPresentOrElse(
                            contractId -> claimContract(
                                    player, holder.herbalistUuid(), contractId),
                            () -> player.sendMessage(
                                    translations.component(player, "errors.database-conflict")));
        }
    }

    public CompletableFuture<Void> refreshMarket() {
        if (!isReady()) return CompletableFuture.completedFuture(null);
        return markets.proposeRefresh(Instant.now()).thenCompose(proposals -> {
            List<CompletableFuture<Void>> updates = new ArrayList<>(proposals.size());
            for (MarketUpdateProposal proposal : proposals) {
                CompletableFuture<Void> update = onMain(() -> {
                    VerdantMarketPriceChangeEvent event = new VerdantMarketPriceChangeEvent(
                            proposal.previous().strainId(),
                            proposal.previous().currentPrice(),
                            proposal.replacement().currentPrice());
                    plugin.getServer().getPluginManager().callEvent(event);
                    return event.isCancelled();
                }).thenCompose(cancelled -> {
                    if (cancelled) return CompletableFuture.completedFuture(null);
                    return markets.apply(proposal).thenCompose(applied ->
                            applied ? onMain(() -> {
                                broadcastMarketChange(proposal);
                                return null;
                            }) : CompletableFuture.completedFuture(null));
                });
                updates.add(update);
            }
            return CompletableFuture.allOf(updates.toArray(CompletableFuture[]::new));
        }).exceptionally(failure -> {
            plugin.getLogger().log(Level.WARNING, "Market refresh failed", unwrap(failure));
            return null;
        });
    }

    @Override
    public void close() {
        if (!started.compareAndSet(true, false)) return;
        BukkitTask task = marketTask;
        marketTask = null;
        if (task != null) task.cancel();
        HandlerList.unregisterAll(herbalistListener);
        HandlerList.unregisterAll(menuListener);
        saleQuotes.clear();
        mutatingPlayers.clear();
    }

    private void openRootWhenReady(Player player, HerbalistRecord record) {
        if (!isReady()) {
            player.sendMessage(translations.component(player, "errors.not-ready"));
            return;
        }
        menus.openRoot(player, record);
    }

    private void openRoot(Player player, UUID herbalistUuid) {
        loadHerbalist(player, herbalistUuid).thenAccept(optional ->
                runMain(() -> optional.ifPresent(record -> menus.openRoot(player, record))));
    }

    private void openBuy(Player player, UUID herbalistUuid) {
        loadHerbalist(player, herbalistUuid).thenAccept(optional ->
                runMain(() -> optional.ifPresent(record ->
                        menus.openBuy(player, record, offers.available(record.tier())))));
    }

    private void openMarket(Player player, UUID herbalistUuid) {
        herbalists.find(herbalistUuid)
                .thenCombine(markets.snapshot(), MenuData::new)
                .whenComplete((data, failure) -> runMain(() -> {
                    if (!player.isOnline()) return;
                    if (failure != null) {
                        reportFailure(player, failure, FailureContext.GENERAL);
                    } else if (data.herbalist().isPresent()) {
                        menus.openMarket(player, data.herbalist().orElseThrow(), data.records());
                    } else {
                        player.sendMessage(
                                translations.component(player, "errors.target-not-found"));
                    }
                }));
    }

    private void openContracts(Player player, UUID herbalistUuid) {
        herbalists.find(herbalistUuid)
                .thenCombine(contracts.list(player.getUniqueId()), ContractMenuData::new)
                .whenComplete((data, failure) -> runMain(() -> {
                    if (!player.isOnline()) return;
                    if (failure != null) {
                        reportFailure(player, failure, FailureContext.CONTRACT);
                    } else if (data.herbalist().isPresent()) {
                        menus.openContracts(
                                player, data.herbalist().orElseThrow(), data.contracts());
                    } else {
                        player.sendMessage(
                                translations.component(player, "errors.target-not-found"));
                    }
                }));
    }

    private CompletableFuture<Optional<HerbalistRecord>> loadHerbalist(
            Player player,
            UUID herbalistUuid
    ) {
        CompletableFuture<Optional<HerbalistRecord>> result = herbalists.find(herbalistUuid);
        result.whenComplete((record, failure) -> runMain(() -> {
            if (!player.isOnline()) return;
            if (failure != null) {
                reportFailure(player, failure, FailureContext.GENERAL);
            } else if (record.isEmpty()) {
                player.sendMessage(translations.component(player, "errors.target-not-found"));
            }
        }));
        return result.exceptionally(ignored -> Optional.empty());
    }

    private void purchase(Player player, UUID herbalistUuid, String offerId) {
        herbalists.find(herbalistUuid).whenComplete((optional, failure) -> runMain(() -> {
            if (!player.isOnline()) return;
            if (failure != null) {
                reportFailure(player, failure, FailureContext.PURCHASE);
                return;
            }
            HerbalistRecord record = optional.orElse(null);
            if (record == null) {
                player.sendMessage(translations.component(player, "errors.target-not-found"));
                return;
            }
            Optional<HerbalistOffer> selected = offers.available(record.tier()).stream()
                    .filter(offer -> offer.id().equals(offerId))
                    .findFirst();
            if (selected.isEmpty()) {
                player.sendMessage(translations.component(player, "errors.database-conflict"));
                return;
            }
            HerbalistOffer offer = selected.orElseThrow();
            purchases.purchase(player, offer).whenComplete((result, purchaseFailure) ->
                    runMain(() -> {
                        if (!player.isOnline()) return;
                        if (purchaseFailure != null) {
                            reportFailure(player, purchaseFailure, FailureContext.PURCHASE);
                            return;
                        }
                        switch (result.status()) {
                            case PURCHASED -> {
                                advanceHerbalist(herbalistUuid, 1L, 1);
                                player.sendMessage(translations.component(
                                        player, "trade.purchase-complete",
                                        Map.of("item", offerName(player, offer),
                                                "price", offer.emeraldPrice())));
                            }
                            case INSUFFICIENT_FUNDS -> player.sendMessage(
                                    translations.component(player, "errors.insufficient-items"));
                            case BUSY -> player.sendMessage(
                                    translations.component(player, "errors.busy"));
                            case FAILED -> player.sendMessage(
                                    translations.component(player, "errors.database-unavailable"));
                        }
                    }));
        }));
    }

    private void prepareSale(Player player, UUID herbalistUuid) {
        ItemStack held = player.getInventory().getItemInMainHand().clone();
        trades.prepare(held, player.getUniqueId(), herbalistUuid)
                .whenComplete((sale, failure) -> runMain(() -> {
                    if (!player.isOnline()) return;
                    if (failure != null) {
                        reportFailure(player, failure, FailureContext.SALE);
                        return;
                    }
                    removeExpiredQuotes();
                    saleQuotes.put(sale.quoteId(), sale);
                    herbalists.find(herbalistUuid).whenComplete((record, recordFailure) ->
                            runMain(() -> {
                                if (recordFailure != null) {
                                    reportFailure(player, recordFailure, FailureContext.SALE);
                                } else {
                                    record.ifPresent(value ->
                                            menus.openSaleConfirm(player, value, sale));
                                }
                            }));
                }));
    }

    private void confirmSale(Player player, UUID herbalistUuid, UUID quoteId) {
        PreparedSale sale = saleQuotes.remove(quoteId);
        if (sale == null
                || !sale.playerUuid().equals(player.getUniqueId())
                || !sale.herbalistUuid().equals(herbalistUuid)
                || !Instant.now().isBefore(sale.expiresAt())) {
            player.sendMessage(translations.component(player, "errors.database-conflict"));
            return;
        }
        if (!beginMutation(player)) return;
        Optional<EscrowedItem> escrow = takeSignedItem(player, sale.itemInstanceId());
        if (escrow.isEmpty()) {
            endMutation(player);
            player.sendMessage(translations.component(player, "trade.already-used"));
            return;
        }
        player.closeInventory();
        String operationId = "sale:" + sale.quoteId();
        trades.commit(sale, operationId).whenComplete((result, failure) ->
                runMain(() -> {
                    if (failure != null) {
                        if (isAmbiguous(failure)) {
                            recoverSale(
                                    player, sale, escrow.orElseThrow(),
                                    operationId, failure);
                            return;
                        }
                        endMutation(player);
                        restore(player, escrow.orElseThrow());
                        reportFailure(player, failure, FailureContext.SALE);
                        return;
                    }
                    endMutation(player);
                    if (result.committed()) {
                        completeSale(player, sale, result.payout());
                    } else {
                        restore(player, escrow.orElseThrow());
                        if (result.status() == SaleCommitResult.Status.PRICE_CHANGED) {
                            player.sendMessage(translations.component(
                                    player, "trade.price-changed",
                                    Map.of("old_price", sale.payout(),
                                            "new_price", result.payout())));
                        } else {
                            sendReason(player, result.reasonCode(), FailureContext.SALE);
                        }
                    }
                }));
    }

    private void createContract(Player player, UUID herbalistUuid) {
        if (!beginMutation(player)) return;
        herbalists.find(herbalistUuid).thenCompose(optional -> {
            HerbalistRecord record = optional.orElseThrow(() ->
                    new IllegalArgumentException("unknown-herbalist"));
            return contracts.generate(player.getUniqueId(), record, Instant.now());
        }).whenComplete((stored, failure) -> runMain(() -> {
            endMutation(player);
            if (!player.isOnline()) return;
            if (failure != null) {
                reportFailure(player, failure, FailureContext.CONTRACT);
                return;
            }
            player.sendMessage(translations.component(
                    player, "contracts.accepted",
                    Map.of("contract_id", stored.contract().contractId(),
                            "deadline", stored.contract().deadline())));
            openContracts(player, herbalistUuid);
        }));
    }

    private void deliverContract(Player player, UUID herbalistUuid, UUID contractId) {
        ItemStack held = player.getInventory().getItemInMainHand().clone();
        ItemSigner.Verification verification = signer.verify(held);
        if (held.getAmount() != 1 || !verification.valid()) {
            player.sendMessage(translations.component(player, "trade.authentic-only"));
            return;
        }
        if (!beginMutation(player)) return;
        Optional<EscrowedItem> escrow = takeSignedItem(
                player, verification.data().uniqueInstanceId());
        if (escrow.isEmpty()) {
            endMutation(player);
            player.sendMessage(translations.component(player, "trade.authentic-only"));
            return;
        }
        String operationId = "contract:" + contractId + ":"
                + verification.data().uniqueInstanceId();
        contracts.deliver(
                contractId, escrow.orElseThrow().item(), player.getUniqueId(), operationId
        ).whenComplete((result, failure) -> runMain(() -> {
            if (failure != null) {
                if (isAmbiguous(failure)) {
                    recoverDelivery(
                            player, herbalistUuid, contractId,
                            verification.data().uniqueInstanceId(),
                            operationId, escrow.orElseThrow(), failure);
                    return;
                }
                endMutation(player);
                restore(player, escrow.orElseThrow());
                reportFailure(player, failure, FailureContext.CONTRACT);
                return;
            }
            endMutation(player);
            if (result.status() == ContractDeliveryResult.Status.REJECTED) {
                restore(player, escrow.orElseThrow());
                sendReason(player, result.reasonCode(), FailureContext.CONTRACT);
                return;
            }
            if (result.newlyCompleted()) {
                advanceHerbalist(herbalistUuid, 5L, 5);
                plugin.getServer().getPluginManager().callEvent(
                        new VerdantContractCompleteEvent(
                                result.contract().contractId(), player.getUniqueId()));
                player.sendMessage(translations.component(
                        player, "contracts.completed",
                        Map.of("contract_id", result.contract().contractId())));
            } else {
                double current = result.contract().deliveredWeight().values().stream()
                        .mapToDouble(Double::doubleValue).sum();
                double required = result.contract().requirements().stream()
                        .mapToDouble(requirement -> requirement.requiredWeight()).sum();
                player.sendMessage(translations.component(
                        player, "contracts.progress",
                        Map.of("current", format(current), "required", format(required))));
            }
            openContracts(player, herbalistUuid);
        }));
    }

    private void claimContract(Player player, UUID herbalistUuid, UUID contractId) {
        if (!beginMutation(player)) return;
        String operationId = "contract-reward:" + contractId;
        contracts.claim(contractId, player.getUniqueId(), operationId)
                .whenComplete((result, failure) -> runMain(() -> {
                    if (failure != null) {
                        if (isAmbiguous(failure)) {
                            recoverClaim(
                                    player, herbalistUuid, contractId,
                                    operationId, failure);
                            return;
                        }
                        endMutation(player);
                        reportFailure(player, failure, FailureContext.CONTRACT);
                        return;
                    }
                    endMutation(player);
                    if (result.status() == ContractClaimResult.Status.CLAIMED) {
                        pay(player, result.reward().emeralds());
                        player.sendMessage(translations.component(
                                player, "contracts.claimed",
                                Map.of("reward", result.reward().emeralds(),
                                        "contract_id", contractId)));
                    } else if (result.status()
                            == ContractClaimResult.Status.ALREADY_CLAIMED) {
                        player.sendMessage(
                                translations.component(player, "contracts.already-claimed"));
                    } else {
                        sendReason(player, result.reasonCode(), FailureContext.CONTRACT);
                    }
                    openContracts(player, herbalistUuid);
                }));
    }

    private void recoverSale(
            Player player,
            PreparedSale sale,
            EscrowedItem escrow,
            String operationId,
            Throwable originalFailure
    ) {
        trades.committedPayout(operationId).whenComplete((payout, recoveryFailure) ->
                runMain(() -> {
                    endMutation(player);
                    if (recoveryFailure != null) {
                        plugin.getLogger().log(
                                Level.SEVERE,
                                "Unable to resolve ambiguous sale " + operationId,
                                unwrap(recoveryFailure));
                        reportFailure(player, originalFailure, FailureContext.SALE);
                    } else if (payout.isPresent()) {
                        completeSale(player, sale, payout.orElseThrow());
                    } else {
                        restore(player, escrow);
                        player.sendMessage(
                                translations.component(player, "errors.database-conflict"));
                    }
                }));
    }

    private void recoverDelivery(
            Player player,
            UUID herbalistUuid,
            UUID contractId,
            UUID instanceId,
            String operationId,
            EscrowedItem escrow,
            Throwable originalFailure
    ) {
        contractRepository.deliveryApplied(instanceId, operationId)
                .thenCombine(contractRepository.find(contractId), DeliveryRecovery::new)
                .whenComplete((recovery, recoveryFailure) -> runMain(() -> {
                    endMutation(player);
                    if (recoveryFailure != null) {
                        plugin.getLogger().log(
                                Level.SEVERE,
                                "Unable to resolve ambiguous contract delivery " + operationId,
                                unwrap(recoveryFailure));
                        reportFailure(player, originalFailure, FailureContext.CONTRACT);
                    } else if (!recovery.applied()) {
                        restore(player, escrow);
                        player.sendMessage(
                                translations.component(player, "errors.database-conflict"));
                    } else if (recovery.contract().isPresent()) {
                        StoredContract stored = recovery.contract().orElseThrow();
                        boolean completed = stored.contract().status()
                                == com.verdantvice.contract.ContractStatus.COMPLETED;
                        notifyContractProgress(
                                player, herbalistUuid, stored, completed);
                    }
                }));
    }

    private void recoverClaim(
            Player player,
            UUID herbalistUuid,
            UUID contractId,
            String operationId,
            Throwable originalFailure
    ) {
        contractRepository.find(contractId).whenComplete((stored, recoveryFailure) ->
                runMain(() -> {
                    endMutation(player);
                    if (recoveryFailure != null) {
                        plugin.getLogger().log(
                                Level.SEVERE,
                                "Unable to resolve ambiguous contract reward " + operationId,
                                unwrap(recoveryFailure));
                        reportFailure(player, originalFailure, FailureContext.CONTRACT);
                        return;
                    }
                    if (stored.isPresent()
                            && stored.orElseThrow().rewardClaimed()
                            && operationId.equals(
                                    stored.orElseThrow().rewardOperationId())) {
                        int reward = stored.orElseThrow().contract().reward().emeralds();
                        pay(player, reward);
                        player.sendMessage(translations.component(
                                player, "contracts.claimed",
                                Map.of("reward", reward, "contract_id", contractId)));
                    } else {
                        player.sendMessage(
                                translations.component(player, "errors.database-conflict"));
                    }
                    openContracts(player, herbalistUuid);
                }));
    }

    private void completeSale(Player player, PreparedSale sale, int payout) {
        pay(player, payout);
        player.sendMessage(translations.component(
                player, "trade.sale-complete",
                Map.of("item", batchName(sale), "price", payout)));
    }

    private void notifyContractProgress(
            Player player,
            UUID herbalistUuid,
            StoredContract stored,
            boolean newlyCompleted
    ) {
        if (newlyCompleted) {
            advanceHerbalist(herbalistUuid, 5L, 5);
            plugin.getServer().getPluginManager().callEvent(
                    new VerdantContractCompleteEvent(
                            stored.contract().contractId(), player.getUniqueId()));
            player.sendMessage(translations.component(
                    player, "contracts.completed",
                    Map.of("contract_id", stored.contract().contractId())));
        } else {
            double current = stored.contract().deliveredWeight().values().stream()
                    .mapToDouble(Double::doubleValue).sum();
            double required = stored.contract().requirements().stream()
                    .mapToDouble(requirement -> requirement.requiredWeight()).sum();
            player.sendMessage(translations.component(
                    player, "contracts.progress",
                    Map.of("current", format(current), "required", format(required))));
        }
        openContracts(player, herbalistUuid);
    }

    private boolean beginMutation(Player player) {
        if (mutatingPlayers.add(player.getUniqueId())) return true;
        player.sendMessage(translations.component(player, "errors.busy"));
        return false;
    }

    private void endMutation(Player player) {
        mutatingPlayers.remove(player.getUniqueId());
    }

    private Optional<EscrowedItem> takeSignedItem(Player player, UUID instanceId) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack candidate = contents[slot];
            if (candidate == null || candidate.getType() == Material.AIR
                    || candidate.getAmount() != 1) {
                continue;
            }
            ItemSigner.Verification verification = signer.verify(candidate);
            if (verification.valid()
                    && verification.data().uniqueInstanceId().equals(instanceId)) {
                ItemStack removed = candidate.clone();
                contents[slot] = null;
                player.getInventory().setStorageContents(contents);
                return Optional.of(new EscrowedItem(removed));
            }
        }
        return Optional.empty();
    }

    private void restore(Player player, EscrowedItem escrow) {
        if (!player.isOnline()) {
            player.getWorld().dropItemNaturally(player.getLocation(), escrow.item());
            return;
        }
        player.getInventory().addItem(escrow.item()).values().forEach(stack ->
                player.getWorld().dropItemNaturally(player.getLocation(), stack));
    }

    private void pay(Player player, int amount) {
        if (amount <= 0) return;
        try {
            economy.deposit(player, amount);
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.SEVERE, "Economy payout failed; using emerald fallback", failure);
            player.getInventory().addItem(new ItemStack(Material.EMERALD, amount))
                    .values().forEach(stack ->
                            player.getWorld().dropItemNaturally(player.getLocation(), stack));
        }
    }

    private void broadcastMarketChange(MarketUpdateProposal proposal) {
        String strain = strains.find(proposal.previous().strainId())
                .map(Strain::displayName)
                .orElse(proposal.previous().strainId());
        String trendKey;
        double difference = proposal.replacement().currentPrice()
                - proposal.previous().currentPrice();
        if (difference > 0.01) trendKey = "gui.market.trend-up";
        else if (difference < -0.01) trendKey = "gui.market.trend-down";
        else trendKey = "gui.market.trend-stable";
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(translations.component(
                    player, "market.changed",
                    Map.of("strain", strain,
                            "price", format(proposal.replacement().currentPrice()),
                            "trend", translations.plain(player, trendKey))));
        }
    }

    private String offerName(Player player, HerbalistOffer offer) {
        PlainTextComponentSerializer serializer =
                PlainTextComponentSerializer.plainText();
        if (offer.kind() == HerbalistOfferKind.SEED) {
            String strain = strains.require(offer.strainId().orElseThrow()).displayName();
            return serializer.serialize(translations.component(
                    player, "item.seed.name", Map.of("strain", strain)));
        }
        if (offer.kind() == HerbalistOfferKind.BOOK) {
            return translations.plain(
                    player, "book." + offer.bookType().orElseThrow().slug() + ".title");
        }
        if (offer.kind() == HerbalistOfferKind.PLANTATION_CONTRACT) {
            return translations.plain(player, "item.plantation_contract.name");
        }
        return translations.plain(
                player, "item." + offer.id().replace('-', '_') + ".name");
    }

    private void advanceHerbalist(UUID herbalistUuid, long experience, int reputation) {
        herbalists.addProgress(
                herbalistUuid, experience, reputation,
                configs.get("villagers.yml"), Instant.now()
        ).exceptionally(failure -> {
            plugin.getLogger().log(
                    Level.WARNING, "Herbalist progress update failed", unwrap(failure));
            return null;
        });
    }

    private String batchName(PreparedSale sale) {
        String dominant = sale.batch().strainComposition().entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElseThrow()
                .getKey();
        return strains.find(dominant).map(Strain::displayName).orElse(dominant);
    }

    private void reportFailure(
            Player player,
            Throwable failure,
            FailureContext context
    ) {
        Throwable cause = unwrap(failure);
        if (cause instanceof com.verdantvice.trade.TradeValidationException validation) {
            player.sendMessage(translations.component(
                    player, validation.reason().translationKey()));
            return;
        }
        if (cause instanceof com.verdantvice.database.DatabaseUnavailableException
                || cause instanceof java.sql.SQLException) {
            plugin.getLogger().log(Level.WARNING, "Herbalist database action failed", cause);
            player.sendMessage(translations.component(player, "errors.database-unavailable"));
            return;
        }
        String key = reasonKey(cause.getMessage(), context);
        if (key != null) {
            Map<String, ?> placeholders = key.equals("contracts.expired")
                    ? Map.of("contract_id", "")
                    : Map.of();
            player.sendMessage(translations.component(player, key, placeholders));
            return;
        }
        String errorId = UUID.randomUUID().toString().substring(0, 8);
        plugin.getLogger().log(
                Level.WARNING, "Herbalist action failed [" + errorId + "]", cause);
        player.sendMessage(translations.component(
                player, "errors.internal", Map.of("error_id", errorId)));
    }

    private void sendReason(Player player, String reason, FailureContext context) {
        String key = reasonKey(reason, context);
        if (key == null) {
            String errorId = UUID.randomUUID().toString().substring(0, 8);
            player.sendMessage(translations.component(
                    player, "errors.internal", Map.of("error_id", errorId)));
            return;
        }
        Map<String, ?> placeholders = key.equals("contracts.expired")
                ? Map.of("contract_id", "")
                : Map.of();
        player.sendMessage(translations.component(player, key, placeholders));
    }

    static String reasonKey(String reason, FailureContext context) {
        String normalized = reason == null ? "" : reason.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("daily-limit")
                || normalized.contains("daily limit")
                || normalized.contains("batch-limit")
                || normalized.contains("weight-limit")
                || normalized.contains("payout-limit")) return "trade.daily-limit";
        if (normalized.contains("server-budget")) return "trade.server-budget";
        if (normalized.contains("fresh-batch") || normalized.contains("requires-drying")) {
            return "trade.fresh-batch-requires-drying";
        }
        if (normalized.contains("batch-state") || normalized.contains("unsupported-sale-state")) {
            return "trade.unsupported-batch-state";
        }
        if (normalized.contains("dry-only")
                || normalized.contains("not-tradeable")
                || normalized.contains("not-deliverable")) return "trade.dry-only";
        if (normalized.contains("wrong-owner")) return "errors.wrong-owner";
        if (normalized.contains("already-used") || normalized.contains("consumed")) {
            return "trade.already-used";
        }
        if (normalized.contains("reserved")) return "trade.item-reserved";
        if (normalized.contains("unregistered")) return "trade.unregistered-item";
        if (normalized.contains("missing-batch") || normalized.contains("unknown-batch")) {
            return "trade.missing-batch";
        }
        if (normalized.contains("authentic")
                || normalized.contains("signature")
                || normalized.contains("modified")
                || normalized.contains("invalid-or-used-item")) return "trade.authentic-only";
        if (normalized.contains("expired") && context == FailureContext.CONTRACT) {
            return "contracts.expired";
        }
        if ((normalized.contains("require")
                || normalized.contains("quality")
                || normalized.contains("strain"))
                && context == FailureContext.CONTRACT) return "contracts.item-not-required";
        if (normalized.contains("already-claimed")) return "contracts.already-claimed";
        if (normalized.contains("contract-limit") || normalized.contains("busy")) {
            return "errors.busy";
        }
        if (context == FailureContext.CONTRACT) return "contracts.item-not-required";
        if (context == FailureContext.PURCHASE) return "errors.insufficient-items";
        return null;
    }

    private void removeExpiredQuotes() {
        Instant now = Instant.now();
        saleQuotes.entrySet().removeIf(entry ->
                !now.isBefore(entry.getValue().expiresAt()));
    }

    private <T> CompletableFuture<T> onMain(Supplier<T> supplier) {
        CompletableFuture<T> result = new CompletableFuture<>();
        runMain(() -> {
            try {
                result.complete(supplier.get());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private void runMain(Runnable runnable) {
        if (plugin.getServer().isPrimaryThread()) {
            runnable.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, runnable);
        }
    }

    private void requireMainThread() {
        if (!plugin.getServer().isPrimaryThread()) {
            throw new IllegalStateException("Herbalist manager must be called on the server thread");
        }
    }

    private static Optional<UUID> parseUuid(String input) {
        try {
            return Optional.of(UUID.fromString(input));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static boolean isAmbiguous(Throwable failure) {
        return unwrap(failure) instanceof AmbiguousCommitException;
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private record MenuData(
            Optional<HerbalistRecord> herbalist,
            List<com.verdantvice.market.PaperMarketRecord> records
    ) {
    }

    private record ContractMenuData(
            Optional<HerbalistRecord> herbalist,
            List<StoredContract> contracts
    ) {
    }

    private record DeliveryRecovery(
            boolean applied,
            Optional<StoredContract> contract
    ) {
    }

    private record EscrowedItem(ItemStack item) {
        private EscrowedItem {
            item = item.clone();
        }

        @Override
        public ItemStack item() {
            return item.clone();
        }
    }

    private enum FailureContext {
        GENERAL,
        PURCHASE,
        SALE,
        CONTRACT
    }
}
