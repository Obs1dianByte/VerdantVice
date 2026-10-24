package com.verdantvice.processing;

import com.verdantvice.api.event.VerdantBatchCureEvent;
import com.verdantvice.api.event.VerdantBatchDryEvent;
import com.verdantvice.config.ConfigManager;
import com.verdantvice.common.BlockPosition;
import com.verdantvice.farm.Farm;
import com.verdantvice.database.DatabaseManager;
import com.verdantvice.database.DatabaseUnavailableException;
import com.verdantvice.i18n.TranslationService;
import com.verdantvice.notification.NotificationService;
import com.verdantvice.notification.NotificationType;
import com.verdantvice.security.CustomItemType;
import com.verdantvice.security.ItemSigner;
import com.verdantvice.security.SecureItemService;
import com.verdantvice.security.SignedItemData;
import com.verdantvice.storage.BatchService;
import com.verdantvice.storage.BatchAccessException;
import com.verdantvice.storage.BatchAccessFailure;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Persistent drying/curing jobs and authenticated transformations. Station
 * inventories are menus, never storage; inputs move into DB ownership before
 * the inventory item is removed.
 */
public final class ProcessingManager {
    private static final Set<CustomItemType> FRESH =
            Set.of(CustomItemType.FRESH_BATCH);
    private static final Set<CustomItemType> DRIED =
            Set.of(CustomItemType.DRY_BATCH);
    private static final Set<CustomItemType> GRINDABLE =
            Set.of(CustomItemType.DRY_BATCH, CustomItemType.CURED_BATCH);
    private static final Set<CustomItemType> GROUND =
            Set.of(CustomItemType.GROUND_PRODUCT);
    private static final Set<CustomItemType> ROLLING_PAPER =
            Set.of(CustomItemType.ROLLING_PAPER);
    private static final Set<CustomItemType> ROLLING_FILTER =
            Set.of(CustomItemType.FILTER);
    private static final Set<CustomItemType> ROLLING_PRODUCT =
            Set.of(CustomItemType.MIXTURE, CustomItemType.GROUND_PRODUCT);

    private final JavaPlugin plugin;
    private final ConfigManager configs;
    private final TranslationService translations;
    private final NotificationService notifications;
    private final DatabaseManager database;
    private final BatchService batchService;
    private final SecureItemService secureItems;
    private final ItemSigner signer;
    private final byte[] signingSecret;
    private final DryingService drying = new DryingService();
    private final CuringService curing = new CuringService();
    private final GrindingService grinding = new GrindingService();
    private final MixtureService mixing = new MixtureService();
    private final BatchAuthenticityService authenticity = new BatchAuthenticityService();
    private final Map<UUID, ProcessJob> jobs = new ConcurrentHashMap<>();
    private final Map<BlockKey, Station> stations = new ConcurrentHashMap<>();
    private final Set<UUID> pendingStations = ConcurrentHashMap.newKeySet();
    private final ProcessingStartGuard startGuard = new ProcessingStartGuard(pendingStations);
    private final DryingStartService dryingStarts;
    private final ProcessingTransferService transfers;
    private final ProcessingTransactionService transactions;
    private final com.verdantvice.storage.PerKeyAsyncQueue<UUID> outputQueue =
            new com.verdantvice.storage.PerKeyAsyncQueue<>();
    private final Set<UUID> pendingPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, RollingSession> rollingSessions = new ConcurrentHashMap<>();
    private final Set<UUID> liveRollingSessions = ConcurrentHashMap.newKeySet();
    private final RollingStartGuard rollingStarts = new RollingStartGuard();
    private final StationBreakGuard<BlockKey> stationBreaks = new StationBreakGuard<>();
    private final File stationFile;
    private BukkitTask updateTask;

    public ProcessingManager(
            JavaPlugin plugin,
            ConfigManager configs,
            TranslationService translations,
            NotificationService notifications,
            DatabaseManager database,
            BatchService batchService,
            SecureItemService secureItems,
            ItemSigner signer,
            byte[] signingSecret
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.translations = Objects.requireNonNull(translations, "translations");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        this.database = Objects.requireNonNull(database, "database");
        this.batchService = Objects.requireNonNull(batchService, "batchService");
        this.secureItems = Objects.requireNonNull(secureItems, "secureItems");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.signingSecret = Objects.requireNonNull(signingSecret, "signingSecret").clone();
        this.dryingStarts = new DryingStartService(
                database, batchService, secureItems, authenticity, this.signingSecret);
        this.transfers = new ProcessingTransferService(database, batchService, secureItems,
                new com.verdantvice.database.item.ItemRegistryRepository(database), signer);
        this.transactions = new ProcessingTransactionService(database, batchService, secureItems,
                new com.verdantvice.database.item.ItemRegistryRepository(database));
        this.stationFile = configs.dataPath("data/processing-stations.yml").toFile();
    }

    public CompletableFuture<Void> load() {
        loadStations();
        CompletableFuture<Void> batchFuture = batchService.loadAll();
        CompletableFuture<List<ProcessJob>> dryingFuture = database.query(
                """
                SELECT drying_job_id, batch_id, owner_uuid, rack_id, status
                FROM drying_jobs
                WHERE status IN ('ACTIVE', 'READY')
                """,
                result -> job(result, ProcessingStationType.DRYING_RACK)
        );
        CompletableFuture<List<ProcessJob>> curingFuture = database.query(
                """
                SELECT curing_job_id AS drying_job_id, batch_id, owner_uuid,
                       container_id AS rack_id, status
                FROM curing_jobs
                WHERE status IN ('ACTIVE', 'READY')
                """,
                result -> job(result, ProcessingStationType.CURING_CONTAINER)
        );
        return batchFuture
                .thenCombine(dryingFuture, (ignored, dryingJobs) -> dryingJobs)
                .thenCombine(curingFuture, LoadedJobs::new)
                .thenCompose(loaded -> onMain(() -> {
                    jobs.clear();
                    loaded.drying().forEach(job -> jobs.put(job.stationId(), job));
                    loaded.curing().forEach(job -> jobs.put(job.stationId(), job));
                }));
    }

    public void start() {
        requireMainThread();
        if (updateTask != null) {
            return;
        }
        long period = Math.max(20L, quality().getLong(
                "processing.update-seconds",
                30L
        ) * 20L);
        updateTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                this::updateProcesses,
                period,
                period
        );
    }

    public void stop() {
        requireMainThread();
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        List<RollingSession> sessions = List.copyOf(rollingSessions.values());
        sessions.forEach(session -> abortRolling(session.playerId(), true));
        pendingStations.clear();
        startGuard.clear();
        rollingStarts.clear();
        stationBreaks.clear();
        saveStations();
    }

    public Station markStation(Block block, ProcessingStationType type) {
        requireMainThread();
        if (block.getType() != type.material()) {
            throw new IllegalArgumentException("Wrong material for processing station");
        }
        BlockKey key = BlockKey.of(block.getLocation());
        Station existing = stations.get(key);
        if (existing != null) {
            return existing;
        }
        Station station = new Station(UUID.randomUUID(), type, key);
        stations.put(key, station);
        saveStations();
        return station;
    }

    public Optional<Station> station(Block block) {
        return Optional.ofNullable(stations.get(BlockKey.of(block.getLocation())));
    }

    public boolean stationBreakPending(Station station) {
        return stationBreaks.isClaimed(Objects.requireNonNull(station, "station").position());
    }

    public boolean canBreak(Block block) {
        BlockKey key = BlockKey.of(block.getLocation());
        Station station = stations.get(key);
        return station == null || ProcessingStationPolicy.breakDecision(
                station.type(),
                jobs.containsKey(station.stationId()),
                pendingStations.contains(station.stationId()) || stationBreaks.isClaimed(key)
        ) == ProcessingStationPolicy.BreakDecision.ALLOW;
    }

    public StationBreakResult claimStationForBreak(Block block) {
        requireMainThread();
        BlockKey key = BlockKey.of(block.getLocation());
        Station station = stations.get(key);
        if (station == null) {
            return new StationBreakResult(StationBreakStatus.NOT_STATION, null, null);
        }
        boolean busy = ProcessingStationPolicy.breakDecision(
                station.type(),
                jobs.containsKey(station.stationId()),
                pendingStations.contains(station.stationId())
        ) == ProcessingStationPolicy.BreakDecision.BUSY;
        Optional<StationBreakGuard.Lease<BlockKey>> lease = stationBreaks.acquire(key, busy);
        if (lease.isEmpty()) {
            return new StationBreakResult(StationBreakStatus.BUSY, station, null);
        }
        return new StationBreakResult(StationBreakStatus.CLAIMED, station, lease.orElseThrow());
    }

    public boolean completeStationBreak(Block block, StationBreakResult claim) {
        requireMainThread();
        Objects.requireNonNull(claim, "claim");
        if (claim.status() != StationBreakStatus.CLAIMED || claim.lease() == null) {
            return false;
        }
        BlockKey key = BlockKey.of(block.getLocation());
        Station station = claim.optionalStation().orElseThrow();
        try {
            if (!stations.remove(key, station)) {
                return false;
            }
            saveStations();
            return true;
        } finally {
            claim.close();
        }
    }

    public void removeStation(Block block) {
        StationBreakResult claim = claimStationForBreak(block);
        if (claim.status() == StationBreakStatus.CLAIMED) {
            completeStationBreak(block, claim);
        }
    }

    public void open(Player player, Station station) {
        requireMainThread();
        String titleKey = switch (station.type()) {
            case DRYING_RACK -> "gui.processing.drying-title";
            case CURING_CONTAINER -> "gui.processing.curing-title";
            case GRINDER -> "gui.processing.grinder-title";
        };
        ProcessingMenuHolder holder =
                new ProcessingMenuHolder(station.stationId(), station.type());
        Inventory inventory = Bukkit.createInventory(
                holder,
                27,
                translations.component(player, titleKey)
        );
        holder.attach(inventory);
        ItemStack filler = icon(player, Material.GRAY_STAINED_GLASS_PANE, Component.empty());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
        inventory.setItem(11, icon(
                player,
                Material.LIME_DYE,
                translations.component(player, "gui.processing.input-slot")
        ));
        ProcessJob job = jobs.get(station.stationId());
        inventory.setItem(13, statusIcon(player, station, job));
        inventory.setItem(15, icon(
                player,
                Material.CHEST,
                translations.component(player, "gui.processing.output-slot")
        ));
        inventory.setItem(22, icon(
                player,
                Material.BARRIER,
                translations.component(player, "common.close")
        ));
        player.openInventory(inventory);
    }

    public void handleMenuClick(Player player, ProcessingMenuHolder holder, int rawSlot) {
        requireMainThread();
        Station station = stations.values().stream()
                .filter(value -> value.stationId().equals(holder.stationId()))
                .findFirst()
                .orElse(null);
        if (station == null) {
            player.closeInventory();
            player.sendMessage(translations.component(player, "errors.target-not-found"));
            return;
        }
        if (rawSlot == 22) {
            player.closeInventory();
        } else if (rawSlot == 11) {
            if (stationBreakPending(station) || jobs.containsKey(station.stationId())) {
                player.sendMessage(translations.component(player, "errors.busy"));
                return;
            }
            switch (station.type()) {
                case DRYING_RACK -> startDrying(player, station);
                case CURING_CONTAINER -> startCuring(player, station);
                case GRINDER -> grind(player, station);
            }
        } else if (rawSlot == 15) {
            retrieve(player, station);
        } else if (rawSlot == 13) {
            open(player, station);
        }
    }

    public CompletableFuture<Void> mixHands(Player player) {
        requireMainThread();
        ItemStack first = player.getInventory().getItemInMainHand().clone();
        ItemStack second = player.getInventory().getItemInOffHand().clone();
        ItemSigner.Verification firstVerification = signer.verify(first);
        ItemSigner.Verification secondVerification = signer.verify(second);
        if (!firstVerification.valid() || !secondVerification.valid()
                || firstVerification.data().itemType() != CustomItemType.GROUND_PRODUCT
                || secondVerification.data().itemType() != CustomItemType.GROUND_PRODUCT) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("invalid_or_wrong_item")
            );
        }
        SignedItemData firstData = firstVerification.data();
        SignedItemData secondData = secondVerification.data();
        if (Objects.equals(firstData.batchId(), secondData.batchId())) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("duplicate_source_batch")
            );
        }
        ProductBatch firstBatch = requireBatch(firstData, BatchState.GROUND);
        ProductBatch secondBatch = requireBatch(secondData, BatchState.GROUND);
        if (!firstBatch.ownerUuid().equals(player.getUniqueId())
                || !secondBatch.ownerUuid().equals(player.getUniqueId())) {
            return CompletableFuture.failedFuture(new IllegalStateException("wrong_owner"));
        }
        GroundProduct firstGround = ground(firstBatch);
        GroundProduct secondGround = ground(secondBatch);
        int maximum = quality().getInt("processing.mixing.maximum-batches", 4);
        Mixture mixture = mixing.mix(
                UUID.randomUUID(),
                player.getUniqueId(),
                List.of(
                        new MixtureComponent(firstGround, firstGround.weight()),
                        new MixtureComponent(secondGround, secondGround.weight())
                ),
                maximum,
                0.85,
                0.0,
                ThreadLocalRandom.current(),
                Instant.now()
        );
        ProductBatch mixed = batchFromMixture(mixture, firstBatch, secondBatch);
        return commitOutput(player, List.of(first, second), List.of(
                new ProcessingTransactionService.Change(firstBatch, consumedSource(firstBatch)),
                new ProcessingTransactionService.Change(secondBatch, consumedSource(secondBatch))),
                mixed, CustomItemType.MIXTURE, mixed, "mix:" + mixed.batchId());
    }

    public CompletableFuture<Void> startRolling(Player player) {
        requireMainThread();
        UUID playerId = player.getUniqueId();
        Optional<RollingStartGuard.Lease> leaseResult = rollingStarts.acquire(
                playerId,
                rollingSessions.containsKey(playerId)
        );
        if (leaseResult.isEmpty()) {
            return CompletableFuture.failedFuture(new ProcessingFailureException(
                    ProcessingFailureReason.ROLLING_BUSY));
        }
        RollingStartGuard.Lease lease = leaseResult.orElseThrow();

        Inventory inventory = player.getInventory();
        LocatedItem paper = find(inventory, CustomItemType.ROLLING_PAPER);
        LocatedItem filter = find(inventory, CustomItemType.FILTER);
        LocatedItem product = find(
                inventory,
                CustomItemType.MIXTURE,
                CustomItemType.GROUND_PRODUCT
        );
        Optional<ProcessingFailureReason> missing = RollingIngredientPolicy.missing(
                paper != null,
                filter != null,
                product != null
        );
        if (missing.isPresent()) {
            lease.close();
            return CompletableFuture.failedFuture(
                    new ProcessingFailureException(missing.orElseThrow()));
        }

        LocatedItem requiredPaper = Objects.requireNonNull(paper, "paper");
        LocatedItem requiredFilter = Objects.requireNonNull(filter, "filter");
        LocatedItem requiredProduct = Objects.requireNonNull(product, "product");
        UUID sessionId = UUID.randomUUID();
        liveRollingSessions.add(sessionId);
        CompletableFuture<SecureItemService.ValidatedItem> paperValidation =
                secureItems.validateActive(requiredPaper.item(), ROLLING_PAPER, playerId);
        CompletableFuture<SecureItemService.ValidatedItem> filterValidation =
                secureItems.validateActive(requiredFilter.item(), ROLLING_FILTER, playerId);
        CompletableFuture<SecureItemService.ValidatedItem> productValidation =
                secureItems.validateActive(requiredProduct.item(), ROLLING_PRODUCT, playerId);

        CompletableFuture<Void> result = paperValidation
                .thenCombine(filterValidation, RollingValidatedPair::new)
                .thenCombine(productValidation, (pair, validatedProduct) ->
                        new RollingValidatedInputs(
                                pair.paper(),
                                pair.filter(),
                                validatedProduct
                        ))
                .thenCompose(validated -> batchService.require(
                        validated.product().data().batchId(),
                        playerId,
                        Set.of(BatchState.MIXED, BatchState.GROUND)
                ).thenCompose(source -> transactions.startRolling(
                        playerId, sessionId, source,
                        validated.paper().identity(),
                        validated.filter().identity(),
                        validated.product().identity(),
                        Instant.now()
                ).thenCompose(ignored -> onMain(() -> {
                    if (!player.isOnline()) throw new IllegalStateException("Player left during rolling start");
                    removeInstance(player, validated.paper().data().uniqueInstanceId());
                    removeInstance(player, validated.filter().data().uniqueInstanceId());
                    removeInstance(player, validated.product().data().uniqueInstanceId());
                    beginRollingGui(
                            player,
                            sessionId,
                            source,
                            validated.product().data().itemType()
                    );
                }))));
        return result.exceptionallyCompose(failure -> {
            liveRollingSessions.remove(sessionId);
            return transactions.restoreRolling(playerId, sessionId).handle((ignored, refundFailure) -> {
                if (refundFailure != null) failure.addSuppressed(refundFailure);
                pendingPlayers.add(playerId);
                throw new CompletionException(unwrap(failure));
            });
        }).whenComplete((ignored, failure) -> lease.close());
    }

    public void handleRollingClick(Player player, RollingMenuHolder holder, int rawSlot) {
        requireMainThread();
        RollingSession session = rollingSessions.get(player.getUniqueId());
        if (session == null || !session.sessionId().equals(holder.sessionId())) {
            player.closeInventory();
            return;
        }
        if (session.completing()) return;
        if (rawSlot == 22) {
            abortRolling(player.getUniqueId(), false);
            player.closeInventory();
            return;
        }
        int expectedSlot = session.slots().get(session.step());
        if (rawSlot != expectedSlot) {
            session.errors().add(1_200L);
        } else {
            long elapsed = Math.max(0L, System.currentTimeMillis() - session.stepStartedAt());
            session.errors().add(Math.abs(1_000L - elapsed));
        }
        session.steps().add(RollingStep.values()[session.step()]);
        int next = session.step() + 1;
        if (next >= RollingStep.values().length) {
            finishRolling(player, session);
            return;
        }
        session.step(next);
        session.stepStartedAt(System.currentTimeMillis());
        renderRolling(player, session);
    }

    public void onRollingClosed(Player player, RollingMenuHolder holder) {
        RollingSession session = rollingSessions.get(player.getUniqueId());
        if (session != null && session.sessionId().equals(holder.sessionId())
                && !session.completing()) {
            abortRolling(player.getUniqueId(), false);
        }
    }

    public void abortForPlayer(Player player) {
        requireMainThread();
        abortRolling(player.getUniqueId(), false);
    }

    public int activeProcesses(UUID owner) {
        return (int) jobs.values().stream().filter(job -> job.ownerUuid().equals(owner)).count();
    }

    public Optional<ProductBatch> batch(UUID id) {
        return batchService.cached(id);
    }

    public ProcessingTransferService transfers() {
        return transfers;
    }

    private CompletableFuture<Void> commitOutput(Player player, List<ItemStack> inputs,
            List<ProcessingTransactionService.Change> changes, ProductBatch created,
            CustomItemType outputType, ProductBatch outputBatch, String operation) {
        return commitOutput(player, inputs, changes, created, outputType, outputBatch, operation, null, null);
    }

    private CompletableFuture<Void> commitOutput(Player player, List<ItemStack> inputs,
            List<ProcessingTransactionService.Change> changes, ProductBatch created,
            CustomItemType outputType, ProductBatch outputBatch, String operation,
            ProcessingTransactionService.CompletedJob completedJob, UUID rollingSessionId) {
        requireMainThread();
        UUID owner = player.getUniqueId();
        var prepared = secureItems.prepare(player, outputType, owner, outputBatch);
        SignedItemData data = prepared.data();
        var output = new com.verdantvice.database.item.ItemRegistration(data.uniqueInstanceId(),
                data.itemType().name(), data.itemId(), data.batchId(), data.ownerUuid(), data.quality(),
                data.strain(), data.createdAt(), prepared.payloadHash(), data.schemaVersion(), null);
        List<CompletableFuture<SecureItemService.ValidatedItem>> validations = inputs.stream().map(item -> {
            ItemSigner.Verification verified = signer.verify(item);
            if (!verified.valid()) throw new IllegalArgumentException("invalid_item");
            return secureItems.validateActive(item, Set.of(verified.data().itemType()), owner);
        }).toList();
        return CompletableFuture.allOf(validations.toArray(CompletableFuture[]::new))
                .thenCompose(ignored -> {
                    var plan = new ProcessingTransactionService.Plan(owner,
                        validations.stream().map(future -> future.join().identity()).toList(), changes,
                        created, output, null, operation, Instant.now());
                    return rollingSessionId != null ? transactions.finishRolling(plan, rollingSessionId)
                            : completedJob != null ? transactions.retrieve(plan, completedJob) : transactions.commit(plan);
                })
                .thenCompose(ignored -> {
                    pendingPlayers.add(owner);
                    return deliverPending(player).exceptionally(failure -> {
                    plugin.getLogger().log(Level.WARNING,
                            "Processing committed; inventory receipt will be replayed", unwrap(failure));
                    return 0;
                    });
                }).thenApply(ignored -> null);
    }

    /** Replays only DB-committed outputs and acknowledges after saving the actual player inventory. */
    public CompletableFuture<Integer> deliverPending(Player player) {
        UUID owner = player.getUniqueId();
        pendingPlayers.add(owner);
        return outputQueue.submit(owner, () -> transactions.restoreInterruptedRolling(owner, liveRollingSessions)
                .thenCompose(ignored -> transactions.pending(owner)).thenCompose(receipts -> {
            List<UUID> delivered = new ArrayList<>();
            return onMain(() -> {
                if (!player.isOnline()) return;
                for (ProcessingTransactionService.PendingOutput receipt : receipts) {
                    ProductBatch batch = receipt.data().batchId() == null ? null
                            : batchService.cachedOrNull(receipt.data().batchId());
                    if (receipt.data().batchId() != null && batch == null) continue;
                    receipt.inputs().forEach(id -> removeInstance(player, id));
                    secureItems.beginDelivery(receipt.data().uniqueInstanceId());
                    boolean present = false;
                    for (ItemStack stack : player.getInventory().getContents()) {
                        var verified = signer.verify(stack);
                        if (verified.valid() && receipt.data().uniqueInstanceId().equals(
                                verified.data().uniqueInstanceId())) { present = true; break; }
                    }
                    ItemStack output = batch == null ? secureItems.recreate(player, receipt.data())
                            : secureItems.recreate(player, receipt.data(), batch);
                    if (present || player.getInventory().addItem(output).isEmpty()) {
                        delivered.add(receipt.data().uniqueInstanceId());
                    }
                }
                if (!receipts.isEmpty()) player.saveData();
            }).thenCompose(ignored -> CompletableFuture.allOf(delivered.stream()
                    .map(id -> transactions.acknowledge(owner, id)
                            .thenRun(() -> secureItems.endDelivery(id))).toArray(CompletableFuture[]::new)))
                    .thenCompose(ignored -> transactions.pending(owner))
                    .thenApply(remaining -> {
                        if (remaining.isEmpty()) pendingPlayers.remove(owner);
                        return delivered.size();
                    });
        }));
    }

    public boolean hasPendingDelivery(UUID owner) {
        return pendingPlayers.contains(owner);
    }

    /** Starts the same persistent drying process as the player GUI, without a fake player. */
    public CompletableFuture<ProductBatch> loadDryingByWorker(
            ItemStack input, UUID owner, UUID stationId) {
        requireMainThread();
        Station station = stations.values().stream()
                .filter(value -> value.stationId().equals(stationId)
                        && value.type() == ProcessingStationType.DRYING_RACK)
                .findFirst().orElse(null);
        ItemSigner.Verification verification = signer.verify(input);
        if (station == null || stationBreakPending(station) || !verification.valid()
                || verification.data().itemType() != CustomItemType.FRESH_BATCH) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("invalid_drying_input"));
        }
        ProcessingStartGuard.Lease lease = startGuard.tryAcquire(stationId,
                verification.data().uniqueInstanceId()).orElse(null);
        if (lease == null) return CompletableFuture.failedFuture(
                new ProcessingFailureException(ProcessingFailureReason.STATION_BUSY));
        YamlConfiguration config = quality();
        DryingStartService.Parameters parameters = new DryingStartService.Parameters(
                Duration.ofMinutes(config.getLong("processing.drying.duration-minutes", 20L)),
                config.getDouble("processing.drying.cleanliness", 92.0),
                config.getDouble("processing.drying.stability", 90.0),
                config.getDouble("processing.drying.speed", 1.0));
        return secureItems.validateActive(input, FRESH, owner)
                .thenCompose(item -> dryingStarts.start(item, owner, stationId,
                        Instant.now(), parameters))
                .thenCompose(result -> onMain(() -> jobs.put(stationId, new ProcessJob(
                        result.jobId(), stationId, result.batch().batchId(), owner,
                        ProcessingStationType.DRYING_RACK, false)))
                        .thenApply(ignored -> result.batch()))
                .whenComplete((ignored, failure) -> lease.close());
    }

    /** Removes a ready job and queues its signed output in one database transaction. */
    public CompletableFuture<ProcessingTransferService.Delivery> unloadDryingByWorker(
            UUID stationId, UUID batchId, UUID owner, UUID farmId,
            UUID storageId, String operationId) {
        requireMainThread();
        if (!pendingStations.add(stationId)) return CompletableFuture.failedFuture(
                new ProcessingFailureException(ProcessingFailureReason.STATION_BUSY));
        ProcessJob job = jobs.get(stationId);
        CompletableFuture<ProductBatch> updated = job != null
                && job.batchId().equals(batchId) && job.ownerUuid().equals(owner)
                ? updateOne(job, true)
                : CompletableFuture.completedFuture(null);
        return updated.thenCompose(ignored -> transfers.unloadDrying(
                        stationId, batchId, owner, farmId, storageId, operationId, Instant.now()))
                .thenCompose(delivery -> onMain(() -> jobs.computeIfPresent(stationId,
                        (key, current) -> current.batchId().equals(batchId) ? null : current))
                        .thenApply(ignored -> delivery))
                .whenComplete((ignored, failure) -> pendingStations.remove(stationId));
    }

    public List<StationSnapshot> stationSnapshots(
            Farm farm,
            ProcessingStationType type
    ) {
        requireMainThread();
        Objects.requireNonNull(farm, "farm");
        Objects.requireNonNull(type, "type");
        Instant now = Instant.now();
        return stations.values().stream()
                .filter(station -> station.type() == type)
                .filter(station -> farm.region().contains(new BlockPosition(
                        station.position().worldUuid(),
                        station.position().x(),
                        station.position().y(),
                        station.position().z()
                )))
                .map(station -> stationSnapshot(station, now))
                .sorted(java.util.Comparator.comparing(value ->
                        value.station().stationId().toString()))
                .toList();
    }

    private StationSnapshot stationSnapshot(Station station, Instant now) {
        ProcessJob job = jobs.get(station.stationId());
        ProductBatch batch = job == null
                ? null
                : batchService.cachedOrNull(job.batchId());
        StationStatus status = job == null
                ? StationStatus.FREE
                : job.ready() ? StationStatus.READY : StationStatus.PROCESSING;
        long remainingSeconds = status == StationStatus.PROCESSING && batch != null
                ? remainingSeconds(batch, station.type(), now)
                : 0L;
        Location location = station.position().location();
        boolean exists = location != null
                && location.getWorld().isChunkLoaded(
                        location.getBlockX() >> 4,
                        location.getBlockZ() >> 4
                )
                && location.getBlock().getType() == station.type().material();
        return new StationSnapshot(
                station,
                status,
                job == null ? Optional.empty() : Optional.of(job.batchId()),
                job == null ? Optional.empty() : Optional.of(job.ownerUuid()),
                Optional.ofNullable(batch),
                remainingSeconds,
                exists
        );
    }

    private long remainingSeconds(
            ProductBatch batch,
            ProcessingStationType type,
            Instant now
    ) {
        if (type == ProcessingStationType.DRYING_RACK) {
            return batch.dryingData().map(data -> {
                long effectiveMillis = Math.max(1L, Math.round(
                        data.targetDuration().toMillis() / data.rackSpeed()
                ));
                long elapsed = Math.max(0L, Duration.between(
                        data.startedAt(), now
                ).toMillis());
                return Math.max(0L, (effectiveMillis - elapsed + 999L) / 1000L);
            }).orElse(0L);
        }
        if (type == ProcessingStationType.CURING_CONTAINER) {
            return batch.curingData().map(data -> {
                long elapsed = Math.max(0L, Duration.between(
                        data.startedAt(), now
                ).toMillis());
                return Math.max(0L, (
                        data.targetDuration().toMillis() - elapsed + 999L
                ) / 1000L);
            }).orElse(0L);
        }
        return 0L;
    }

    public CompletableFuture<Void> setQuality(
            Player player,
            ItemStack item,
            QualityGrade grade
    ) {
        requireMainThread();
        ItemSigner.Verification verification = signer.verify(item);
        if (!verification.valid() || verification.data().batchId() == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("invalid_item")
            );
        }
        SignedItemData data = verification.data();
        ProductBatch source = batchService.cachedOrNull(data.batchId());
        if (source == null || !authenticity.verify(source, signingSecret)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("invalid_batch")
            );
        }
        double score = grade.minimumScore();
        ProductBatch updated = authenticity.sign(new ProductBatch(
                source.batchId(),
                source.ownerUuid(),
                source.strainComposition(),
                source.geneticOrigin(),
                source.freshWeight(),
                source.dryWeight(),
                score,
                grade,
                grade == QualityGrade.SPOILED ? 100.0 : source.contamination(),
                grade == QualityGrade.SPOILED ? 0.0 : source.freshness(),
                source.harvestTiming(),
                source.dryingData(),
                source.curingData(),
                source.createdAt(),
                "",
                grade == QualityGrade.SPOILED ? BatchState.SPOILED : source.state()
        ), signingSecret);
        CustomItemType itemType = data.itemType();
        return commitOutput(player, List.of(item.clone()),
                List.of(new ProcessingTransactionService.Change(source, updated)), null,
                itemType, updated, "admin-quality:" + UUID.randomUUID());
    }

    private void startDrying(Player player, Station station) {
        requireMainThread();
        ItemStack input = player.getInventory().getItemInMainHand().clone();
        ItemSigner.Verification verification = signer.verify(input);
        if (!verification.valid()) {
            sendProcessingFailure(player, ProcessingFailureReason.INVALID_SIGNATURE);
            return;
        }
        SignedItemData data = verification.data();
        if (data.itemType() != CustomItemType.FRESH_BATCH || data.batchId() == null) {
            sendProcessingFailure(player, ProcessingFailureReason.WRONG_BATCH_STATE);
            return;
        }
        YamlConfiguration config = quality();
        DryingStartService.Parameters parameters = new DryingStartService.Parameters(
                Duration.ofMinutes(config.getLong(
                        "processing.drying.duration-minutes", 20L)),
                config.getDouble("processing.drying.cleanliness", 92.0),
                config.getDouble("processing.drying.stability", 90.0),
                config.getDouble("processing.drying.speed", 1.0)
        );
        ProcessingStartGuard.Lease lease = startGuard.tryAcquire(
                station.stationId(), data.uniqueInstanceId()).orElse(null);
        if (lease == null) {
            sendProcessingFailure(player, ProcessingFailureReason.STATION_BUSY);
            return;
        }

        Instant now = Instant.now();
        CompletableFuture<DryingStartService.Result> attempt;
        try {
            attempt = secureItems.validateActive(input, FRESH, player.getUniqueId())
                    .thenCompose(validated -> batchService.require(
                            data.batchId(), player.getUniqueId(), Set.of(BatchState.FRESH))
                            .thenApply(ignored -> validated))
                    .thenCompose(validated -> dryingStarts.start(
                            validated, player.getUniqueId(), station.stationId(), now, parameters));
        } catch (Throwable failure) {
            lease.close();
            handleProcessingFailure(player, "Could not start drying", failure);
            return;
        }
        attempt.whenComplete((result, failure) -> {
            onMain(() -> {
                if (failure != null || result == null) {
                    handleProcessingFailure(player, "Could not start drying", failure);
                    return;
                }
                ProductBatch started = result.batch();
                removeInstance(player, data.uniqueInstanceId());
                jobs.put(station.stationId(), new ProcessJob(
                        result.jobId(),
                        station.stationId(),
                        started.batchId(),
                        player.getUniqueId(),
                        ProcessingStationType.DRYING_RACK,
                        false
                ));
                player.sendMessage(translations.component(
                        player,
                        "drying.loaded",
                        Map.of("batch_id", started.batchId())
                ));
                open(player, station);
            }).whenComplete((ignored, callbackFailure) -> lease.close());
        });
    }

    private void startCuring(Player player, Station station) {
        ItemStack input = player.getInventory().getItemInMainHand().clone();
        ItemSigner.Verification verification = signer.verify(input);
        if (!verification.valid() || verification.data().itemType() != CustomItemType.DRY_BATCH) {
            player.sendMessage(translations.component(player, "errors.wrong-item-state"));
            return;
        }
        SignedItemData data = verification.data();
        ProductBatch source;
        try {
            source = requireBatch(data, BatchState.DRIED);
        } catch (RuntimeException failure) {
            player.sendMessage(translations.component(player, "errors.wrong-item-state"));
            return;
        }
        if (!pendingStations.add(station.stationId())) {
            player.sendMessage(translations.component(player, "errors.busy"));
            return;
        }
        YamlConfiguration config = quality();
        ProductBatch started = authenticity.sign(curing.start(
                source,
                Instant.now(),
                Duration.ofMinutes(config.getLong(
                        "processing.curing.duration-minutes",
                        30L
                )),
                config.getDouble("processing.curing.cleanliness", 94.0),
                config.getDouble("processing.curing.stability", 92.0)
        ), signingSecret);
        ProcessJob job = new ProcessJob(
                UUID.randomUUID(),
                station.stationId(),
                started.batchId(),
                player.getUniqueId(),
                ProcessingStationType.CURING_CONTAINER,
                false
        );
        secureItems.validateActive(input, DRIED, player.getUniqueId())
                .thenCompose(validated -> transactions.commit(new ProcessingTransactionService.Plan(
                        player.getUniqueId(), List.of(validated.identity()),
                        List.of(new ProcessingTransactionService.Change(source, started)), null, null,
                        new ProcessingTransactionService.CuringJob(job.jobId(), station.stationId()),
                        "cure:" + job.jobId(), Instant.now())))
                .whenComplete((ignored, failure) -> onMain(() -> {
                    pendingStations.remove(station.stationId());
                    if (failure != null) {
                        fail(player, "Could not start curing", failure);
                        return;
                    }
                    removeInstance(player, data.uniqueInstanceId());
                    batchService.remember(started);
                    jobs.put(station.stationId(), job);
                    player.sendMessage(translations.component(
                            player,
                            "curing.loaded",
                            Map.of("batch_id", started.batchId())
                    ));
                    open(player, station);
                }));
    }

    private void grind(Player player, Station station) {
        ItemStack input = player.getInventory().getItemInMainHand().clone();
        ItemSigner.Verification verification = signer.verify(input);
        if (!verification.valid() || !GRINDABLE.contains(verification.data().itemType())) {
            player.sendMessage(translations.component(player, "errors.wrong-item-state"));
            return;
        }
        SignedItemData data = verification.data();
        ProductBatch source = batchService.cachedOrNull(data.batchId());
        if (source == null || (source.state() != BatchState.DRIED
                && source.state() != BatchState.CURED)
                || !authenticity.verify(source, signingSecret)) {
            player.sendMessage(translations.component(player, "errors.invalid-signature"));
            return;
        }
        YamlConfiguration config = quality();
        GroundProduct ground = grinding.grind(
                UUID.randomUUID(),
                source,
                new GrinderParameters(
                        1,
                        Math.max(1.0, source.dryWeight()),
                        100.0,
                        config.getDouble("processing.grinding.efficiency", 0.92),
                        config.getDouble("processing.grinding.loss-risk", 0.06)
                ),
                source.dryWeight(),
                0.0,
                ThreadLocalRandom.current(),
                Instant.now()
        );
        ProductBatch transformed = authenticity.sign(new ProductBatch(
                source.batchId(),
                source.ownerUuid(),
                ground.strainComposition(),
                source.geneticOrigin(),
                source.freshWeight(),
                ground.weight(),
                ground.qualityScore(),
                ground.qualityGrade(),
                source.contamination(),
                source.freshness(),
                source.harvestTiming(),
                source.dryingData(),
                source.curingData(),
                source.createdAt(),
                "",
                BatchState.GROUND
        ), signingSecret);
        commitOutput(player, List.of(input),
                List.of(new ProcessingTransactionService.Change(source, transformed)), null,
                CustomItemType.GROUND_PRODUCT, transformed, "grind:" + ground.productId())
                .whenComplete((ignored, failure) -> onMain(() -> {
                    if (failure != null) {
                        fail(player, "Could not grind batch", failure);
                        return;
                    }
                    open(player, station);
                }));
    }

    private void retrieve(Player player, Station station) {
        ProcessJob job = jobs.get(station.stationId());
        if (job == null) {
            player.sendMessage(translations.component(player, "errors.target-not-found"));
            return;
        }
        if (!job.ownerUuid().equals(player.getUniqueId())) {
            player.sendMessage(translations.component(player, "errors.wrong-owner"));
            return;
        }
        if (!pendingStations.add(station.stationId())) {
            player.sendMessage(translations.component(player, "errors.busy"));
            return;
        }
        updateOne(job, true).whenComplete((updated, failure) -> onMain(() -> {
            if (failure != null || updated == null) {
                pendingStations.remove(station.stationId());
                fail(player, "Could not update process", failure);
                return;
            }
            boolean ready = job.type() == ProcessingStationType.DRYING_RACK
                    ? updated.state() == BatchState.DRIED || updated.state() == BatchState.SPOILED
                    : updated.state() == BatchState.CURED || updated.state() == BatchState.SPOILED;
            if (!ready) {
                pendingStations.remove(station.stationId());
                player.sendMessage(translations.component(player, "errors.wrong-item-state"));
                open(player, station);
                return;
            }
            CustomItemType outputType = updated.state() == BatchState.SPOILED
                    ? CustomItemType.DRY_BATCH
                    : job.type() == ProcessingStationType.DRYING_RACK
                    ? CustomItemType.DRY_BATCH
                    : CustomItemType.CURED_BATCH;
            // The job disappears in the same transaction which records its one durable output.
            commitOutput(player, List.of(), List.of(), null, outputType, updated,
                    "retrieve:" + job.jobId(), new ProcessingTransactionService.CompletedJob(
                            job.jobId(), station.stationId(), job.batchId(), job.type()), null)
                    .whenComplete((ignored, deliveryFailure) -> onMain(() -> {
                        pendingStations.remove(station.stationId());
                        if (deliveryFailure != null) {
                            fail(player, "Could not retrieve process", deliveryFailure);
                            return;
                        }
                        jobs.remove(station.stationId());
                        player.sendMessage(translations.component(
                                player,
                                job.type() == ProcessingStationType.DRYING_RACK
                                        ? "drying.unloaded"
                                        : "curing.completed",
                                Map.of("batch_id", updated.batchId())
                        ));
                        open(player, station);
                    }));
        }));
    }

    private void updateProcesses() {
        requireMainThread();
        for (ProcessJob job : List.copyOf(jobs.values())) {
            if (job.ready() || !pendingStations.add(job.stationId())) {
                continue;
            }
            updateOne(job, false).whenComplete((updated, failure) -> onMain(() -> {
                pendingStations.remove(job.stationId());
                if (failure != null) {
                    plugin.getLogger().log(
                            Level.WARNING,
                            "Could not update processing job " + job.jobId(),
                            unwrap(failure)
                    );
                }
            }));
        }
    }

    private CompletableFuture<ProductBatch> updateOne(ProcessJob job, boolean force) {
        ProductBatch source = batchService.cachedOrNull(job.batchId());
        if (source == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Missing process batch"));
        }
        ProductBatch updated = job.type() == ProcessingStationType.DRYING_RACK
                ? drying.update(source, Instant.now(), offlineProcessing() || force)
                : curing.update(source, Instant.now(), offlineProcessing() || force);
        updated = authenticity.sign(updated, signingSecret);
        boolean ready = job.type() == ProcessingStationType.DRYING_RACK
                ? updated.state() == BatchState.DRIED || updated.state() == BatchState.SPOILED
                : updated.state() == BatchState.CURED || updated.state() == BatchState.SPOILED;
        ProductBatch finalUpdated = updated;
        return batchService.save(finalUpdated)
                .thenCompose(ignored -> updateJob(job, finalUpdated, ready))
                .thenCompose(ignored -> onMain(() -> {
                    batchService.remember(finalUpdated);
                    ProcessJob current = jobs.get(job.stationId());
                    if (current != null) {
                        jobs.put(job.stationId(), current.withReady(ready));
                    }
                    fireProcessEvent(job, source, finalUpdated, ready);
                }).thenApply(mainThreadResult -> finalUpdated));
    }

    private void fireProcessEvent(
            ProcessJob job,
            ProductBatch before,
            ProductBatch after,
            boolean ready
    ) {
        if (job.type() == ProcessingStationType.DRYING_RACK) {
            DryingStage beforeStage = before.dryingData().orElseThrow().stage();
            DryingStage afterStage = after.dryingData().orElseThrow().stage();
            if (beforeStage != afterStage) {
                Bukkit.getPluginManager().callEvent(
                        new VerdantBatchDryEvent(after.batchId(), afterStage)
                );
            }
            if (ready && !job.ready()) {
                Player owner = Bukkit.getPlayer(job.ownerUuid());
                if (owner != null) {
                    notifications.send(
                            owner,
                            NotificationType.DRYING_COMPLETE,
                            "notifications.types.drying-complete",
                            Map.of("batch_id", after.batchId())
                    );
                }
            }
        } else {
            CuringStage beforeStage = before.curingData().orElseThrow().stage();
            CuringStage afterStage = after.curingData().orElseThrow().stage();
            if (beforeStage != afterStage) {
                Bukkit.getPluginManager().callEvent(
                        new VerdantBatchCureEvent(after.batchId(), afterStage)
                );
            }
            if (ready && !job.ready()) {
                Player owner = Bukkit.getPlayer(job.ownerUuid());
                if (owner != null) {
                    notifications.send(
                            owner,
                            NotificationType.CURING_COMPLETE,
                            "notifications.types.curing-complete",
                            Map.of("batch_id", after.batchId())
                    );
                }
            }
        }
    }

    private CompletableFuture<Void> updateJob(
            ProcessJob job,
            ProductBatch batch,
            boolean ready
    ) {
        if (job.type() == ProcessingStationType.DRYING_RACK) {
            DryingData data = batch.dryingData().orElseThrow();
            return database.update(
                    """
                    UPDATE drying_jobs SET drying_stage=?, status=?,
                      last_updated_at=?, row_version=row_version+1
                    WHERE drying_job_id=?
                    """,
                    data.stage(),
                    ready ? "READY" : "ACTIVE",
                    data.lastUpdatedAt(),
                    job.jobId()
            ).thenApply(ignored -> null);
        }
        CuringData data = batch.curingData().orElseThrow();
        return database.update(
                """
                UPDATE curing_jobs SET status=?, last_updated_at=?,
                  row_version=row_version+1
                WHERE curing_job_id=?
                """,
                ready ? "READY" : "ACTIVE",
                data.lastUpdatedAt(),
                job.jobId()
        ).thenApply(ignored -> null);
    }

    private ProcessJob job(ResultSet result, ProcessingStationType type) throws SQLException {
        return new ProcessJob(
                UUID.fromString(result.getString("drying_job_id")),
                UUID.fromString(result.getString("rack_id")),
                UUID.fromString(result.getString("batch_id")),
                UUID.fromString(result.getString("owner_uuid")),
                type,
                "READY".equals(result.getString("status"))
        );
    }

    private ProductBatch requireBatch(SignedItemData item, BatchState expected) {
        ProductBatch batch = batchService.cachedOrNull(item.batchId());
        if (batch == null || batch.state() != expected
                || !batch.ownerUuid().equals(item.ownerUuid())
                || !authenticity.verify(batch, signingSecret)) {
            throw new IllegalStateException("wrong_item_state");
        }
        return batch;
    }

    private ProductBatch consumedSource(ProductBatch source) {
        return authenticity.sign(source.transition(
                source.dryWeight(),
                source.qualityScore(),
                source.contamination(),
                source.freshness(),
                source.dryingData(),
                source.curingData(),
                BatchState.ROLLED
        ), signingSecret);
    }

    private GroundProduct ground(ProductBatch batch) {
        return new GroundProduct(
                UUID.nameUUIDFromBytes(
                        ("ground:" + batch.batchId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)
                ),
                batch.ownerUuid(),
                Set.of(batch.batchId()),
                batch.strainComposition(),
                Math.max(0.001, batch.dryWeight()),
                batch.qualityScore(),
                batch.qualityGrade(),
                0.85,
                batch.createdAt()
        );
    }

    private ProductBatch batchFromMixture(
            Mixture mixture,
            ProductBatch first,
            ProductBatch second
    ) {
        java.util.HashSet<UUID> genetics = new java.util.HashSet<>(first.geneticOrigin());
        genetics.addAll(second.geneticOrigin());
        ProductBatch result = new ProductBatch(
                mixture.mixtureId(),
                mixture.ownerUuid(),
                mixture.strainComposition(),
                genetics,
                mixture.totalWeight(),
                mixture.totalWeight(),
                mixture.qualityScore(),
                mixture.qualityGrade(),
                (first.contamination() + second.contamination()) / 2.0,
                Math.min(first.freshness(), second.freshness()),
                first.harvestTiming(),
                Optional.empty(),
                Optional.empty(),
                mixture.createdAt(),
                "",
                BatchState.MIXED
        );
        return authenticity.sign(result, signingSecret);
    }

    private void beginRollingGui(
            Player player,
            UUID sessionId,
            ProductBatch source,
            CustomItemType sourceType
    ) {
        List<Integer> slots = List.of(10, 12, 14, 16, 13);
        RollingSession session = new RollingSession(
                sessionId,
                player.getUniqueId(),
                source,
                sourceType,
                slots,
                new ArrayList<>(),
                new ArrayList<>(),
                0,
                System.currentTimeMillis(),
                false
        );
        rollingSessions.put(player.getUniqueId(), session);
        RollingMenuHolder holder = new RollingMenuHolder(player.getUniqueId(), sessionId);
        Inventory gui = Bukkit.createInventory(
                holder,
                27,
                translations.component(player, "gui.processing.rolling-title")
        );
        holder.attach(gui);
        player.openInventory(gui);
        renderRolling(player, session);
    }

    private void renderRolling(Player player, RollingSession session) {
        Inventory inventory = player.getOpenInventory().getTopInventory();
        if (!(inventory.getHolder() instanceof RollingMenuHolder holder)
                || !holder.sessionId().equals(session.sessionId())) {
            return;
        }
        ItemStack filler = icon(player, Material.GRAY_STAINED_GLASS_PANE, Component.empty());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
        Material[] materials = {
                Material.PAPER,
                Material.STICK,
                Material.DRIED_KELP,
                Material.STRING,
                Material.SLIME_BALL
        };
        for (int index = 0; index < session.slots().size(); index++) {
            String key = switch (index) {
                case 0 -> "rolling.steps.place-paper";
                case 1 -> "rolling.steps.add-filter";
                case 2 -> "rolling.steps.add-product";
                case 3 -> "rolling.steps.distribute";
                default -> "rolling.steps.finish-roll";
            };
            inventory.setItem(
                    session.slots().get(index),
                    icon(
                            player,
                            index == session.step() ? Material.LIME_CONCRETE : materials[index],
                            translations.component(player, key)
                    )
            );
        }
        inventory.setItem(22, icon(
                player,
                Material.BARRIER,
                translations.component(player, "common.cancel")
        ));
    }

    private void finishRolling(Player player, RollingSession session) {
        session.completing(true);
        Mixture mixture = mixtureFromBatch(session.source());
        RollingAttempt attempt = new RollingAttempt(
                List.copyOf(session.steps()),
                List.copyOf(session.errors()),
                1.0
        );
        RollingService service = new RollingService();
        RolledProduct rolled = service.roll(
                UUID.randomUUID(),
                mixture,
                attempt,
                Math.min(1.0, mixture.totalWeight()),
                0.0,
                Instant.now()
        );
        ProductBatch finished = authenticity.sign(session.source().transition(
                session.source().dryWeight(),
                session.source().qualityScore(),
                session.source().contamination(),
                session.source().freshness(),
                session.source().dryingData(),
                session.source().curingData(),
                BatchState.ROLLED
        ), signingSecret);
        commitOutput(player, List.of(),
                List.of(new ProcessingTransactionService.Change(session.source(), finished)), null,
                CustomItemType.JOINT, finished, "roll-finish:" + session.sessionId(), null, session.sessionId())
                .whenComplete((ignored, failure) -> onMain(() -> {
                    liveRollingSessions.remove(session.sessionId());
                    rollingSessions.remove(player.getUniqueId());
                    player.closeInventory();
                    if (failure != null) {
                        fail(player, "Could not complete rolling", failure);
                        restoreRollingInputs(player, session);
                        return;
                    }
                    player.sendMessage(translations.component(
                            player,
                            "rolling.complete",
                            Map.of("result", rolled.result().name())
                    ));
                }));
    }

    private void abortRolling(UUID playerId, boolean shutdown) {
        RollingSession session = rollingSessions.remove(playerId);
        if (session == null || session.completing()) {
            return;
        }
        liveRollingSessions.remove(session.sessionId());
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            restoreRollingInputs(player, session);
            if (!shutdown) {
                player.sendMessage(translations.component(player, "rolling.aborted"));
            }
        }
    }

    private void restoreRollingInputs(Player player, RollingSession session) {
        pendingPlayers.add(player.getUniqueId());
        transactions.restoreRolling(player.getUniqueId(), session.sessionId())
                .thenCompose(ignored -> deliverPending(player))
                .whenComplete((ignored, failure) -> {
                    if (failure != null) plugin.getLogger().log(Level.WARNING,
                            "Rolling refund remains recoverable from its durable receipt", unwrap(failure));
                });
    }

    private Mixture mixtureFromBatch(ProductBatch batch) {
        return new Mixture(
                batch.batchId(),
                batch.ownerUuid(),
                Set.of(batch.batchId()),
                batch.strainComposition(),
                Math.max(0.01, batch.dryWeight()),
                batch.qualityScore(),
                batch.qualityGrade(),
                0.85,
                batch.createdAt()
        );
    }

    private ItemStack statusIcon(Player player, Station station, ProcessJob job) {
        if (job == null) {
            return icon(
                    player,
                    Material.CLOCK,
                    translations.component(player, "common.none")
            );
        }
        ProductBatch batch = batchService.cachedOrNull(job.batchId());
        String status = batch == null
                ? translations.plain(player, "common.unknown")
                : batch.state().name();
        return icon(
                player,
                job.ready() ? Material.EMERALD : Material.CLOCK,
                translations.component(
                        player,
                        "common.status",
                        Map.of("status", status)
                )
        );
    }

    private ItemStack icon(Player player, Material material, Component name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        meta.addItemFlags(ItemFlag.values());
        item.setItemMeta(meta);
        return item;
    }

    private LocatedItem find(Inventory inventory, CustomItemType... types) {
        EnumSet<CustomItemType> accepted = EnumSet.noneOf(CustomItemType.class);
        java.util.Collections.addAll(accepted, types);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            ItemSigner.Verification verification = signer.verify(item);
            if (verification.valid() && accepted.contains(verification.data().itemType())) {
                return new LocatedItem(slot, item.clone(), verification.data());
            }
        }
        return null;
    }

    private void removeInstance(Player player, UUID instanceId) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            Optional<SignedItemData> data = signer.read(item);
            if (data.isPresent() && data.get().uniqueInstanceId().equals(instanceId)) {
                player.getInventory().setItem(slot, null);
                return;
            }
        }
    }

    private String dominantStrain(ProductBatch batch) {
        return batch.strainComposition().entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("unknown");
    }

    private boolean offlineProcessing() {
        return quality().getBoolean("processing.offline", true);
    }

    private YamlConfiguration quality() {
        return configs.get("quality.yml");
    }

    private void loadStations() {
        stations.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(stationFile);
        ConfigurationSection root = yaml.getConfigurationSection("stations");
        if (root == null) {
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) {
                continue;
            }
            try {
                Station station = new Station(
                        UUID.fromString(id),
                        ProcessingStationType.valueOf(section.getString("type", "")),
                        new BlockKey(
                                UUID.fromString(section.getString("world", "")),
                                section.getInt("x"),
                                section.getInt("y"),
                                section.getInt("z")
                        )
                );
                stations.put(station.position(), station);
            } catch (IllegalArgumentException invalid) {
                plugin.getLogger().log(
                        Level.WARNING,
                        "Ignored invalid processing station " + id,
                        invalid
                );
            }
        }
    }

    private void saveStations() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Station station : stations.values()) {
            String base = "stations." + station.stationId();
            yaml.set(base + ".type", station.type().name());
            yaml.set(base + ".world", station.position().worldUuid().toString());
            yaml.set(base + ".x", station.position().x());
            yaml.set(base + ".y", station.position().y());
            yaml.set(base + ".z", station.position().z());
        }
        try {
            File parent = stationFile.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Could not create " + parent);
            }
            yaml.save(stationFile);
        } catch (IOException failure) {
            plugin.getLogger().log(Level.SEVERE, "Could not save processing stations", failure);
        }
    }

    private void handleProcessingFailure(Player player, String context, Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof ProcessingFailureException expected) {
            sendProcessingFailure(player, expected.reason());
            return;
        }
        if (cause instanceof BatchAccessException batchFailure) {
            ProcessingFailureReason reason = switch (batchFailure.reason()) {
                case MISSING_BATCH -> ProcessingFailureReason.MISSING_BATCH;
                case INVALID_BATCH_SIGNATURE -> ProcessingFailureReason.INVALID_BATCH_SIGNATURE;
                case WRONG_OWNER -> ProcessingFailureReason.WRONG_OWNER;
                case WRONG_BATCH_STATE -> ProcessingFailureReason.WRONG_BATCH_STATE;
            };
            sendProcessingFailure(player, reason);
            return;
        }
        if (cause instanceof DatabaseUnavailableException
                || cause instanceof java.sql.SQLException) {
            plugin.getLogger().log(Level.WARNING,
                    context + " for " + player.getUniqueId(), cause);
            sendProcessingFailure(player, ProcessingFailureReason.DATABASE_UNAVAILABLE);
            return;
        }
        plugin.getLogger().log(Level.WARNING,
                context + " for " + player.getUniqueId(), cause);
        sendProcessingFailure(player, ProcessingFailureReason.DATABASE_UNAVAILABLE);
    }

    private void sendProcessingFailure(Player player, ProcessingFailureReason reason) {
        player.sendMessage(translations.component(player, reason.translationKey()));
    }

    private void fail(Player player, String context, Throwable failure) {
        Throwable cause = unwrap(failure);
        plugin.getLogger().log(Level.WARNING, context + " for " + player.getUniqueId(), cause);
        player.sendMessage(translations.component(player, "errors.database-unavailable"));
    }

    private Throwable unwrap(Throwable failure) {
        if (failure == null) {
            return new IllegalStateException("Unknown processing failure");
        }
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private CompletableFuture<Void> onMain(Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            try {
                action.run();
                return CompletableFuture.completedFuture(null);
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    action.run();
                    future.complete(null);
                } catch (Throwable failure) {
                    future.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException failure) {
            future.completeExceptionally(failure);
        }
        return future;
    }

    private void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Processing world operations require the main thread");
        }
    }

    private record LoadedJobs(List<ProcessJob> drying, List<ProcessJob> curing) {
    }

    public record Station(
            UUID stationId,
            ProcessingStationType type,
            BlockKey position
    ) {
    }

    public record BlockKey(UUID worldUuid, int x, int y, int z) {
        public static BlockKey of(Location location) {
            return new BlockKey(
                    Objects.requireNonNull(location.getWorld()).getUID(),
                    location.getBlockX(),
                    location.getBlockY(),
                    location.getBlockZ()
            );
        }

        public Location location() {
            World world = Bukkit.getWorld(worldUuid);
            return world == null ? null : new Location(world, x, y, z);
        }
    }

    private record ProcessJob(
            UUID jobId,
            UUID stationId,
            UUID batchId,
            UUID ownerUuid,
            ProcessingStationType type,
            boolean ready
    ) {
        private ProcessJob withReady(boolean value) {
            return new ProcessJob(jobId, stationId, batchId, ownerUuid, type, value);
        }
    }

    public enum StationStatus {
        FREE,
        PROCESSING,
        READY
    }

    public record StationSnapshot(
            Station station,
            StationStatus status,
            Optional<UUID> batchId,
            Optional<UUID> ownerUuid,
            Optional<ProductBatch> batch,
            long remainingSeconds,
            boolean exists
    ) {
        public StationSnapshot {
            Objects.requireNonNull(station, "station");
            Objects.requireNonNull(status, "status");
            batchId = Objects.requireNonNull(batchId, "batchId");
            ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
            batch = Objects.requireNonNull(batch, "batch");
            remainingSeconds = Math.max(0L, remainingSeconds);
        }
    }

    public enum StationBreakStatus {
        NOT_STATION,
        BUSY,
        CLAIMED
    }

    public record StationBreakResult(
            StationBreakStatus status,
            Station station,
            StationBreakGuard.Lease<?> lease
    ) implements AutoCloseable {
        public StationBreakResult {
            Objects.requireNonNull(status, "status");
        }

        public Optional<Station> optionalStation() {
            return Optional.ofNullable(station);
        }

        @Override
        public void close() {
            if (lease != null) {
                lease.close();
            }
        }
    }

    private record RollingValidatedPair(
            SecureItemService.ValidatedItem paper,
            SecureItemService.ValidatedItem filter
    ) {
    }

    private record RollingValidatedInputs(
            SecureItemService.ValidatedItem paper,
            SecureItemService.ValidatedItem filter,
            SecureItemService.ValidatedItem product
    ) {
    }

    private record LocatedItem(int slot, ItemStack item, SignedItemData data) {
    }

    private static final class RollingSession {
        private final UUID sessionId;
        private final UUID playerId;
        private final ProductBatch source;
        private final CustomItemType sourceType;
        private final List<Integer> slots;
        private final List<RollingStep> steps;
        private final List<Long> errors;
        private int step;
        private long stepStartedAt;
        private boolean completing;

        private RollingSession(
                UUID sessionId,
                UUID playerId,
                ProductBatch source,
                CustomItemType sourceType,
                List<Integer> slots,
                List<RollingStep> steps,
                List<Long> errors,
                int step,
                long stepStartedAt,
                boolean completing
        ) {
            this.sessionId = sessionId;
            this.playerId = playerId;
            this.source = source;
            this.sourceType = sourceType;
            this.slots = slots;
            this.steps = steps;
            this.errors = errors;
            this.step = step;
            this.stepStartedAt = stepStartedAt;
            this.completing = completing;
        }

        private UUID sessionId() {
            return sessionId;
        }

        private UUID playerId() {
            return playerId;
        }

        private ProductBatch source() {
            return source;
        }

        private CustomItemType sourceType() {
            return sourceType;
        }

        private List<Integer> slots() {
            return slots;
        }

        private List<RollingStep> steps() {
            return steps;
        }

        private List<Long> errors() {
            return errors;
        }

        private int step() {
            return step;
        }

        private void step(int value) {
            step = value;
        }

        private long stepStartedAt() {
            return stepStartedAt;
        }

        private void stepStartedAt(long value) {
            stepStartedAt = value;
        }

        private boolean completing() {
            return completing;
        }

        private void completing(boolean value) {
            completing = value;
        }
    }
}
