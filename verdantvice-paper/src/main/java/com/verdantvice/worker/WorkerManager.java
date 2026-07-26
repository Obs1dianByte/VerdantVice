package com.verdantvice.worker;

import com.verdantvice.api.event.VerdantWorkerHiredEvent;
import com.verdantvice.api.event.VerdantWorkerTaskEvent;
import com.verdantvice.common.BlockPosition;
import com.verdantvice.config.ConfigManager;
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
    private final WorkerStateMachine stateMachine = new WorkerStateMachine();
    private final SalaryService salaryService = new SalaryService();
    private final MoraleService moraleService = new MoraleService();
    private final WorkerProgressionService progression = new WorkerProgressionService();
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
            WorkerPaymentRepository payments
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
        this.pathfinder = new PaperWorkerPathfinder(plugin);
        this.actions = new WorkerActionService(
                plugin,
                configs,
                farms,
                plants,
                secureItems
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
        Objects.requireNonNull(database, "database");
        return new WorkerManager(
                plugin,
                configs,
                translations,
                farms,
                plants,
                herbalists,
                secureItems,
                notifications,
                new WorkerRepository(database),
                new WorkerTaskRepository(database),
                new WorkerPaymentRepository(database)
        );
    }

    public CompletableFuture<Void> load() {
        Instant now = Instant.now();
        return workers.loadAll()
                .thenCombine(tasks.cleanupExpired(now), (records, ignored) -> records)
                .thenCompose(records -> onMain(() -> {
                    runtimes.clear();
                    workerByEntity.clear();
                    for (WorkerRecord record : records) {
                        RuntimeWorker runtime = new RuntimeWorker(record);
                        runtimes.put(record.worker().workerUuid(), runtime);
                        workerByEntity.put(record.entityUuid(), record.worker().workerUuid());
                        Entity entity = Bukkit.getEntity(record.entityUuid());
                        if (entity instanceof Villager villager) {
                            bind(runtime, villager);
                        }
                    }
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
        tickTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                () -> tick(),
                period,
                period
        );
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
                    return secureItems.consume(
                                    contractSnapshot,
                                    CONTRACT_ONLY,
                                    player.getUniqueId(),
                                    operation
                            )
                            .thenCompose(consumed -> workers.hireHerbalist(
                                    context.herbalist(),
                                    player.getUniqueId(),
                                    context.farm().farmId(),
                                    villager.getName(),
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
                                    Instant.now()
                            ))
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
                                Map.of("worker", runtime.record.name())
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
        tasks.releaseForWorker(workerId, Instant.now());
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
        if (runtime.busy) {
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
        if (runtime.lease == null && runtime.lastSalaryProcessedDay < day) {
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
        if (directive == WorkerStateMachine.ScheduleDirective.RETURN_HOME
                && runtime.lease == null
                && worker.state() != WorkerState.RETURNING_HOME
                && worker.state() != WorkerState.BLOCKED) {
            transition(runtime, WorkerState.RETURNING_HOME);
            return;
        }
        if (directive == WorkerStateMachine.ScheduleDirective.REST
                && runtime.lease == null
                && worker.state() != WorkerState.RESTING) {
            transition(runtime, WorkerState.RESTING);
            runtime.receivedBreak = true;
            return;
        }
        if (directive == WorkerStateMachine.ScheduleDirective.SLEEP
                && runtime.lease == null
                && atHome
                && worker.state() != WorkerState.SLEEPING) {
            transition(runtime, WorkerState.SLEEPING);
            return;
        }

        switch (worker.state()) {
            case IDLE -> {
                if (directive == WorkerStateMachine.ScheduleDirective.WORK) {
                    transition(runtime, WorkerState.SEARCHING_TASK);
                }
            }
            case RESTING, SLEEPING -> {
                if (directive == WorkerStateMachine.ScheduleDirective.WORK) {
                    transition(runtime, WorkerState.SEARCHING_TASK);
                }
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
                if (directive == WorkerStateMachine.ScheduleDirective.WORK) {
                    transition(runtime, WorkerState.SEARCHING_TASK);
                } else if (!atHome) {
                    transition(runtime, WorkerState.RETURNING_HOME);
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
        if (runtime.navigation == null) {
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
        if (runtime.busy) {
            return;
        }
        Worker current = runtime.record.worker();
        WorkerState next = stateMachine.transition(current.state(), requested);
        Worker updated = copy(current, next, current.activeTaskId());
        persist(runtime, updated, () -> {
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
        runtime.busy = true;
        runtime.lastSalaryProcessedDay = day;
        UUID workerId = runtime.record.worker().workerUuid();
        java.util.concurrent.atomic.AtomicReference<Optional<InventoryDebit>> debit =
                new java.util.concurrent.atomic.AtomicReference<>(Optional.empty());
        java.util.concurrent.atomic.AtomicBoolean workerSaved =
                new java.util.concurrent.atomic.AtomicBoolean();
        payments.findForDay(workerId, day)
                .thenCompose(existing -> {
                    if (existing.isPresent()
                            && existing.get().status() != PaymentStatus.PENDING) {
                        return CompletableFuture.completedFuture(
                                new SalaryOutcome(
                                        existing.get(),
                                        runtime.record,
                                        Optional.empty(),
                                        existing.get().status() == PaymentStatus.PAID,
                                        true
                                )
                        );
                    }
                    if (existing.isPresent()) {
                        PaymentRecord pending = existing.get();
                        if (runtime.record.worker().lastPaidMinecraftDay() >= day) {
                            return payments.markPaid(
                                            pending.paymentId(),
                                            Instant.now()
                                    )
                                    .thenApply(finalized -> new SalaryOutcome(
                                            finalized,
                                            runtime.record,
                                            Optional.empty(),
                                            true,
                                            true
                                    ));
                        }
                        if (runtime.record.worker().unpaidDays() > 0
                                && !runtime.record.updatedAt()
                                .isBefore(pending.createdAt())) {
                            return payments.markFailed(pending.paymentId())
                                    .thenApply(finalized -> new SalaryOutcome(
                                            finalized,
                                            runtime.record,
                                            Optional.empty(),
                                            false,
                                            true
                                    ));
                        }
                        return payments.markFailed(pending.paymentId())
                                .thenCompose(finalized -> onMainSupply(() ->
                                        prepareUnpaidSalary(
                                                runtime,
                                                finalized,
                                                day
                                        )));
                    }
                    CompletableFuture<PaymentRecord> ledger = existing.isPresent()
                            ? CompletableFuture.completedFuture(existing.get())
                            : payments.createPending(
                                    workerId,
                                    runtime.record.worker().ownerUuid(),
                                    runtime.record.worker()
                                            .salaryPerMinecraftDay(),
                                    day,
                                    Instant.now()
                            );
                    return ledger.thenCompose(payment -> onMainSupply(() ->
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
                    return workers.save(outcome.updated())
                            .thenCompose(saved -> {
                                workerSaved.set(true);
                                CompletableFuture<PaymentRecord> finalLedger =
                                        outcome.paid()
                                                ? payments.markPaid(
                                                outcome.payment().paymentId(),
                                                Instant.now()
                                        )
                                                : payments.markFailed(
                                                outcome.payment().paymentId()
                                        );
                                return finalLedger.thenApply(payment ->
                                        new SalaryOutcome(
                                                payment,
                                                saved,
                                                outcome.debit(),
                                                outcome.paid(),
                                                false
                                        ));
                            });
                })
                .whenComplete((outcome, failure) -> onMainNow(() -> {
                    runtime.busy = false;
                    if (failure != null) {
                        if (!workerSaved.get()) {
                            debit.get().ifPresent(this::refund);
                        }
                        logAsync("Worker salary processing failed", failure);
                        return;
                    }
                    if (!outcome.alreadyFinal()) {
                        runtime.record = outcome.updated();
                    }
                    salaryNotification(runtime, outcome, day);
                    if (!outcome.paid()
                            && shouldLeave(runtime.record.worker(), day)) {
                        leaveFarm(runtime);
                    }
                }));
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
                            "worker", runtime.record.name(),
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
                Map.of("worker", runtime.record.name())
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
        return plant.growthStage() != GrowthStage.SEED
                && plant.growthStage() != GrowthStage.SPROUT
                && plant.growthStage() != GrowthStage.SEEDLING
                && plant.growthStage() != GrowthStage.SPOILED
                && (!now.isBefore(plant.harvestWindowStart())
                || plant.growthStage() == GrowthStage.READY
                || plant.growthStage() == GrowthStage.OVERRIPE);
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
        private int pathAttempts;
        private int blockedCount;
        private Instant blockedSince;
        private boolean busy;
        private boolean receivedBreak;
        private boolean coworkerDied;
        private long lastSalaryProcessedDay = Long.MIN_VALUE;

        private RuntimeWorker(WorkerRecord record) {
            this.record = Objects.requireNonNull(record, "record");
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
