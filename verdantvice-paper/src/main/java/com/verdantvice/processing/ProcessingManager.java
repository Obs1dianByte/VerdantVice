package com.verdantvice.processing;

import com.verdantvice.api.event.VerdantBatchCureEvent;
import com.verdantvice.api.event.VerdantBatchDryEvent;
import com.verdantvice.config.ConfigManager;
import com.verdantvice.database.DatabaseManager;
import com.verdantvice.i18n.TranslationService;
import com.verdantvice.notification.NotificationService;
import com.verdantvice.notification.NotificationType;
import com.verdantvice.security.CustomItemType;
import com.verdantvice.security.ItemSigner;
import com.verdantvice.security.SecureItemService;
import com.verdantvice.security.SignedItemData;
import com.verdantvice.storage.BatchRepository;
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

    private final JavaPlugin plugin;
    private final ConfigManager configs;
    private final TranslationService translations;
    private final NotificationService notifications;
    private final DatabaseManager database;
    private final BatchRepository batchRepository;
    private final SecureItemService secureItems;
    private final ItemSigner signer;
    private final byte[] signingSecret;
    private final DryingService drying = new DryingService();
    private final CuringService curing = new CuringService();
    private final GrindingService grinding = new GrindingService();
    private final MixtureService mixing = new MixtureService();
    private final BatchAuthenticityService authenticity = new BatchAuthenticityService();
    private final Map<UUID, ProductBatch> batches = new ConcurrentHashMap<>();
    private final Map<UUID, ProcessJob> jobs = new ConcurrentHashMap<>();
    private final Map<BlockKey, Station> stations = new ConcurrentHashMap<>();
    private final Set<UUID> pendingStations = ConcurrentHashMap.newKeySet();
    private final Map<UUID, RollingSession> rollingSessions = new ConcurrentHashMap<>();
    private final File stationFile;
    private BukkitTask updateTask;

    public ProcessingManager(
            JavaPlugin plugin,
            ConfigManager configs,
            TranslationService translations,
            NotificationService notifications,
            DatabaseManager database,
            BatchRepository batchRepository,
            SecureItemService secureItems,
            ItemSigner signer,
            byte[] signingSecret
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.translations = Objects.requireNonNull(translations, "translations");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        this.database = Objects.requireNonNull(database, "database");
        this.batchRepository = Objects.requireNonNull(batchRepository, "batchRepository");
        this.secureItems = Objects.requireNonNull(secureItems, "secureItems");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.signingSecret = Objects.requireNonNull(signingSecret, "signingSecret").clone();
        this.stationFile = configs.dataPath("data/processing-stations.yml").toFile();
    }

    public CompletableFuture<Void> load() {
        loadStations();
        CompletableFuture<List<ProductBatch>> batchFuture = batchRepository.loadAll();
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
        return CompletableFuture.allOf(batchFuture, dryingFuture, curingFuture)
                .thenCompose(ignored -> onMain(() -> {
                    batches.clear();
                    batchFuture.join().forEach(batch -> batches.put(batch.batchId(), batch));
                    jobs.clear();
                    dryingFuture.join().forEach(job -> jobs.put(job.stationId(), job));
                    curingFuture.join().forEach(job -> jobs.put(job.stationId(), job));
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

    public boolean canBreak(Block block) {
        Station station = stations.get(BlockKey.of(block.getLocation()));
        return station == null || !jobs.containsKey(station.stationId());
    }

    public void removeStation(Block block) {
        requireMainThread();
        Station removed = stations.remove(BlockKey.of(block.getLocation()));
        if (removed != null) {
            saveStations();
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
            if (jobs.containsKey(station.stationId())) {
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
        String firstOperation = "mix:" + mixed.batchId() + ":1";
        String secondOperation = "mix:" + mixed.batchId() + ":2";
        return secureItems.consume(
                first,
                GROUND,
                player.getUniqueId(),
                firstOperation
        ).thenCompose(consumed -> secureItems.consume(
                second,
                GROUND,
                player.getUniqueId(),
                secondOperation
        )).thenCompose(consumed -> batchRepository.create(mixed))
                .thenCompose(ignored -> markSourceConsumed(firstBatch))
                .thenCompose(ignored -> markSourceConsumed(secondBatch))
                .thenCompose(ignored -> secureItems.issue(
                        player,
                        CustomItemType.MIXTURE,
                        player.getUniqueId(),
                        mixed.batchId(),
                        mixed.strainComposition().keySet().stream().sorted().findFirst().orElse(""),
                        mixed.qualityScore()
                ))
                .thenCompose(item -> onMain(() -> {
                    removeInstance(player, firstData.uniqueInstanceId());
                    removeInstance(player, secondData.uniqueInstanceId());
                    batches.put(mixed.batchId(), mixed);
                    giveOrDrop(player, item);
                }));
    }

    public CompletableFuture<Void> startRolling(Player player) {
        requireMainThread();
        if (rollingSessions.containsKey(player.getUniqueId())) {
            return CompletableFuture.failedFuture(new IllegalStateException("busy"));
        }
        Inventory inventory = player.getInventory();
        LocatedItem paper = find(inventory, CustomItemType.ROLLING_PAPER);
        LocatedItem filter = find(inventory, CustomItemType.FILTER);
        LocatedItem product = find(inventory, CustomItemType.MIXTURE, CustomItemType.GROUND_PRODUCT);
        if (paper == null || filter == null || product == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("insufficient_items"));
        }
        SignedItemData productData = product.data();
        ProductBatch source = batches.get(productData.batchId());
        if (source == null || (source.state() != BatchState.MIXED && source.state() != BatchState.GROUND)
                || !authenticity.verify(source, signingSecret)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("wrong_item_state"));
        }
        UUID sessionId = UUID.randomUUID();
        return secureItems.consume(
                paper.item(),
                Set.of(CustomItemType.ROLLING_PAPER),
                player.getUniqueId(),
                "roll:" + sessionId + ":paper"
        ).thenCompose(ignored -> secureItems.consume(
                filter.item(),
                Set.of(CustomItemType.FILTER),
                player.getUniqueId(),
                "roll:" + sessionId + ":filter"
        )).thenCompose(ignored -> secureItems.consume(
                product.item(),
                Set.of(product.data().itemType()),
                player.getUniqueId(),
                "roll:" + sessionId + ":product"
        )).thenCompose(ignored -> onMain(() -> {
            removeInstance(player, paper.data().uniqueInstanceId());
            removeInstance(player, filter.data().uniqueInstanceId());
            removeInstance(player, product.data().uniqueInstanceId());
            beginRollingGui(player, sessionId, source, product.data().itemType());
        }));
    }

    public void handleRollingClick(Player player, RollingMenuHolder holder, int rawSlot) {
        requireMainThread();
        RollingSession session = rollingSessions.get(player.getUniqueId());
        if (session == null || !session.sessionId().equals(holder.sessionId())) {
            player.closeInventory();
            return;
        }
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
        return Optional.ofNullable(batches.get(id));
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
        ProductBatch source = batches.get(data.batchId());
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
        return secureItems.consume(
                item.clone(),
                Set.of(itemType),
                player.getUniqueId(),
                "admin-quality:" + UUID.randomUUID()
        ).thenCompose(ignored -> batchRepository.save(updated))
                .thenCompose(ignored -> secureItems.issue(
                        player,
                        itemType,
                        data.ownerUuid(),
                        updated.batchId(),
                        dominantStrain(updated),
                        score
                ))
                .thenCompose(replacement -> onMain(() -> {
                    removeInstance(player, data.uniqueInstanceId());
                    batches.put(updated.batchId(), updated);
                    giveOrDrop(player, replacement);
                }));
    }

    private void startDrying(Player player, Station station) {
        ItemStack input = player.getInventory().getItemInMainHand().clone();
        ItemSigner.Verification verification = signer.verify(input);
        if (!verification.valid() || verification.data().itemType() != CustomItemType.FRESH_BATCH) {
            player.sendMessage(translations.component(player, "errors.wrong-item-state"));
            return;
        }
        SignedItemData data = verification.data();
        ProductBatch source;
        try {
            source = requireBatch(data, BatchState.FRESH);
        } catch (RuntimeException failure) {
            player.sendMessage(translations.component(player, "errors.wrong-item-state"));
            return;
        }
        if (!pendingStations.add(station.stationId())) {
            player.sendMessage(translations.component(player, "errors.busy"));
            return;
        }
        Instant now = Instant.now();
        YamlConfiguration config = quality();
        ProductBatch started = authenticity.sign(drying.start(
                source,
                now,
                Duration.ofMinutes(config.getLong(
                        "processing.drying.duration-minutes",
                        20L
                )),
                config.getDouble("processing.drying.cleanliness", 92.0),
                config.getDouble("processing.drying.stability", 90.0),
                config.getDouble("processing.drying.speed", 1.0)
        ), signingSecret);
        ProcessJob job = new ProcessJob(
                UUID.randomUUID(),
                station.stationId(),
                started.batchId(),
                player.getUniqueId(),
                ProcessingStationType.DRYING_RACK,
                false
        );
        secureItems.consume(
                input,
                FRESH,
                player.getUniqueId(),
                "dry:" + job.jobId()
        ).thenCompose(ignored -> batchRepository.save(started))
                .thenCompose(ignored -> insertJob(job, started))
                .whenComplete((ignored, failure) -> onMain(() -> {
                    pendingStations.remove(station.stationId());
                    if (failure != null) {
                        fail(player, "Could not start drying", failure);
                        return;
                    }
                    removeInstance(player, data.uniqueInstanceId());
                    batches.put(started.batchId(), started);
                    jobs.put(station.stationId(), job);
                    player.sendMessage(translations.component(
                            player,
                            "drying.loaded",
                            Map.of("batch_id", started.batchId())
                    ));
                    open(player, station);
                }));
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
        secureItems.consume(
                input,
                DRIED,
                player.getUniqueId(),
                "cure:" + job.jobId()
        ).thenCompose(ignored -> batchRepository.save(started))
                .thenCompose(ignored -> insertJob(job, started))
                .whenComplete((ignored, failure) -> onMain(() -> {
                    pendingStations.remove(station.stationId());
                    if (failure != null) {
                        fail(player, "Could not start curing", failure);
                        return;
                    }
                    removeInstance(player, data.uniqueInstanceId());
                    batches.put(started.batchId(), started);
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
        ProductBatch source = batches.get(data.batchId());
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
        secureItems.consume(
                input,
                GRINDABLE,
                player.getUniqueId(),
                "grind:" + ground.productId()
        ).thenCompose(ignored -> batchRepository.save(transformed))
                .thenCompose(ignored -> secureItems.issue(
                        player,
                        CustomItemType.GROUND_PRODUCT,
                        player.getUniqueId(),
                        transformed.batchId(),
                        dominantStrain(transformed),
                        transformed.qualityScore()
                ))
                .whenComplete((output, failure) -> onMain(() -> {
                    if (failure != null) {
                        fail(player, "Could not grind batch", failure);
                        return;
                    }
                    removeInstance(player, data.uniqueInstanceId());
                    batches.put(transformed.batchId(), transformed);
                    giveOrDrop(player, output);
                    open(player, station);
                }));
    }

    private void retrieve(Player player, Station station) {
        ProcessJob job = jobs.get(station.stationId());
        if (job == null) {
            player.sendMessage(translations.component(player, "errors.target-not-found"));
            return;
        }
        if (!job.ownerUuid().equals(player.getUniqueId())
                && !player.hasPermission("verdantvice.admin")) {
            player.sendMessage(translations.component(player, "errors.wrong-owner"));
            return;
        }
        updateOne(job, true).whenComplete((updated, failure) -> onMain(() -> {
            if (failure != null || updated == null) {
                fail(player, "Could not update process", failure);
                return;
            }
            boolean ready = job.type() == ProcessingStationType.DRYING_RACK
                    ? updated.state() == BatchState.DRIED || updated.state() == BatchState.SPOILED
                    : updated.state() == BatchState.CURED || updated.state() == BatchState.SPOILED;
            if (!ready) {
                player.sendMessage(translations.component(player, "errors.wrong-item-state"));
                open(player, station);
                return;
            }
            CustomItemType outputType = updated.state() == BatchState.SPOILED
                    ? CustomItemType.DRY_BATCH
                    : job.type() == ProcessingStationType.DRYING_RACK
                    ? CustomItemType.DRY_BATCH
                    : CustomItemType.CURED_BATCH;
            secureItems.issue(
                    player,
                    outputType,
                    player.getUniqueId(),
                    updated.batchId(),
                    dominantStrain(updated),
                    updated.qualityScore()
            ).thenCompose(item -> deleteJob(job).thenApply(ignored -> item))
                    .whenComplete((item, deliveryFailure) -> onMain(() -> {
                        if (deliveryFailure != null) {
                            fail(player, "Could not retrieve process", deliveryFailure);
                            return;
                        }
                        jobs.remove(station.stationId());
                        giveOrDrop(player, item);
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
        ProductBatch source = batches.get(job.batchId());
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
        return batchRepository.save(finalUpdated)
                .thenCompose(ignored -> updateJob(job, finalUpdated, ready))
                .thenCompose(ignored -> onMain(() -> {
                    batches.put(finalUpdated.batchId(), finalUpdated);
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

    private CompletableFuture<Void> insertJob(ProcessJob job, ProductBatch batch) {
        if (job.type() == ProcessingStationType.DRYING_RACK) {
            DryingData data = batch.dryingData().orElseThrow();
            return database.update(
                    """
                    INSERT INTO drying_jobs (
                      drying_job_id, batch_id, owner_uuid, rack_id, drying_stage,
                      status, started_at, last_updated_at, completes_at,
                      process_data_json, row_version
                    ) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, '{}', 0)
                    """,
                    job.jobId(),
                    batch.batchId(),
                    job.ownerUuid(),
                    job.stationId(),
                    data.stage(),
                    data.startedAt(),
                    data.lastUpdatedAt(),
                    data.startedAt().plus(data.targetDuration())
            ).thenApply(ignored -> null);
        }
        CuringData data = batch.curingData().orElseThrow();
        return database.update(
                """
                INSERT INTO curing_jobs (
                  curing_job_id, batch_id, owner_uuid, container_id, status,
                  started_at, last_updated_at, completes_at,
                  process_data_json, row_version
                ) VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, '{}', 0)
                """,
                job.jobId(),
                batch.batchId(),
                job.ownerUuid(),
                job.stationId(),
                data.startedAt(),
                data.lastUpdatedAt(),
                data.startedAt().plus(data.targetDuration())
        ).thenApply(ignored -> null);
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

    private CompletableFuture<Void> deleteJob(ProcessJob job) {
        String table = job.type() == ProcessingStationType.DRYING_RACK
                ? "drying_jobs" : "curing_jobs";
        String id = job.type() == ProcessingStationType.DRYING_RACK
                ? "drying_job_id" : "curing_job_id";
        return database.update("DELETE FROM " + table + " WHERE " + id + "=?", job.jobId())
                .thenApply(ignored -> null);
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
        ProductBatch batch = batches.get(item.batchId());
        if (batch == null || batch.state() != expected
                || !batch.ownerUuid().equals(item.ownerUuid())
                || !authenticity.verify(batch, signingSecret)) {
            throw new IllegalStateException("wrong_item_state");
        }
        return batch;
    }

    private CompletableFuture<Void> markSourceConsumed(ProductBatch source) {
        ProductBatch consumed = authenticity.sign(source.transition(
                source.dryWeight(),
                source.qualityScore(),
                source.contamination(),
                source.freshness(),
                source.dryingData(),
                source.curingData(),
                BatchState.ROLLED
        ), signingSecret);
        return batchRepository.save(consumed).thenRun(() ->
                batches.put(consumed.batchId(), consumed));
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
        batchRepository.save(finished)
                .thenCompose(ignored -> secureItems.issue(
                        player,
                        CustomItemType.JOINT,
                        player.getUniqueId(),
                        finished.batchId(),
                        dominantStrain(finished),
                        Math.min(100.0, finished.qualityScore() * rolled.valueMultiplier())
                ))
                .whenComplete((item, failure) -> onMain(() -> {
                    rollingSessions.remove(player.getUniqueId());
                    player.closeInventory();
                    if (failure != null) {
                        fail(player, "Could not complete rolling", failure);
                        restoreRollingInputs(player, session);
                        return;
                    }
                    batches.put(finished.batchId(), finished);
                    giveOrDrop(player, item);
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
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            restoreRollingInputs(player, session);
            if (!shutdown) {
                player.sendMessage(translations.component(player, "rolling.aborted"));
            }
        }
    }

    private void restoreRollingInputs(Player player, RollingSession session) {
        List<CompletableFuture<ItemStack>> futures = List.of(
                secureItems.issue(
                        player,
                        CustomItemType.ROLLING_PAPER,
                        player.getUniqueId(),
                        ""
                ),
                secureItems.issue(
                        player,
                        CustomItemType.FILTER,
                        player.getUniqueId(),
                        ""
                ),
                secureItems.issue(
                        player,
                        session.sourceType(),
                        player.getUniqueId(),
                        session.source().batchId(),
                        dominantStrain(session.source()),
                        session.source().qualityScore()
                )
        );
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, failure) -> onMain(() -> {
                    if (failure != null) {
                        fail(player, "Could not restore rolling inputs", failure);
                        return;
                    }
                    futures.forEach(future -> giveOrDrop(player, future.join()));
                }));
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
        ProductBatch batch = batches.get(job.batchId());
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

    private void giveOrDrop(Player player, ItemStack item) {
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        overflow.values().forEach(stack ->
                player.getWorld().dropItemNaturally(player.getLocation(), stack));
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
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                action.run();
                future.complete(null);
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        });
        return future;
    }

    private void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Processing world operations require the main thread");
        }
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
