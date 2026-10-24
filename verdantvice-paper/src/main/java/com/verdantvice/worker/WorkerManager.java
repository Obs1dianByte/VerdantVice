package com.verdantvice.worker;

import com.verdantvice.api.event.VerdantWorkerHiredEvent;
import com.verdantvice.api.event.VerdantWorkerTaskEvent;
import com.verdantvice.common.BlockPosition;
import com.verdantvice.config.ConfigManager;
import com.verdantvice.database.DatabaseIntegrityException;
import com.verdantvice.database.DatabaseManager;
import com.verdantvice.farm.Farm;
import com.verdantvice.farm.FarmManager;
import com.verdantvice.farm.FarmPermission;
import com.verdantvice.farm.FarmStorage;
import com.verdantvice.farm.StorageType;
import com.verdantvice.herbalist.HerbalistConversionService;
import com.verdantvice.herbalist.HerbalistRecord;
import com.verdantvice.herbalist.HerbalistTier;
import com.verdantvice.i18n.TranslationService;
import com.verdantvice.text.ExternalNameSanitizer;
import com.verdantvice.notification.NotificationService;
import com.verdantvice.notification.NotificationType;
import com.verdantvice.plant.GrowthStage;
import com.verdantvice.plant.Plant;
import com.verdantvice.plant.PlantManager;
import com.verdantvice.security.CustomItemType;
import com.verdantvice.security.ItemSigner;
import com.verdantvice.security.SecureItemService;
import com.verdantvice.worker.WorkerPaymentRepository.PaymentRecord;
import com.verdantvice.worker.WorkerPaymentRepository.PaymentStatus;
import com.verdantvice.worker.WorkerTaskRepository.StoredTask;
import com.verdantvice.worker.WorkerTaskRepository.TaskLease;
import com.verdantvice.worker.ai.PaperWorkerPathfinder;
import com.verdantvice.worker.ai.WorkerActionService;
import com.verdantvice.worker.ai.WorkerStateMachine;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Paper facade for hiring, scheduling, navigation, task execution, salaries,
 * morale and worker lifecycle.
 */
public final class WorkerManager {
    private static final Set<CustomItemType> CONTRACT_ONLY =
            Set.of(CustomItemType.PLANTATION_CONTRACT);
    private static final Set<WorkerTaskType> CRITICAL_TASKS =
            EnumSet.of(
                    WorkerTaskType.SAVE_DYING_PLANTS,
                    WorkerTaskType.TREAT_DISEASE,
                    WorkerTaskType.WATER
            );

    private final JavaPlugin plugin;
    private final ConfigManager configs;
    private final TranslationService translations;
    private final FarmManager farms;
    private final PlantManager plants;
    private final HerbalistConversionService herbalists;
    private final SecureItemService secureItems;
    private final NotificationService notifications;
    private final WorkerRepository workers;
    private final WorkerTaskRepository tasks;
    private final WorkerPaymentRepository payments;
    private final WorkerRuntimeIntegrityService integrity;
    private final WorkerStateMachine stateMachine = new WorkerStateMachine();
    private final SalaryService salaryService = new SalaryService();
    private final MoraleService moraleService = new MoraleService();
    private final WorkerProgressionService progression = new WorkerProgressionService();
    private final WorkerSalaryAttemptPolicy salaryAttempts;
    private final PaperWorkerPathfinder pathfinder;
    private final WorkerActionService actions;
    private final WorkerListener listener;
    private final NamespacedKey workerIdKey;
    private final Map<UUID, RuntimeWorker> runtimes = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> workerByEntity = new ConcurrentHashMap<>();

    private BukkitTask tickTask;
    private long ticks;
    private boolean generatingTasks;

    public WorkerManager(
            JavaPlugin plugin,
            ConfigManager configs,
            TranslationService translations,
            FarmManager farms,
            PlantManager plants,
            HerbalistConversionService herbalists,
            SecureItemService secureItems,
            NotificationService notifications,
            WorkerRepository workers,
            WorkerTaskRepository tasks,
            WorkerPaymentRepository payments,
            WorkerRuntimeIntegrityService integrity,
            WorkerHarvestDeliveryRepository harvestDeliveries
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.translations = Objects.requireNonNull(translations, "translations");
        this.farms = Objects.requireNonNull(farms, "farms");
        this.plants = Objects.requireNonNull(plants, "plants");
        this.herbalists = Objects.requireNonNull(herbalists, "herbalists");
        this.secureItems = Objects.requireNonNull(secureItems, "secureItems");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        this.workers = Objects.requireNonNull(workers, "workers");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.payments = Objects.requireNonNull(payments, "payments");
        this.integrity = Objects.requireNonNull(integrity, "integrity");
        long initialSalaryBackoff = Math.max(
                1L,
                configs.get("workers.yml").getLong(
                        "salary.retry-initial-seconds", 5L));
        long maximumSalaryBackoff = Math.max(
                initialSalaryBackoff,
                configs.get("workers.yml").getLong(
                        "salary.retry-maximum-seconds", 60L));
        this.salaryAttempts = new WorkerSalaryAttemptPolicy(
                Duration.ofSeconds(initialSalaryBackoff),
                Duration.ofSeconds(maximumSalaryBackoff));
        this.pathfinder = new PaperWorkerPathfinder(plugin);
        this.actions = new WorkerActionService(
                plugin,
                configs,
                farms,
                plants,
                secureItems,
                Objects.requireNonNull(harvestDeliveries, "harvestDeliveries")
        );
        this.listener = new WorkerListener(this);
        this.workerIdKey = new NamespacedKey(plugin, "worker_id");
    }

    public static WorkerManager create(
            JavaPlugin plugin,
            ConfigManager configs,
            TranslationService translations,
            FarmManager farms,
            PlantManager plants,
            HerbalistConversionService herbalists,
            SecureItemService secureItems,
            NotificationService notifications,
            DatabaseManager database
    ) {
        return create(
                plugin, configs, translations, farms, plants, herbalists,
                secureItems, notifications, database, new WorkerRepository(database));
    }

    public static WorkerManager create(
            JavaPlugin plugin,
            ConfigManager configs,
            TranslationService translations,
            FarmManager farms,
            PlantManager plants,
            HerbalistConversionService herbalists,
            SecureItemService secureItems,
            NotificationService notifications,
            DatabaseManager database,
            WorkerRepository workerRepository
    ) {
        Objects.requireNonNull(database, "database");
        WorkerTaskRepository taskRepository = new WorkerTaskRepository(database);
        return new WorkerManager(
                plugin,
                configs,
                translations,
                farms,
                plants,
                herbalists,
                secureItems,
                notifications,
                Objects.requireNonNull(workerRepository, "workerRepository"),
                taskRepository,
                new WorkerPaymentRepository(database),
                new WorkerRuntimeIntegrityService(
                        database,
                        taskRepository,
                        plugin.getLogger()),
                new WorkerHarvestDeliveryRepository(database)
        );
    }

    public CompletableFuture<Void> load() {
        Instant now = Instant.now();
        return integrity.migrate(translations.serverLocale())
                .thenCompose(report -> workers.loadAll()
                        .thenCombine(
                                payments.pending(),
                                (records, pending) -> new WorkerLoad(
                                        records.stream()
                                                .filter(record -> !report.disabledWorkerIds()
                                                        .contains(record.worker().workerUuid()))
                                                .toList(),
                                        pending))
                        .thenCombine(
                                tasks.cleanupExpired(now),
                                (loaded, ignored) -> loaded))
                .thenCompose(loaded -> onMain(() -> {
                    runtimes.clear();
                    workerByEntity.clear();
                    for (WorkerRecord record : loaded.records()) {
                        RuntimeWorker runtime = new RuntimeWorker(record);
                        runtimes.put(record.worker().workerUuid(), runtime);
                        workerByEntity.put(record.entityUuid(), record.worker().workerUuid());
                        Entity entity = Bukkit.getEntity(record.entityUuid());
                        if (entity instanceof Villager villager) {
                            bind(runtime, villager);
                        }
                    }
                    loaded.pendingPayments().stream()
                            .sorted(Comparator
                                    .comparing(PaymentRecord::createdAt)
                                    .thenComparing(value -> value.paymentId().toString()))
                            .forEach(payment -> {
                                RuntimeWorker runtime = runtimes.get(payment.workerUuid());
                                if (runtime == null) {
                                    return;
                                }
                                long day = WorkerPaymentRepository.minecraftDay(
                                        payment.periodKey());
                                runtime.queueStartupPendingSalary(day);
                            });
                }));
    }

    public void start() {
        requireMainThread();
        if (tickTask != null) {
            return;
        }
        long period = Math.max(
                1L,
                configs.main().getLong("performance.worker-update-ticks", 20L)
        );
        actions.recoverPendingDeliveries();
        tickTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                () -> tick(),
                period,
                period
        );
    }

    public void attachProcessing(com.verdantvice.processing.ProcessingManager processing) {
        requireMainThread();
        actions.attachProcessing(processing);
    }

    public void stop() {
        requireMainThread();
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        for (RuntimeWorker runtime : runtimes.values()) {
            if (runtime.entity != null) {
                pathfinder.stop(runtime.entity);
            }
        }
    }

    public CompletableFuture<Void> flush() {
        Instant now = Instant.now();
        CompletableFuture<?>[] releases = runtimes.values().stream()
                .filter(runtime -> runtime.lease != null)
                .map(runtime -> tasks.releaseForWorker(
                        runtime.record.worker().workerUuid(),
                        now
                ))
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(releases);
    }

    public WorkerListener listener() {
        return listener;
    }

    public Collection<WorkerRecord> all() {
        return runtimes.values().stream()
                .map(runtime -> runtime.record)
                .sorted(Comparator.comparing(record ->
                        record.worker().workerUuid().toString()))
                .toList();
    }

    public List<WorkerRecord> workers(UUID farmId) {
        Objects.requireNonNull(farmId, "farmId");
        return runtimes.values().stream()
                .map(runtime -> runtime.record)
                .filter(record -> record.worker().farmId().equals(farmId))
                .sorted(Comparator.comparing(WorkerRecord::name))
                .toList();
    }

    public Optional<WorkerRecord> worker(UUID workerUuid) {
        RuntimeWorker runtime = runtimes.get(
                Objects.requireNonNull(workerUuid, "workerUuid")
        );
        return runtime == null ? Optional.empty() : Optional.of(runtime.record);
    }

    public List<WorkerSnapshot> snapshots(UUID farmId) {
        Objects.requireNonNull(farmId, "farmId");
        return runtimes.values().stream()
                .filter(runtime -> runtime.record.worker().farmId().equals(farmId))
                .map(this::snapshot)
                .sorted(Comparator.comparing(value -> value.record().name()))
                .toList();
    }

    private WorkerSnapshot snapshot(RuntimeWorker runtime) {
        Optional<WorkerTaskType> currentTask = runtime.lease == null
                ? Optional.empty()
                : Optional.of(runtime.lease.task().task().type());
        Optional<Integer> currentPriority = runtime.lease == null
                ? Optional.empty()
                : Optional.of(runtime.lease.task().task().priority());
        Optional<BlockPosition> position = runtime.entity != null
                && runtime.entity.isValid() && !runtime.entity.isDead()
                ? Optional.of(new BlockPosition(
                        runtime.entity.getWorld().getUID(),
                        runtime.entity.getLocation().getBlockX(),
                        runtime.entity.getLocation().getBlockY(),
                        runtime.entity.getLocation().getBlockZ()
                ))
                : runtime.record.lastKnownPosition();
        boolean entityAvailable = runtime.entity != null
                && runtime.entity.isValid()
                && !runtime.entity.isDead();
        return new WorkerSnapshot(
                runtime.record,
                currentTask,
                currentPriority,
                Optional.ofNullable(runtime.lastTaskType),
                position,
                entityAvailable,
                runtime.busy,
                runtime.blockedCount
        );
    }

    public boolean isWorker(Villager villager) {
        Objects.requireNonNull(villager, "villager");
        return workerByEntity.containsKey(villager.getUniqueId())
                || villager.getPersistentDataContainer()
                .has(workerIdKey, PersistentDataType.STRING);
    }

    public boolean isHiringContract(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        ItemSigner.Verification verification = secureItems.verify(item);
        return verification.valid()
                && verification.data().itemType()
                == CustomItemType.PLANTATION_CONTRACT;
    }

    public CompletableFuture<HireResult> hire(Player player, Villager villager) {
        requireMainThread();
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(villager, "villager");
        if (!player.hasPermission("verdantvice.worker.hire")
                && !player.hasPermission("verdantvice.admin")) {
            return failedHire(player, HireStatus.NO_PERMISSION);
        }
        if (!villager.isValid() || villager.isDead()) {
            return failedHire(player, HireStatus.INVALID_ENTITY);
        }
        ItemStack contract = player.getInventory().getItemInMainHand();
        ItemSigner.Verification verification = secureItems.verify(contract);
        if (!verification.valid()
                || verification.data().itemType()
                != CustomItemType.PLANTATION_CONTRACT) {
            return failedHire(player, HireStatus.CONTRACT_REQUIRED);
        }
        Optional<Farm> farm = selectFarm(player, villager);
        if (farm.isEmpty()) {
            return failedHire(player, HireStatus.NO_FARM);
        }
        if (workers(farm.get().farmId()).size() >= effectiveWorkerLimit(farm.get())) {
            return failedHire(player, HireStatus.WORKER_LIMIT);
        }
        if (isWorker(villager)) {
            return failedHire(player, HireStatus.ALREADY_EMPLOYED);
        }

        ItemStack contractSnapshot = contract.clone();
        UUID instanceId = verification.data().uniqueInstanceId();
        UUID operationWorkerId = UUID.randomUUID();
        return herbalists.resolve(villager)
                .thenCompose(optional -> onMainSupply(() -> {
                    HerbalistRecord herbalist = optional.orElse(null);
                    if (herbalist == null) {
                        return HireContext.failed(HireStatus.INVALID_ENTITY);
                    }
                    if (herbalist.tier() != HerbalistTier.VERDANT_EXPERT) {
                        return HireContext.failed(HireStatus.NOT_EXPERT);
                    }
                    if (herbalist.ownerUuid() != null
                            && !herbalist.ownerUuid().equals(player.getUniqueId())) {
                        return HireContext.failed(HireStatus.ALREADY_EMPLOYED);
                    }
                    VerdantWorkerHiredEvent event = new VerdantWorkerHiredEvent(
                            player,
                            villager,
                            farm.get().farmId()
                    );
                    Bukkit.getPluginManager().callEvent(event);
                    if (event.isCancelled()) {
                        return HireContext.failed(HireStatus.CANCELLED);
                    }
                    return new HireContext(
                            HireStatus.HIRED,
                            herbalist,
                            farm.get(),
                            blockPosition(villager.getLocation())
                    );
                }))
                .thenCompose(context -> {
                    if (context.status() != HireStatus.HIRED) {
                        return completedHire(context.status());
                    }
                    String operation = "hire-worker:"
                            + operationWorkerId + ':' + instanceId;
                    return onMainSupply(() -> secureItems.validateActive(
                                    contractSnapshot,
                                    CONTRACT_ONLY,
                                    player.getUniqueId()
                            )).thenCompose(future -> future)
                            .thenCompose(validated -> onMainSupply(() -> workers.hireHerbalist(
                                    context.herbalist(),
                                    player.getUniqueId(),
                                    context.farm().farmId(),
                                    workerName(player, villager),
                                    specialization(villager.getUniqueId()),
                                    Math.max(
                                            1,
                                            configs.get("workers.yml").getInt(
                                                    "salary.emeralds-per-minecraft-day",
                                                    4
                                            )
                                    ),
                                    minecraftDay(villager.getWorld()),
                                    WorkerSchedule.from(configs.get("workers.yml")),
                                    context.home(),
                                    effectiveWorkerLimit(context.farm()),
                                    translations.locale(player),
                                    Instant.now(),
                                    validated.identity(),
                                    secureItems,
                                    operation
                            )).thenCompose(future -> future))
                            .thenCompose(record -> onMainSupply(() -> {
                                if (!villager.isValid() || villager.isDead()) {
                                    throw new IllegalStateException("invalid_entity");
                                }
                                removeItemInstance(player, instanceId);
                                RuntimeWorker runtime = new RuntimeWorker(record);
                                runtimes.put(record.worker().workerUuid(), runtime);
                                workerByEntity.put(
                                        record.entityUuid(),
                                        record.worker().workerUuid()
                                );
                                bind(runtime, villager);
                                player.sendMessage(translations.component(
                                        player,
                                        "herbalist.hire.signed",
                                        Map.of(
                                                "worker", record.name(),
                                                "farm", context.farm().name()
                                        )
                                ));
                                return new HireResult(
                                        HireStatus.HIRED,
                                        Optional.of(record)
                                );
                            }));
                })
                .exceptionally(failure -> {
                    Throwable cause = unwrap(failure);
                    plugin.getLogger().log(
                            Level.WARNING,
                            "Worker hire failed for " + villager.getUniqueId(),
                            cause
                    );
                    return new HireResult(
                            hireFailure(cause),
                            Optional.empty()
                    );
                })
                .thenCompose(result -> onMainSupply(() -> {
                    if (result.status() != HireStatus.HIRED) {
                        sendHireFailure(player, result.status());
                    }
                    return result;
                }));
    }

    public CompletableFuture<Boolean> dismiss(Player actor, UUID workerUuid) {
        requireMainThread();
        Objects.requireNonNull(actor, "actor");
        RuntimeWorker runtime = runtimes.get(
                Objects.requireNonNull(workerUuid, "workerUuid")
        );
        if (runtime == null) {
            return CompletableFuture.completedFuture(false);
        }
        Farm farm = farms.get(runtime.record.worker().farmId()).orElse(null);
        if (farm == null
                || (!actor.hasPermission("verdantvice.admin")
                && !farm.can(actor.getUniqueId(), FarmPermission.MANAGE_WORKERS))) {
            return CompletableFuture.completedFuture(false);
        }
        return dismissRuntime(runtime, actor.getUniqueId(), false)
                .thenApply(removed -> {
                    if (removed) {
                        actor.sendMessage(translations.component(
                                actor,
                                "herbalist.hire.dismissed",
                                Map.of("worker", safeWorkerName(actor, runtime.record.name()))
                        ));
                    }
                    return removed;
                });
    }

    public CompletableFuture<StoredTask> submitTask(
            UUID farmId,
            WorkerTaskType type,
            TaskTarget target,
            Duration timeout,
            String idempotencyKey
    ) {
        Objects.requireNonNull(farmId, "farmId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(timeout, "timeout");
        if (farms.get(farmId).isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("unknown_farm")
            );
        }
        if (timeout.isZero() || timeout.isNegative()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Task timeout must be positive")
            );
        }
        Instant now = Instant.now();
        WorkerTask task = WorkerTask.queued(
                UUID.randomUUID(),
                type,
                priority(type),
                target,
                now,
                now.plus(timeout),
                maximumAttempts()
        );
        return tasks.createQueued(farmId, task, idempotencyKey);
    }

    public void bind(Villager villager) {
        requireMainThread();
        Objects.requireNonNull(villager, "villager");
        UUID workerId = workerByEntity.get(villager.getUniqueId());
        if (workerId == null) {
            String stored = villager.getPersistentDataContainer()
                    .get(workerIdKey, PersistentDataType.STRING);
            if (stored != null) {
                try {
                    workerId = UUID.fromString(stored);
                } catch (IllegalArgumentException ignored) {
                    villager.getPersistentDataContainer().remove(workerIdKey);
                }
            }
        }
        RuntimeWorker runtime = workerId == null ? null : runtimes.get(workerId);
        if (runtime != null && runtime.record.entityUuid()
                .equals(villager.getUniqueId())) {
            bind(runtime, villager);
        }
    }

    public void handleDeath(Villager villager) {
        requireMainThread();
        UUID workerId = workerByEntity.get(villager.getUniqueId());
        RuntimeWorker runtime = workerId == null ? null : runtimes.get(workerId);
        if (runtime == null) {
            return;
        }
        runtime.entity = null;
        runtime.coworkerDied = true;
        for (RuntimeWorker coworker : runtimes.values()) {
            if (coworker != runtime
                    && coworker.record.worker().farmId()
                    .equals(runtime.record.worker().farmId())) {
                coworker.coworkerDied = true;
            }
        }
        runtime.lease = null;
        runtime.navigation = null;
        tasks.releaseForWorker(workerId, Instant.now())
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        logAsync("Could not release dead worker task " + workerId, failure);
                    }
                });
        Worker blocked = copy(
                runtime.record.worker(),
                WorkerState.BLOCKED,
                Optional.empty()
        );
        persist(runtime, blocked, () -> {
            runtime.blockedSince = Instant.now();
            notifyBlocked(runtime, runtime.record.home().orElse(null));
        });
    }

    public boolean protectCareerChange(Villager villager) {
        return isWorker(villager);
    }

    private void tick() {
        requireMainThread();
        ticks++;
        if (ticks % 10L == 0L) {
            tasks.cleanupExpired(Instant.now()).exceptionally(failure -> {
                logAsync("Worker task cleanup failed", failure);
                return 0;
            });
        }
        if (ticks % 20L == 0L) {
            generatePlantTasks();
        }
        if (ticks % 100L == 0L) {
            actions.recoverPendingDeliveries();
        }
        List<RuntimeWorker> ordered = new ArrayList<>(runtimes.values());
        ordered.sort(Comparator.comparing(runtime ->
                runtime.record.worker().workerUuid().toString()));
        for (RuntimeWorker runtime : ordered) {
            try {
                tick(runtime);
            } catch (RuntimeException failure) {
                plugin.getLogger().log(
                        Level.WARNING,
                        "Worker tick failed for "
                                + runtime.record.worker().workerUuid(),
                        failure
                );
            }
        }
    }

    private void tick(RuntimeWorker runtime) {
        if (runtime.disabled || runtime.busy) {
            return;
        }
        Villager entity = runtime.entity;
        if (entity == null || !entity.isValid() || entity.isDead()) {
            Entity loaded = Bukkit.getEntity(runtime.record.entityUuid());
            if (loaded instanceof Villager villager
                    && villager.isValid() && !villager.isDead()) {
                bind(runtime, villager);
                entity = villager;
            } else {
                runtime.entity = null;
                return;
            }
        }
        long day = minecraftDay(entity.getWorld());
        Instant now = Instant.now();
        Long pendingSalaryDay = runtime.pendingSalaryDays.peekFirst();
        if (runtime.lease == null && pendingSalaryDay != null
                && !runtime.salaryInFlight
                && !now.isBefore(runtime.salaryRetryAt)) {
            processSalary(runtime, pendingSalaryDay);
            return;
        }
        if (runtime.lease == null && salaryAttempts.shouldAttempt(
                runtime.lastSalaryProcessedDay,
                day,
                runtime.salaryInFlight,
                runtime.salaryRetryAt,
                now)) {
            processSalary(runtime, day);
            return;
        }
        renewLeaseIfNeeded(runtime);
        if (runtime.busy) {
            return;
        }

        Worker worker = runtime.record.worker();
        boolean atHome = atHome(runtime);
        WorkerStateMachine.ScheduleDirective directive = stateMachine.schedule(
                runtime.record.schedule(),
                entity.getWorld().getTime(),
                runtime.lease != null,
                atHome
        );
        Optional<WorkerState> scheduled = stateMachine.scheduledTransition(
                worker.state(),
                directive,
                runtime.lease != null,
                atHome,
                worker.unpaidDays() < refusalDays());
        if (scheduled.isPresent()) {
            if (scheduled.get() == WorkerState.RESTING) {
                runtime.receivedBreak = true;
            }
            transition(runtime, scheduled.get());
            return;
        }

        switch (worker.state()) {
            case IDLE, RESTING, SLEEPING -> {
                // Schedule transitions are handled above.
            }
            case SEARCHING_TASK -> searchTask(runtime);
            case RESERVING_TASK -> startTask(runtime);
            case WALKING_TO_STORAGE -> walkToStorage(runtime);
            case COLLECTING_RESOURCE -> collectResource(runtime);
            case WALKING_TO_TARGET -> walkToTarget(runtime);
            case INSPECTING_PLANT, WATERING, TREATING, PLANTING, HARVESTING,
                    MOVING_BATCH, LOADING_DRYER, UNLOADING_DRYER, PACKAGING ->
                    executeAction(runtime);
            case RETURNING_HOME -> returnHome(runtime);
            case WAITING_FOR_PAYMENT -> {
                if (!atHome) {
                    runtime.record.home()
                            .flatMap(this::location)
                            .ifPresent(home -> navigate(
                                    runtime,
                                    home,
                                    WorkerState.WAITING_FOR_PAYMENT));
                }
            }
            case BLOCKED -> handleBlocked(runtime);
        }
    }

    private void searchTask(RuntimeWorker runtime) {
        runtime.busy = true;
        Worker worker = runtime.record.worker();
        tasks.reserveNext(
                        worker.farmId(),
                        worker.workerUuid(),
                        allowedTasks(worker),
                        Instant.now(),
                        reservationDuration()
                )
                .whenComplete((optional, failure) -> onMainNow(() -> {
                    runtime.busy = false;
                    if (failure != null) {
                        logAsync("Task reservation failed", failure);
                        return;
                    }
                    if (optional.isEmpty()) {
                        transition(runtime, WorkerState.IDLE);
                        return;
                    }
                    runtime.lease = optional.get();
                    runtime.pathAttempts = 0;
                    Worker reserved = copy(
                            runtime.record.worker(),
                            WorkerState.RESERVING_TASK,
                            Optional.of(optional.get().task().task().taskId())
                    );
                    persist(runtime, reserved, () -> notifyTask(
                            runtime,
                            "worker.task.reserved",
                            optional.get().task().task(),
                            null
                    ));
                }));
    }

    private void startTask(RuntimeWorker runtime) {
        TaskLease lease = runtime.lease;
        if (lease == null) {
            transition(runtime, WorkerState.SEARCHING_TASK);
            return;
        }
        runtime.busy = true;
        tasks.start(lease, Instant.now())
                .whenComplete((started, failure) -> onMainNow(() -> {
                    runtime.busy = false;
                    if (failure != null) {
                        failActive(runtime, "reservation-expired");
                        return;
                    }
                    runtime.lease = started;
                    transition(
                            runtime,
                            stateMachine.afterReservation(
                                    started.task().task().type()
                            )
                    );
                }));
    }

    private void walkToStorage(RuntimeWorker runtime) {
        TaskLease lease = runtime.lease;
        if (lease == null) {
            transition(runtime, WorkerState.SEARCHING_TASK);
            return;
        }
        Optional<FarmStorage> storage = actions.requiredStorage(
                runtime.record.worker().farmId(),
                lease.task().task().type()
        );
        Optional<Location> target = storage.flatMap(actions::storageLocation);
        if (target.isEmpty()) {
            failActive(runtime, "missing-resource");
            return;
        }
        navigate(runtime, target.get(), WorkerState.COLLECTING_RESOURCE);
    }

    private void collectResource(RuntimeWorker runtime) {
        if (runtime.lease == null) {
            transition(runtime, WorkerState.SEARCHING_TASK);
            return;
        }
        if (!actions.hasRequiredResource(
                runtime.record.worker().farmId(),
                runtime.lease.task().task()
        )) {
            failActive(runtime, "missing-resource");
            return;
        }
        transition(runtime, WorkerState.WALKING_TO_TARGET);
    }

    private void walkToTarget(RuntimeWorker runtime) {
        if (runtime.lease == null) {
            transition(runtime, WorkerState.SEARCHING_TASK);
            return;
        }
        Optional<Location> target = actions.targetLocation(
                runtime.lease.task().task()
        );
        if (target.isEmpty()) {
            failActive(runtime, "target-unavailable");
            return;
        }
        navigate(
                runtime,
                target.get(),
                stateMachine.actionState(runtime.lease.task().task().type())
        );
    }

    private void executeAction(RuntimeWorker runtime) {
        TaskLease lease = runtime.lease;
        if (lease == null) {
            transition(runtime, WorkerState.SEARCHING_TASK);
            return;
        }
        runtime.busy = true;
        actions.execute(runtime.record, lease)
                .whenComplete((result, failure) -> onMainNow(() -> {
                    runtime.busy = false;
                    if (failure != null || result == null || !result.success()) {
                        failActive(
                                runtime,
                                result == null
                                        ? "target-unavailable"
                                        : result.reason()
                        );
                        return;
                    }
                    completeActive(runtime, result);
                }));
    }

    private void completeActive(
            RuntimeWorker runtime,
            WorkerActionService.ActionResult result
    ) {
        TaskLease lease = runtime.lease;
        if (lease == null) {
            return;
        }
        runtime.busy = true;
        tasks.complete(lease, Instant.now())
                .whenComplete((completed, failure) -> onMainNow(() -> {
                    runtime.busy = false;
                    if (failure != null) {
                        failActive(runtime, "reservation-expired");
                        return;
                    }
                    WorkerTask task = completed.task().task();
                    runtime.lastTaskType = task.type();
                    runtime.lease = null;
                    runtime.navigation = null;
                    runtime.pathAttempts = 0;
                    Worker progressed = progression.grantExperience(
                            runtime.record.worker(),
                            result.experience()
                    );
                    Worker updated = copy(
                            progressed,
                            nextAvailableState(runtime),
                            Optional.empty()
                    );
                    persist(runtime, updated, () -> {
                        notifyTask(
                                runtime,
                                "worker.task.completed",
                                task,
                                null
                        );
                        Bukkit.getPluginManager().callEvent(
                                new VerdantWorkerTaskEvent(
                                        updated.workerUuid(),
                                        task.taskId(),
                                        updated.state()
                                )
                        );
                    });
                }));
    }

    private void failActive(RuntimeWorker runtime, String reason) {
        TaskLease lease = runtime.lease;
        if (lease == null || runtime.busy) {
            return;
        }
        runtime.busy = true;
        tasks.failAttempt(lease, Instant.now())
                .whenComplete((failed, failure) -> onMainNow(() -> {
                    runtime.busy = false;
                    WorkerTask task = lease.task().task();
                    if (failure != null) {
                        logAsync("Could not record worker task failure", failure);
                        tasks.releaseForWorker(
                                runtime.record.worker().workerUuid(),
                                Instant.now()
                        );
                        runtime.lease = null;
                        transition(runtime, WorkerState.SEARCHING_TASK);
                        return;
                    }
                    WorkerTask result = failed.task().task();
                    runtime.lease = null;
                    runtime.navigation = null;
                    boolean blocked =
                            result.status() == WorkerTaskStatus.BLOCKED;
                    if (blocked) {
                        runtime.blockedCount++;
                        runtime.blockedSince = Instant.now();
                    }
                    Worker updated = copy(
                            runtime.record.worker(),
                            blocked
                                    ? WorkerState.BLOCKED
                                    : WorkerState.SEARCHING_TASK,
                            Optional.empty()
                    );
                    persist(runtime, updated, () -> {
                        notifyTask(
                                runtime,
                                blocked
                                        ? "worker.task.blocked"
                                        : "worker.task.failed",
                                task,
                                reason
                        );
                        Bukkit.getPluginManager().callEvent(
                                new VerdantWorkerTaskEvent(
                                        updated.workerUuid(),
                                        task.taskId(),
                                        updated.state()
                                )
                        );
                    });
                }));
    }

    private void blockActive(RuntimeWorker runtime, Location location) {
        TaskLease lease = runtime.lease;
        if (lease == null || runtime.busy) {
            transition(runtime, WorkerState.BLOCKED);
            return;
        }
        runtime.busy = true;
        tasks.markBlocked(lease, Instant.now())
                .whenComplete((blocked, failure) -> onMainNow(() -> {
                    runtime.busy = false;
                    if (failure != null) {
                        logAsync("Could not mark worker task blocked", failure);
                    }
                    runtime.lease = null;
                    runtime.navigation = null;
                    runtime.blockedCount++;
                    runtime.blockedSince = Instant.now();
                    Worker updated = copy(
                            runtime.record.worker(),
                            WorkerState.BLOCKED,
                            Optional.empty()
                    );
                    persist(runtime, updated, () ->
                            notifyBlocked(runtime, blockPosition(location)));
                }));
    }

    private void returnHome(RuntimeWorker runtime) {
        Optional<Location> home = runtime.record.home().flatMap(this::location);
        if (home.isEmpty()) {
            transition(runtime, WorkerState.BLOCKED);
            runtime.blockedSince = Instant.now();
            return;
        }
        WorkerSchedule.Phase phase = runtime.record.schedule().phase(
                runtime.entity.getWorld().getTime()
        );
        WorkerState arrived = switch (phase) {
            case SLEEP -> WorkerState.SLEEPING;
            case BREAK, OFF_DUTY -> WorkerState.RESTING;
            case WORK -> WorkerState.SEARCHING_TASK;
        };
        navigate(runtime, home.get(), arrived);
    }

    private void navigate(
            RuntimeWorker runtime,
            Location target,
            WorkerState arrivedState
    ) {
        Villager entity = runtime.entity;
        if (entity == null) {
            return;
        }
        BlockPosition requestedTarget = blockPosition(target);
        if (runtime.navigation != null && !requestedTarget.equals(runtime.navigationTarget)) {
            pathfinder.stop(entity);
            runtime.navigation = null;
            runtime.pathAttempts = 0;
        }
        if (runtime.navigation == null) {
            runtime.navigationTarget = requestedTarget;
            double speed = Math.max(
                    0.25,
                    Math.min(
                            1.5,
                            0.55 * progression.taskEfficiency(
                                    runtime.record.worker(),
                                    runtime.lease == null
                                            ? WorkerTaskType.INSPECT
                                            : runtime.lease.task().task().type()
                            )
                    )
            );
            runtime.navigation = pathfinder.begin(
                    entity,
                    target,
                    speed,
                    pathTimeout()
            ).orElse(null);
            if (runtime.navigation == null) {
                pathFailed(runtime, target);
            }
            return;
        }
        PaperWorkerPathfinder.NavigationStatus status =
                pathfinder.poll(entity, runtime.navigation);
        switch (status) {
            case MOVING -> {
                return;
            }
            case ARRIVED -> {
                runtime.navigation = null;
                runtime.pathAttempts = 0;
                transition(runtime, arrivedState);
            }
            case NO_PATH, TIMED_OUT, TARGET_UNAVAILABLE -> {
                runtime.navigation = null;
                pathFailed(runtime, target);
            }
        }
    }

    private void pathFailed(RuntimeWorker runtime, Location target) {
        runtime.pathAttempts++;
        if (runtime.pathAttempts <= maximumAttempts()) {
            return;
        }
        blockActive(runtime, target);
    }

    private void handleBlocked(RuntimeWorker runtime) {
        if (runtime.blockedSince == null) {
            runtime.blockedSince = Instant.now();
            return;
        }
        if (!configs.main().getBoolean(
                "performance.emergency-worker-teleport",
                false
        )) {
            return;
        }
        long seconds = Math.max(
                60L,
                configs.main().getLong(
                        "performance.emergency-worker-teleport-seconds",
                        300L
                )
        );
        if (Duration.between(runtime.blockedSince, Instant.now()).toSeconds()
                < seconds) {
            return;
        }
        Optional<Location> home = runtime.record.home().flatMap(this::location);
        if (home.isPresent()
                && pathfinder.emergencyMove(runtime.entity, home.get())) {
            runtime.blockedSince = null;
            runtime.pathAttempts = 0;
            transition(runtime, WorkerState.IDLE);
        }
    }

    private void renewLeaseIfNeeded(RuntimeWorker runtime) {
        TaskLease lease = runtime.lease;
        if (lease == null
                || lease.task().task().reservation().isEmpty()
                || runtime.busy) {
            return;
        }
        Instant expires = lease.task().task().reservation().get().expiresAt();
        if (Instant.now().plusSeconds(10L).isBefore(expires)) {
            return;
        }
        runtime.busy = true;
        tasks.renew(lease, Instant.now(), reservationDuration())
                .whenComplete((renewed, failure) -> onMainNow(() -> {
                    runtime.busy = false;
                    if (failure != null) {
                        failActive(runtime, "reservation-expired");
                    } else {
                        runtime.lease = renewed;
                    }
                }));
    }

    private void transition(RuntimeWorker runtime, WorkerState requested) {
        if (runtime.busy || runtime.disabled) {
            return;
        }
        Worker current = runtime.record.worker();
        final WorkerState next;
        try {
            next = stateMachine.transition(current.state(), requested);
        } catch (IllegalStateException failure) {
            logTransitionFailure(runtime, current.state(), requested, failure);
            return;
        }
        Worker updated = copy(current, next, current.activeTaskId());
        persist(runtime, updated, () -> {
            runtime.transitionFailureKey = null;
            runtime.transitionFailureLoggedAt = Instant.MIN;
            if (runtime.lease != null) {
                Bukkit.getPluginManager().callEvent(
                        new VerdantWorkerTaskEvent(
                                updated.workerUuid(),
                                runtime.lease.task().task().taskId(),
                                next
                        )
                );
            }
        });
    }

    private void logTransitionFailure(
            RuntimeWorker runtime,
            WorkerState current,
            WorkerState requested,
            IllegalStateException failure
    ) {
        String key = current + "->" + requested;
        Instant now = Instant.now();
        if (key.equals(runtime.transitionFailureKey)
                && now.isBefore(runtime.transitionFailureLoggedAt
                .plus(transitionWarningInterval()))) {
            return;
        }
        runtime.transitionFailureKey = key;
        runtime.transitionFailureLoggedAt = now;
        plugin.getLogger().log(
                Level.WARNING,
                "Illegal worker transition suppressed for "
                        + runtime.record.worker().workerUuid() + ": " + key,
                failure);
    }

    private void persist(
            RuntimeWorker runtime,
            Worker worker,
            Runnable after
    ) {
        if (runtime.busy) {
            return;
        }
        runtime.busy = true;
        Instant now = Instant.now();
        WorkerRecord candidate = runtime.record.withWorker(worker, now);
        if (runtime.entity != null && runtime.entity.isValid()) {
            candidate = candidate.withPosition(
                    blockPosition(runtime.entity.getLocation()),
                    now
            );
        }
        workers.save(candidate).whenComplete((saved, failure) ->
                onMainNow(() -> {
                    runtime.busy = false;
                    if (failure != null) {
                        logAsync(
                                "Could not persist worker "
                                        + worker.workerUuid(),
                                failure
                        );
                        reloadRuntime(runtime);
                        return;
                    }
                    runtime.record = saved;
                    after.run();
                }));
    }

    private void reloadRuntime(RuntimeWorker runtime) {
        runtime.busy = true;
        workers.find(runtime.record.worker().workerUuid())
                .whenComplete((optional, failure) -> onMainNow(() -> {
                    runtime.busy = false;
                    if (failure == null && optional.isPresent()) {
                        runtime.record = optional.get();
                    }
                }));
    }

    private void processSalary(RuntimeWorker runtime, long day) {
        if (runtime.salaryInFlight || runtime.disabled) {
            return;
        }
        runtime.salaryInFlight = true;
        runtime.busy = true;
        UUID workerId = runtime.record.worker().workerUuid();
        java.util.concurrent.atomic.AtomicReference<Optional<InventoryDebit>> debit =
                new java.util.concurrent.atomic.AtomicReference<>(Optional.empty());
        java.util.concurrent.atomic.AtomicBoolean workerSaved =
                new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<WorkerRecord> durableWorker =
                new java.util.concurrent.atomic.AtomicReference<>();
        payments.findForDay(workerId, day)
                .thenCompose(existing -> {
                    if (existing.isPresent()
                            && existing.get().status() != PaymentStatus.PENDING) {
                        return CompletableFuture.completedFuture(new SalaryOutcome(
                                existing.get(), runtime.record, Optional.empty(),
                                existing.get().status() == PaymentStatus.PAID, true));
                    }
                    if (existing.isPresent()) {
                        PaymentRecord pending = existing.get();
                        if (runtime.record.worker().lastPaidMinecraftDay() >= day) {
                            return payments.markPaid(pending.paymentId(), Instant.now())
                                    .thenApply(finalized -> new SalaryOutcome(
                                            finalized, runtime.record, Optional.empty(),
                                            true, true));
                        }
                        if (runtime.record.worker().unpaidDays() > 0
                                && !runtime.record.updatedAt().isBefore(pending.createdAt())) {
                            return payments.markFailed(pending.paymentId())
                                    .thenApply(finalized -> new SalaryOutcome(
                                            finalized, runtime.record, Optional.empty(),
                                            false, true));
                        }
                        if (runtime.startupPendingSalaryDays.contains(day)) {
                            return payments.markFailed(pending.paymentId())
                                    .thenCompose(finalized -> onMainSupply(() ->
                                            prepareUnpaidSalary(runtime, finalized, day)));
                        }
                        return onMainSupply(() -> prepareSalary(runtime, pending, day));
                    }
                    return payments.createPending(
                                    workerId,
                                    runtime.record.worker().ownerUuid(),
                                    runtime.record.worker().farmId(),
                                    runtime.record.worker().salaryPerMinecraftDay(),
                                    day,
                                    Instant.now())
                            .thenCompose(payment -> onMainSupply(() ->
                                    prepareSalary(runtime, payment, day)));
                })
                .thenApply(outcome -> {
                    debit.set(outcome.debit());
                    return outcome;
                })
                .thenCompose(outcome -> {
                    if (outcome.alreadyFinal()) {
                        return CompletableFuture.completedFuture(outcome);
                    }
                    CompletableFuture<WorkerRecord> persisted =
                            workers.save(outcome.updated())
                                    .exceptionallyCompose(saveFailure -> workers.find(workerId)
                                            .thenCompose(current -> {
                                                if (current.isPresent()
                                                        && salaryOutcomePersisted(
                                                        current.get(), outcome, day)) {
                                                    return CompletableFuture.completedFuture(
                                                            current.get());
                                                }
                                                return CompletableFuture.failedFuture(
                                                        unwrap(saveFailure));
                                            }));
                    return persisted.thenCompose(saved -> {
                        durableWorker.set(saved);
                        workerSaved.set(true);
                        CompletableFuture<PaymentRecord> finalLedger = outcome.paid()
                                ? payments.markPaid(outcome.payment().paymentId(), Instant.now())
                                : payments.markFailed(outcome.payment().paymentId());
                        return finalLedger.thenApply(payment -> new SalaryOutcome(
                                payment, saved, outcome.debit(), outcome.paid(), false));
                    });
                })
                .whenComplete((outcome, failure) -> onMainNow(() -> {
                    runtime.busy = false;
                    runtime.salaryInFlight = false;
                    if (failure != null) {
                        if (!workerSaved.get()) {
                            debit.get().ifPresent(this::refund);
                        } else if (durableWorker.get() != null) {
                            runtime.record = durableWorker.get();
                        }
                        handleSalaryFailure(runtime, day, failure);
                        return;
                    }
                    runtime.salaryFailures = 0;
                    runtime.salaryRetryAt = Instant.MIN;
                    runtime.lastSalaryProcessedDay = Math.max(
                            runtime.lastSalaryProcessedDay, day);
                    runtime.pendingSalaryDays.remove(day);
                    runtime.startupPendingSalaryDays.remove(day);
                    if (!outcome.alreadyFinal()) {
                        runtime.record = outcome.updated();
                    }
                    salaryNotification(runtime, outcome, day);
                    if (!outcome.paid() && shouldLeave(runtime.record.worker(), day)) {
                        leaveFarm(runtime);
                    }
                }));
    }

    private boolean salaryOutcomePersisted(
            WorkerRecord current,
            SalaryOutcome outcome,
            long day
    ) {
        Worker persisted = current.worker();
        Worker expected = outcome.updated().worker();
        if (outcome.paid()) {
            return persisted.lastPaidMinecraftDay() >= day
                    && persisted.unpaidDays() == 0;
        }
        return persisted.unpaidDays() >= expected.unpaidDays()
                && persisted.lastPaidMinecraftDay()
                == expected.lastPaidMinecraftDay();
    }

    private void handleSalaryFailure(RuntimeWorker runtime, long day, Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof DatabaseIntegrityException integrity) {
            disableStaleRuntime(runtime, new WorkerPaymentException(
                    WorkerPaymentFailureReason.CONSTRAINT_VIOLATION,
                    "Worker salary persistence violated a database constraint",
                    integrity));
            return;
        }
        if (cause instanceof WorkerPaymentException paymentFailure
                && permanentSalaryFailure(paymentFailure.reason())) {
            disableStaleRuntime(runtime, paymentFailure);
            return;
        }
        runtime.salaryFailures++;
        runtime.salaryRetryAt = salaryAttempts.nextRetry(Instant.now(), runtime.salaryFailures);
        Instant now = Instant.now();
        if (now.isBefore(runtime.salaryFailureLoggedAt.plus(salaryWarningInterval()))) {
            return;
        }
        runtime.salaryFailureLoggedAt = now;
        plugin.getLogger().log(Level.WARNING,
                "Worker salary processing failed for "
                        + runtime.record.worker().workerUuid()
                        + " on day " + day + "; retry after " + runtime.salaryRetryAt,
                cause);
    }

    private boolean permanentSalaryFailure(WorkerPaymentFailureReason reason) {
        return switch (reason) {
            case MISSING_WORKER_PARENT, MISSING_PAYER_PARENT,
                    MISSING_FARM_PARENT, STALE_RUNTIME, CONSTRAINT_VIOLATION -> true;
            case DATABASE_UNAVAILABLE -> false;
        };
    }

    private void disableStaleRuntime(RuntimeWorker runtime, WorkerPaymentException failure) {
        if (runtime.disabled) return;
        runtime.disabled = true;
        runtime.salaryRetryAt = Instant.MAX;
        if (runtime.entity != null) {
            pathfinder.stop(runtime.entity);
        }
        UUID workerId = runtime.record.worker().workerUuid();
        runtime.lease = null;
        tasks.releaseForWorker(workerId, Instant.now())
                .whenComplete((ignored, releaseFailure) -> {
                    if (releaseFailure != null) {
                        logAsync("Could not release tasks for disabled worker " + workerId,
                                releaseFailure);
                    }
                });
        plugin.getLogger().log(Level.SEVERE,
                "Disabled stale worker runtime " + workerId
                        + " after salary integrity failure " + failure.reason(),
                failure);
    }

    private SalaryOutcome prepareUnpaidSalary(
            RuntimeWorker runtime,
            PaymentRecord payment,
            long day
    ) {
        SalaryPayment processed = salaryService.processDay(
                runtime.record.worker(),
                day,
                0
        );
        Worker adjusted = moraleService.applyDailyFactors(
                processed.worker(),
                moraleFactors(runtime, false, day)
        );
        return new SalaryOutcome(
                payment,
                runtime.record.withWorker(adjusted, Instant.now()),
                Optional.empty(),
                false,
                false
        );
    }

    private SalaryOutcome prepareSalary(
            RuntimeWorker runtime,
            PaymentRecord payment,
            long day
    ) {
        Worker current = runtime.record.worker();
        Optional<Inventory> salaryInventory = salaryInventory(
                current.farmId()
        );
        int available = salaryInventory.map(this::countEmeralds).orElse(0);
        SalaryPayment processed = salaryService.processDay(
                current,
                day,
                available
        );
        Optional<InventoryDebit> debit = Optional.empty();
        if (processed.paid() && processed.emeraldsTaken() > 0) {
            Inventory inventory = salaryInventory.orElseThrow();
            debit = Optional.of(debitEmeralds(
                    inventory,
                    processed.emeraldsTaken()
            ));
        }
        MoraleFactors factors = moraleFactors(
                runtime,
                processed.paid(),
                day
        );
        Worker adjusted = moraleService.applyDailyFactors(
                processed.worker(),
                factors
        );
        WorkerRecord updated = runtime.record.withWorker(
                adjusted,
                Instant.now()
        );
        return new SalaryOutcome(
                payment,
                updated,
                debit,
                processed.paid(),
                false
        );
    }

    private MoraleFactors moraleFactors(
            RuntimeWorker runtime,
            boolean paid,
            long day
    ) {
        boolean hasBed = hasBed(runtime);
        boolean hasFood = consumeFood(runtime.record.worker().farmId());
        boolean safe = isSafe(runtime);
        int farmWorkers = Math.max(
                1,
                workers(runtime.record.worker().farmId()).size()
        );
        long plantCount = plants.all().stream()
                .filter(plant -> plant.farmId()
                        .map(runtime.record.worker().farmId()::equals)
                        .orElse(false))
                .count();
        int assigned = (int) Math.min(
                100_000L,
                (plantCount + farmWorkers - 1L) / farmWorkers
        );
        int reasonable = Math.max(
                1,
                configs.get("workers.yml").getInt(
                        "max-plants-per-worker",
                        10
                )
        );
        boolean missingTools = actions.requiredStorage(
                runtime.record.worker().farmId(),
                WorkerTaskType.CLEAN
        ).isEmpty();
        MoraleFactors factors = new MoraleFactors(
                paid,
                hasBed,
                hasFood,
                safe,
                runtime.receivedBreak,
                assigned,
                reasonable,
                !safe,
                missingTools,
                runtime.record.schedule().isNightWork(
                        runtime.entity.getWorld().getTime()
                ),
                runtime.blockedCount,
                runtime.coworkerDied
        );
        runtime.receivedBreak = false;
        runtime.blockedCount = 0;
        runtime.coworkerDied = false;
        return factors;
    }

    private String workerName(Player player, Villager villager) {
        String fallback = translations.plain(player, "herbalist.title");
        String plain = villager.customName() == null
                ? fallback
                : PlainTextComponentSerializer.plainText()
                        .serialize(villager.customName());
        return ExternalNameSanitizer.name(plain, fallback, 64);
    }

    private String safeWorkerName(Player viewer, String source) {
        return ExternalNameSanitizer.name(
                source,
                translations.plain(viewer, "herbalist.title"),
                64
        );
    }

    private void salaryNotification(
            RuntimeWorker runtime,
            SalaryOutcome outcome,
            long day
    ) {
        Player owner = Bukkit.getPlayer(
                runtime.record.worker().ownerUuid()
        );
        if (owner == null) {
            return;
        }
        if (outcome.paid()) {
            owner.sendMessage(translations.component(
                    owner,
                    "worker.salary.paid",
                    Map.of(
                            "worker", safeWorkerName(owner, runtime.record.name()),
                            "amount", runtime.record.worker()
                                    .salaryPerMinecraftDay(),
                            "day", day
                    )
            ));
            return;
        }
        int unpaid = runtime.record.worker().unpaidDays();
        String key = unpaid >= 3
                ? "worker.salary.missing-three"
                : unpaid == 2
                ? "worker.salary.missing-two"
                : "worker.salary.missing-one";
        notifications.send(
                owner,
                NotificationType.UNPAID_WORKER,
                key,
                Map.of("worker", safeWorkerName(owner, runtime.record.name()))
        );
    }

    private void generatePlantTasks() {
        if (generatingTasks || runtimes.isEmpty()) {
            return;
        }
        generatingTasks = true;
        Instant now = Instant.now();
        List<CompletableFuture<StoredTask>> created = new ArrayList<>();
        for (Farm farm : farms.all()) {
            int workerCount = workers(farm.farmId()).size();
            if (workerCount == 0) {
                continue;
            }
            int limit = Math.max(
                    1,
                    configs.get("workers.yml").getInt(
                            "max-plants-per-worker",
                            10
                    )
            ) * workerCount;
            List<Plant> candidates = plants.all().stream()
                    .filter(plant -> plant.farmId()
                            .map(farm.farmId()::equals)
                            .orElse(false))
                    .sorted(Comparator.comparing(plant ->
                            plant.uniqueId().toString()))
                    .limit(limit)
                    .toList();
            for (Plant plant : candidates) {
                generatedTask(farm, plant, now).ifPresent(spec ->
                        created.add(submitTask(
                                farm.farmId(),
                                spec.type(),
                                new TaskTarget(
                                        "plant",
                                        plant.uniqueId(),
                                        Optional.of(plant.position())
                                ),
                                Duration.ofMinutes(20L),
                                spec.idempotencyKey()
                        )));
            }
            for (WorkerActionService.ProcessingTask task : actions.processingTasks(farm, limit)) {
                if (priorityEnabled(task.type())) {
                    created.add(submitTask(farm.farmId(), task.type(), task.target(),
                            Duration.ofMinutes(20L), task.idempotencyKey()));
                }
            }
        }
        CompletableFuture.allOf(created.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, failure) -> onMainNow(() -> {
                    generatingTasks = false;
                    if (failure != null) {
                        logAsync("Plant task generation failed", failure);
                    }
                }));
    }

    private Optional<GeneratedTask> generatedTask(
            Farm farm,
            Plant plant,
            Instant now
    ) {
        if (plant.health() <= 25.0 && priorityEnabled(
                WorkerTaskType.SAVE_DYING_PLANTS)) {
            return Optional.of(generated(
                    WorkerTaskType.SAVE_DYING_PLANTS,
                    plant,
                    plant.lastUpdatedAt().toEpochMilli()
            ));
        }
        if ((plant.diseaseId().isPresent() || plant.pestLevel() >= 15.0)
                && priorityEnabled(WorkerTaskType.TREAT_DISEASE)) {
            return Optional.of(generated(
                    WorkerTaskType.TREAT_DISEASE,
                    plant,
                    plant.lastUpdatedAt().toEpochMilli()
            ));
        }
        if (plant.hydration() <= 30.0
                && priorityEnabled(WorkerTaskType.WATER)) {
            return Optional.of(generated(
                    WorkerTaskType.WATER,
                    plant,
                    plant.lastWateredAt().toEpochMilli()
            ));
        }
        if (harvestable(plant, now)
                && priorityEnabled(WorkerTaskType.HARVEST)) {
            return Optional.of(generated(
                    WorkerTaskType.HARVEST,
                    plant,
                    plant.harvestWindowStart().toEpochMilli()
            ));
        }
        if (plant.cleanliness() <= 40.0
                && priorityEnabled(WorkerTaskType.CLEAN)) {
            return Optional.of(generated(
                    WorkerTaskType.CLEAN,
                    plant,
                    plant.lastUpdatedAt().toEpochMilli()
            ));
        }
        long day = minecraftDay(
                plugin.getServer().getWorld(farm.region().worldUuid())
        );
        if (priorityEnabled(WorkerTaskType.INSPECT)) {
            return Optional.of(generated(
                    WorkerTaskType.INSPECT,
                    plant,
                    day
            ));
        }
        return Optional.empty();
    }

    private GeneratedTask generated(
            WorkerTaskType type,
            Plant plant,
            long revision
    ) {
        return new GeneratedTask(
                type,
                "auto:" + type.name().toLowerCase(java.util.Locale.ROOT)
                        + ':' + plant.uniqueId() + ':' + revision
        );
    }

    private void bind(RuntimeWorker runtime, Villager villager) {
        runtime.entity = villager;
        villager.getPersistentDataContainer().set(
                workerIdKey,
                PersistentDataType.STRING,
                runtime.record.worker().workerUuid().toString()
        );
        villager.setRemoveWhenFarAway(false);
        villager.setPersistent(true);
        villager.setAware(true);
        villager.setCanPickupItems(false);
    }

    private CompletableFuture<Boolean> dismissRuntime(
            RuntimeWorker runtime,
            UUID ownerUuid,
            boolean left
    ) {
        UUID workerId = runtime.record.worker().workerUuid();
        return tasks.releaseForWorker(workerId, Instant.now())
                .thenCompose(ignored -> workers.dismiss(
                        workerId,
                        ownerUuid,
                        Instant.now()
                ))
                .thenCompose(removed -> onMainSupply(() -> {
                    if (removed) {
                        runtimes.remove(workerId);
                        workerByEntity.remove(runtime.record.entityUuid());
                        if (runtime.entity != null && runtime.entity.isValid()) {
                            pathfinder.stop(runtime.entity);
                            runtime.entity.getPersistentDataContainer()
                                    .remove(workerIdKey);
                        }
                    }
                    return removed;
                }));
    }

    private void leaveFarm(RuntimeWorker runtime) {
        Farm farm = farms.get(runtime.record.worker().farmId()).orElse(null);
        Player owner = Bukkit.getPlayer(
                runtime.record.worker().ownerUuid()
        );
        dismissRuntime(
                runtime,
                runtime.record.worker().ownerUuid(),
                true
        ).thenAccept(removed -> onMainNow(() -> {
            if (removed && owner != null) {
                notifications.send(
                        owner,
                        NotificationType.UNPAID_WORKER,
                        "worker.salary.left",
                        Map.of(
                                "worker", runtime.record.name(),
                                "farm", farm == null ? "?" : farm.name()
                        )
                );
            }
        }));
    }

    private Optional<Farm> selectFarm(Player player, Villager villager) {
        Optional<Farm> at = farms.at(villager.getLocation())
                .filter(farm -> player.hasPermission("verdantvice.admin")
                        || farm.can(
                        player.getUniqueId(),
                        FarmPermission.MANAGE_WORKERS
                ));
        if (at.isPresent()) {
            return at;
        }
        return farms.forPlayer(player.getUniqueId()).stream()
                .filter(farm -> player.hasPermission("verdantvice.admin")
                        || farm.can(
                        player.getUniqueId(),
                        FarmPermission.MANAGE_WORKERS
                ))
                .sorted(Comparator.comparing(Farm::name))
                .findFirst();
    }

    private int effectiveWorkerLimit(Farm farm) {
        int configured = Math.max(
                0,
                configs.main().getInt(
                        "performance.max-workers-per-farm",
                        5
                )
        );
        return Math.min(farm.workerLimit(), configured);
    }

    private Set<WorkerTaskType> allowedTasks(Worker worker) {
        EnumSet<WorkerTaskType> allowed =
                EnumSet.allOf(WorkerTaskType.class);
        allowed.removeIf(type -> !priorityEnabled(type));
        if (worker.refusesNonCriticalTasks()) {
            allowed.retainAll(CRITICAL_TASKS);
        }
        return Set.copyOf(allowed);
    }

    private int priority(WorkerTaskType type) {
        return Math.max(
                0,
                Math.min(
                        100,
                        configs.get("workers.yml").getInt(
                                "priorities." + type.name(),
                                defaultPriority(type)
                        )
                )
        );
    }

    private boolean priorityEnabled(WorkerTaskType type) {
        return configs.get("workers.yml").getInt(
                "priorities." + type.name(),
                defaultPriority(type)
        ) >= 0;
    }

    private int defaultPriority(WorkerTaskType type) {
        return switch (type) {
            case SAVE_DYING_PLANTS -> 100;
            case TREAT_DISEASE -> 90;
            case WATER -> 80;
            case HARVEST -> 70;
            case LOAD_DRYING_RACK -> 60;
            case UNLOAD_DRYING_RACK -> 55;
            case PLANT -> 45;
            case PACKAGE -> 35;
            case CLEAN -> 25;
            case INSPECT -> 20;
            case MOVE_BATCH -> 30;
        };
    }

    private int maximumAttempts() {
        return Math.max(
                1,
                configs.get("workers.yml").getInt("path-retry-count", 3)
        );
    }

    private Duration reservationDuration() {
        return Duration.ofSeconds(Math.max(
                5L,
                configs.get("workers.yml").getLong(
                        "task-reservation-seconds",
                        45L
                )
        ));
    }

    private int refusalDays() {
        return Math.max(1, configs.get("workers.yml").getInt("salary.refusal-days", 3));
    }

    private Duration salaryWarningInterval() {
        return Duration.ofSeconds(Math.max(5L,
                configs.get("workers.yml").getLong(
                        "salary.warning-interval-seconds", 60L)));
    }

    private Duration transitionWarningInterval() {
        return Duration.ofSeconds(Math.max(5L,
                configs.get("workers.yml").getLong(
                        "state-transition-warning-interval-seconds", 60L)));
    }

    private Duration pathTimeout() {
        return Duration.ofSeconds(Math.max(
                5L,
                configs.get("workers.yml").getLong(
                        "path-timeout-seconds",
                        30L
                )
        ));
    }

    private WorkerState nextAvailableState(RuntimeWorker runtime) {
        WorkerSchedule.Phase phase = runtime.record.schedule().phase(
                runtime.entity.getWorld().getTime()
        );
        return switch (phase) {
            case WORK -> WorkerState.SEARCHING_TASK;
            case BREAK, OFF_DUTY, SLEEP -> WorkerState.RETURNING_HOME;
        };
    }

    private boolean atHome(RuntimeWorker runtime) {
        if (runtime.entity == null || runtime.record.home().isEmpty()) {
            return false;
        }
        Optional<Location> home = location(runtime.record.home().get());
        return home.isPresent()
                && runtime.entity.getWorld().equals(home.get().getWorld())
                && runtime.entity.getLocation().distanceSquared(home.get()) <= 4.0;
    }

    private boolean hasBed(RuntimeWorker runtime) {
        if (runtime.record.home().isEmpty()) {
            return false;
        }
        BlockPosition home = runtime.record.home().get();
        Optional<Location> center = location(home);
        if (center.isEmpty()
                || !center.get().getWorld().isChunkLoaded(
                home.x() >> 4,
                home.z() >> 4
        )) {
            return false;
        }
        World world = center.get().getWorld();
        for (int y = -2; y <= 2; y++) {
            for (int x = -8; x <= 8; x++) {
                for (int z = -8; z <= 8; z++) {
                    if (Tag.BEDS.isTagged(
                            world.getBlockAt(
                                    home.x() + x,
                                    home.y() + y,
                                    home.z() + z
                            ).getType()
                    )) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean consumeFood(UUID farmId) {
        List<FarmStorage> storages = farms.storages(farmId).stream()
                .filter(FarmStorage::enabled)
                .filter(storage -> storage.type() == StorageType.GENERAL_STORAGE)
                .sorted(Comparator.comparing(storage ->
                        storage.storageId().toString()))
                .toList();
        for (FarmStorage storage : storages) {
            Optional<Inventory> inventory = inventory(storage.position());
            if (inventory.isEmpty()) {
                continue;
            }
            for (int slot = 0; slot < inventory.get().getSize(); slot++) {
                ItemStack item = inventory.get().getItem(slot);
                if (item != null && item.getType().isEdible()) {
                    if (item.getAmount() == 1) {
                        inventory.get().setItem(slot, null);
                    } else {
                        item.setAmount(item.getAmount() - 1);
                        inventory.get().setItem(slot, item);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isSafe(RuntimeWorker runtime) {
        if (runtime.entity == null) {
            return false;
        }
        return runtime.entity.getWorld()
                .getNearbyEntities(
                        runtime.entity.getLocation(),
                        16.0,
                        8.0,
                        16.0,
                        entity -> entity instanceof Monster
                ).isEmpty();
    }

    private Optional<Inventory> salaryInventory(UUID farmId) {
        return farms.storages(farmId).stream()
                .filter(FarmStorage::enabled)
                .filter(storage ->
                        storage.type() == StorageType.SALARY_STORAGE)
                .sorted(Comparator.comparing(storage ->
                        storage.storageId().toString()))
                .map(FarmStorage::position)
                .map(this::inventory)
                .flatMap(Optional::stream)
                .findFirst();
    }

    private Optional<Inventory> inventory(BlockPosition position) {
        Optional<Location> location = location(position);
        if (location.isEmpty()) {
            return Optional.empty();
        }
        World world = location.get().getWorld();
        if (!world.isChunkLoaded(position.x() >> 4, position.z() >> 4)) {
            return Optional.empty();
        }
        Block block = world.getBlockAt(
                position.x(),
                position.y(),
                position.z()
        );
        return block.getState() instanceof Container container
                ? Optional.of(container.getInventory())
                : Optional.empty();
    }

    private int countEmeralds(Inventory inventory) {
        int amount = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && item.getType() == Material.EMERALD) {
                amount = Math.addExact(amount, item.getAmount());
            }
        }
        return amount;
    }

    private InventoryDebit debitEmeralds(Inventory inventory, int amount) {
        int remaining = amount;
        for (int slot = 0; slot < inventory.getSize() && remaining > 0; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType() != Material.EMERALD) {
                continue;
            }
            int taken = Math.min(remaining, item.getAmount());
            remaining -= taken;
            if (taken == item.getAmount()) {
                inventory.setItem(slot, null);
            } else {
                item.setAmount(item.getAmount() - taken);
                inventory.setItem(slot, item);
            }
        }
        if (remaining != 0) {
            throw new IllegalStateException("Salary inventory changed during debit");
        }
        return new InventoryDebit(inventory, amount);
    }

    private void refund(InventoryDebit debit) {
        Map<Integer, ItemStack> overflow = debit.inventory().addItem(
                new ItemStack(Material.EMERALD, debit.amount())
        );
        if (!overflow.isEmpty()) {
            plugin.getLogger().warning(
                    "Could not fully refund a failed worker salary debit"
            );
        }
    }

    private boolean shouldLeave(Worker worker, long day) {
        double configured = Math.max(
                0.0,
                Math.min(
                        1.0,
                        configs.get("workers.yml").getDouble(
                                "salary.leave-chance-per-extra-day",
                                0.08
                        )
                )
        );
        if (worker.unpaidDays() < 4 && worker.morale() >= 20.0) {
            return false;
        }
        double chance = Math.min(
                0.85,
                Math.max(
                        worker.leavingChance(),
                        (worker.unpaidDays() - 3) * configured
                )
        );
        long mixed = worker.workerUuid().getMostSignificantBits()
                ^ Long.rotateLeft(
                worker.workerUuid().getLeastSignificantBits(),
                17
        )
                ^ day * 0x9E3779B97F4A7C15L;
        double deterministic = (mixed >>> 11)
                * 0x1.0p-53;
        return deterministic < chance;
    }

    private void notifyTask(
            RuntimeWorker runtime,
            String key,
            WorkerTask task,
            String reason
    ) {
        Player owner = Bukkit.getPlayer(
                runtime.record.worker().ownerUuid()
        );
        if (owner == null) {
            return;
        }
        Map<String, Object> placeholders = new LinkedHashMap<>();
        placeholders.put("worker", runtime.record.name());
        placeholders.put("task", task.type().name());
        placeholders.put(
                "position",
                task.target().position().map(this::positionText).orElse("?")
        );
        if (reason != null) {
            placeholders.put(
                    "reason",
                    translations.plain(
                            owner,
                            "worker.task." + reason
                    )
            );
        }
        if (key.equals("worker.task.blocked")) {
            notifications.send(
                    owner,
                    NotificationType.BLOCKED_WORKER,
                    key,
                    placeholders
            );
        } else {
            owner.sendMessage(translations.component(
                    owner,
                    key,
                    placeholders
            ));
        }
    }

    private void notifyBlocked(
            RuntimeWorker runtime,
            BlockPosition position
    ) {
        Player owner = Bukkit.getPlayer(
                runtime.record.worker().ownerUuid()
        );
        if (owner == null) {
            return;
        }
        notifications.send(
                owner,
                NotificationType.BLOCKED_WORKER,
                "worker.task.blocked",
                Map.of(
                        "worker", runtime.record.name(),
                        "position", position == null
                                ? "?"
                                : positionText(position)
                )
        );
    }

    private void sendHireFailure(Player player, HireStatus status) {
        String key = switch (status) {
            case CONTRACT_REQUIRED -> "herbalist.hire.contract-required";
            case NOT_EXPERT -> "herbalist.hire.unavailable";
            case ALREADY_EMPLOYED -> "herbalist.hire.already-employed";
            case NO_PERMISSION -> "errors.no-permission";
            case NO_FARM -> "errors.target-not-found";
            case WORKER_LIMIT -> "errors.limit-reached";
            case CANCELLED -> "errors.operation-cancelled";
            case INVALID_ENTITY, FAILED -> "errors.target-not-found";
            case HIRED -> null;
        };
        if (key != null) {
            player.sendMessage(translations.component(player, key));
        }
    }

    private HireStatus hireFailure(Throwable failure) {
        String message = failure.getMessage();
        if (message != null && message.contains("already_employed")) {
            return HireStatus.ALREADY_EMPLOYED;
        }
        if (message != null && message.contains("worker_limit")) {
            return HireStatus.WORKER_LIMIT;
        }
        if (message != null && message.contains("invalid_entity")) {
            return HireStatus.INVALID_ENTITY;
        }
        return HireStatus.FAILED;
    }

    private CompletableFuture<HireResult> completedHire(HireStatus status) {
        return CompletableFuture.completedFuture(
                new HireResult(status, Optional.empty())
        );
    }

    private CompletableFuture<HireResult> failedHire(
            Player player,
            HireStatus status
    ) {
        sendHireFailure(player, status);
        return completedHire(status);
    }

    private WorkerSpecialization specialization(UUID entityUuid) {
        WorkerSpecialization[] values = WorkerSpecialization.values();
        return values[Math.floorMod(entityUuid.hashCode(), values.length)];
    }

    private boolean harvestable(Plant plant, Instant now) {
        Objects.requireNonNull(now, "now");
        return plant.growthStage() == GrowthStage.READY;
    }

    private long minecraftDay(World world) {
        return world == null ? 0L : Math.floorDiv(world.getFullTime(), 24_000L);
    }

    private Worker copy(
            Worker worker,
            WorkerState state,
            Optional<UUID> activeTask
    ) {
        return new Worker(
                worker.workerUuid(),
                worker.ownerUuid(),
                worker.farmId(),
                worker.specialization(),
                worker.level(),
                worker.experience(),
                worker.morale(),
                worker.salaryPerMinecraftDay(),
                worker.unpaidDays(),
                worker.lastPaidMinecraftDay(),
                state,
                activeTask
        );
    }

    private BlockPosition blockPosition(Location location) {
        return new BlockPosition(
                Objects.requireNonNull(location.getWorld()).getUID(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ()
        );
    }

    private Optional<Location> location(BlockPosition position) {
        World world = Bukkit.getWorld(position.worldUuid());
        return world == null
                ? Optional.empty()
                : Optional.of(new Location(
                        world,
                        position.x() + 0.5,
                        position.y(),
                        position.z() + 0.5
                ));
    }

    private String positionText(BlockPosition position) {
        return position.x() + ", " + position.y() + ", " + position.z();
    }

    private void removeItemInstance(Player player, UUID instanceId) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            ItemSigner.Verification verification = secureItems.verify(item);
            if (verification.valid()
                    && verification.data().uniqueInstanceId()
                    .equals(instanceId)) {
                player.getInventory().setItem(slot, null);
                return;
            }
        }
    }

    private <T> CompletableFuture<T> onMainSupply(
            java.util.function.Supplier<T> action
    ) {
        if (Bukkit.isPrimaryThread()) {
            try {
                return CompletableFuture.completedFuture(action.get());
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                future.complete(action.get());
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        });
        return future;
    }

    private CompletableFuture<Void> onMain(Runnable action) {
        return onMainSupply(() -> {
            action.run();
            return null;
        });
    }

    private void onMainNow(Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, action);
        }
    }

    private void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(
                    "Worker facade operations must start on the server thread"
            );
        }
    }

    private void logAsync(String message, Throwable failure) {
        plugin.getLogger().log(Level.WARNING, message, unwrap(failure));
    }

    private Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    public enum HireStatus {
        HIRED,
        CONTRACT_REQUIRED,
        NOT_EXPERT,
        ALREADY_EMPLOYED,
        NO_PERMISSION,
        NO_FARM,
        WORKER_LIMIT,
        CANCELLED,
        INVALID_ENTITY,
        FAILED
    }

    public record HireResult(
            HireStatus status,
            Optional<WorkerRecord> worker
    ) {
        public HireResult {
            Objects.requireNonNull(status, "status");
            worker = Objects.requireNonNull(worker, "worker");
            if ((status == HireStatus.HIRED) != worker.isPresent()) {
                throw new IllegalArgumentException(
                        "Successful hire must contain a worker"
                );
            }
        }
    }

    private static final class RuntimeWorker {
        private WorkerRecord record;
        private Villager entity;
        private TaskLease lease;
        private PaperWorkerPathfinder.Navigation navigation;
        private BlockPosition navigationTarget;
        private int pathAttempts;
        private int blockedCount;
        private Instant blockedSince;
        private boolean busy;
        private boolean receivedBreak;
        private boolean coworkerDied;
        private WorkerTaskType lastTaskType;
        private long lastSalaryProcessedDay = Long.MIN_VALUE;
        private boolean salaryInFlight;
        private final java.util.ArrayDeque<Long> pendingSalaryDays =
                new java.util.ArrayDeque<>();
        private final java.util.Set<Long> startupPendingSalaryDays =
                new java.util.HashSet<>();
        private Instant salaryRetryAt = Instant.MIN;
        private int salaryFailures;
        private Instant salaryFailureLoggedAt = Instant.MIN;
        private boolean disabled;
        private String transitionFailureKey;
        private Instant transitionFailureLoggedAt = Instant.MIN;

        private RuntimeWorker(WorkerRecord record) {
            this.record = Objects.requireNonNull(record, "record");
        }

        private void queueStartupPendingSalary(long day) {
            if (startupPendingSalaryDays.add(day)) {
                pendingSalaryDays.add(day);
                java.util.List<Long> ordered = new java.util.ArrayList<>(pendingSalaryDays);
                ordered.sort(Long::compareTo);
                pendingSalaryDays.clear();
                pendingSalaryDays.addAll(ordered);
            }
        }
    }

    public record WorkerSnapshot(
            WorkerRecord record,
            Optional<WorkerTaskType> currentTask,
            Optional<Integer> currentPriority,
            Optional<WorkerTaskType> lastTask,
            Optional<BlockPosition> position,
            boolean entityAvailable,
            boolean busy,
            int blockedCount
    ) {
        public WorkerSnapshot {
            Objects.requireNonNull(record, "record");
            currentTask = Objects.requireNonNull(currentTask, "currentTask");
            currentPriority = Objects.requireNonNull(currentPriority, "currentPriority");
            lastTask = Objects.requireNonNull(lastTask, "lastTask");
            position = Objects.requireNonNull(position, "position");
        }
    }

    private record HireContext(
            HireStatus status,
            HerbalistRecord herbalist,
            Farm farm,
            BlockPosition home
    ) {
        private static HireContext failed(HireStatus status) {
            return new HireContext(status, null, null, null);
        }
    }

    private record WorkerLoad(
            List<WorkerRecord> records,
            List<PaymentRecord> pendingPayments
    ) {
        private WorkerLoad {
            records = List.copyOf(Objects.requireNonNull(records, "records"));
            pendingPayments = List.copyOf(Objects.requireNonNull(
                    pendingPayments, "pendingPayments"));
        }
    }

    private record GeneratedTask(
            WorkerTaskType type,
            String idempotencyKey
    ) {
    }

    private record InventoryDebit(Inventory inventory, int amount) {
        private InventoryDebit {
            Objects.requireNonNull(inventory, "inventory");
            if (amount < 1) {
                throw new IllegalArgumentException("Debit must be positive");
            }
        }
    }

    private record SalaryOutcome(
            PaymentRecord payment,
            WorkerRecord updated,
            Optional<InventoryDebit> debit,
            boolean paid,
            boolean alreadyFinal
    ) {
        private SalaryOutcome {
            Objects.requireNonNull(payment, "payment");
            Objects.requireNonNull(updated, "updated");
            debit = Objects.requireNonNull(debit, "debit");
        }
    }

}
