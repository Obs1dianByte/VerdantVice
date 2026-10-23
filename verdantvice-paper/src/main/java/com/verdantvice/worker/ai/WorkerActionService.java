package com.verdantvice.worker.ai;

import com.verdantvice.common.BlockPosition;
import com.verdantvice.config.ConfigManager;
import com.verdantvice.farm.FarmManager;
import com.verdantvice.farm.FarmStorage;
import com.verdantvice.farm.StorageType;
import com.verdantvice.plant.GrowthStage;
import com.verdantvice.plant.Plant;
import com.verdantvice.plant.PlantManager;
import com.verdantvice.plant.care.CareAction;
import com.verdantvice.processing.ProductBatch;
import com.verdantvice.processing.BatchState;
import com.verdantvice.processing.ProcessingManager;
import com.verdantvice.processing.ProcessingStationType;
import com.verdantvice.processing.ProcessingTransferService;
import com.verdantvice.farm.Farm;
import com.verdantvice.worker.TaskTarget;
import com.verdantvice.security.CustomItemType;
import com.verdantvice.security.ItemSigner;
import com.verdantvice.security.SecureItemService;
import com.verdantvice.security.SignedItemData;
import com.verdantvice.database.item.ItemRegistryRecord;
import com.verdantvice.database.item.ItemRegistryState;
import com.verdantvice.storage.PlantRepository;
import com.verdantvice.worker.WorkerHarvestDeliveryRepository;
import com.verdantvice.worker.WorkerRecord;
import com.verdantvice.worker.WorkerTask;
import com.verdantvice.worker.WorkerTaskRepository;
import com.verdantvice.worker.WorkerTaskType;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Main-thread world/inventory actions backed by the existing farm, plant,
 * batch and signed-item services.
 */
public final class WorkerActionService {
    private static final Set<CustomItemType> WATER_TOOLS =
            EnumSet.of(CustomItemType.WATERING_CAN);
    private static final Set<CustomItemType> TREATMENT_TOOLS =
            EnumSet.of(
                    CustomItemType.TREATMENT_SPRAY,
                    CustomItemType.NUTRIENT_PACK,
                    CustomItemType.REPAIR_KIT
            );
    private static final Set<CustomItemType> CLEANING_TOOLS =
            EnumSet.of(CustomItemType.CLEANING_KIT);
    private static final Set<CustomItemType> SEEDS =
            EnumSet.of(CustomItemType.SEED);
    private static final Set<CustomItemType> BATCH_ITEMS =
            EnumSet.of(
                    CustomItemType.FRESH_BATCH,
                    CustomItemType.DRY_BATCH,
                    CustomItemType.CURED_BATCH,
                    CustomItemType.GROUND_PRODUCT,
                    CustomItemType.MIXTURE,
                    CustomItemType.PACKAGE
            );

    private final JavaPlugin plugin;
    private final ConfigManager configs;
    private final FarmManager farms;
    private final PlantManager plants;
    private final SecureItemService secureItems;
    private final WorkerHarvestDeliveryRepository deliveries;
    private final StorageSaveBarrier storageSaves;
    private final Map<UUID, Integer> harvestReservations = new java.util.HashMap<>();
    private final Set<UUID> harvestDeliveriesInFlight = new java.util.HashSet<>();
    private boolean recoveringDeliveries;
    private ProcessingManager processing;
    private final Set<String> processingDeliveriesInFlight = new java.util.HashSet<>();

    public WorkerActionService(
            JavaPlugin plugin,
            ConfigManager configs,
            FarmManager farms,
            PlantManager plants,
            SecureItemService secureItems,
            WorkerHarvestDeliveryRepository deliveries
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.farms = Objects.requireNonNull(farms, "farms");
        this.plants = Objects.requireNonNull(plants, "plants");
        this.secureItems = Objects.requireNonNull(secureItems, "secureItems");
        this.deliveries = Objects.requireNonNull(deliveries, "deliveries");
        this.storageSaves = new StorageSaveBarrier(plugin);
    }

    public void attachProcessing(ProcessingManager processing) {
        requireMainThread();
        this.processing = Objects.requireNonNull(processing, "processing");
    }

    public Optional<Location> targetLocation(WorkerTask task) {
        requireMainThread();
        Objects.requireNonNull(task, "task");
        Optional<BlockPosition> explicit = task.target().position();
        if (explicit.isPresent()) {
            return location(explicit.get());
        }
        if (task.target().targetType().equals("plant")) {
            return plants.plant(task.target().targetId())
                    .flatMap(plant -> location(plant.position()));
        }
        if (task.target().targetType().equals("batch")) {
            return findBatch(task.target().targetId(), null)
                    .map(InventoryItem::location);
        }
        return Optional.empty();
    }

    public Optional<FarmStorage> requiredStorage(
            UUID farmId,
            WorkerTaskType type
    ) {
        requireMainThread();
        StorageType storageType = switch (type) {
            case WATER -> StorageType.WATER_STORAGE;
            case TREAT_DISEASE, SAVE_DYING_PLANTS, CLEAN ->
                    StorageType.TOOL_STORAGE;
            case PLANT -> StorageType.SEED_STORAGE;
            case HARVEST, MOVE_BATCH, LOAD_DRYING_RACK ->
                    StorageType.FRESH_HARVEST_STORAGE;
            case UNLOAD_DRYING_RACK, PACKAGE ->
                    StorageType.DRY_PRODUCT_STORAGE;
            case INSPECT -> null;
        };
        if (storageType == null) {
            return Optional.empty();
        }
        return farms.storages(farmId).stream()
                .filter(FarmStorage::enabled)
                .filter(storage -> storage.type() == storageType)
                .sorted(Comparator.comparing(storage -> storage.storageId().toString()))
                .filter(storage -> container(storage.position()).isPresent())
                .findFirst();
    }

    public Optional<Location> storageLocation(FarmStorage storage) {
        requireMainThread();
        return location(storage.position()).map(value -> value.add(0.5, 0.0, 0.5));
    }

    public boolean hasRequiredResource(
            UUID farmId,
            WorkerTask task
    ) {
        requireMainThread();
        if (task.type() == WorkerTaskType.UNLOAD_DRYING_RACK) {
            return processing != null && processing.batch(task.target().targetId())
                    .map(batch -> batch.state() == BatchState.DRIED
                            || batch.state() == BatchState.SPOILED).orElse(false);
        }
        Optional<FarmStorage> storage = requiredStorage(farmId, task.type());
        if (storage.isEmpty()) {
            return !new WorkerStateMachine().needsStorage(task.type());
        }
        Optional<Container> holder = container(storage.get().position());
        if (holder.isEmpty()) {
            return false;
        }
        Inventory inventory = holder.get().getInventory();
        return switch (task.type()) {
            case WATER -> findResource(inventory, WATER_TOOLS,
                    Set.of(Material.WATER_BUCKET, Material.POTION)).isPresent();
            case TREAT_DISEASE, SAVE_DYING_PLANTS ->
                    findResource(inventory, TREATMENT_TOOLS,
                            Set.of(Material.HONEY_BOTTLE, Material.BONE_MEAL))
                            .isPresent();
            case CLEAN -> findResource(inventory, CLEANING_TOOLS,
                    Set.of(Material.BRUSH)).isPresent();
            case PLANT -> findResource(inventory, SEEDS,
                    Set.of(Material.WHEAT_SEEDS)).isPresent();
            case MOVE_BATCH, LOAD_DRYING_RACK, UNLOAD_DRYING_RACK, PACKAGE ->
                    findBatch(task.target().targetId(), farmId).isPresent();
            case HARVEST, INSPECT -> true;
        };
    }

    public CompletableFuture<ActionResult> execute(
            WorkerRecord worker,
            WorkerTaskRepository.TaskLease lease
    ) {
        requireMainThread();
        Objects.requireNonNull(worker, "worker");
        Objects.requireNonNull(lease, "lease");
        WorkerTask task = lease.task().task();
        if (!task.workerUuid()
                .map(worker.worker().workerUuid()::equals)
                .orElse(false)) {
            return CompletableFuture.completedFuture(
                    ActionResult.failure("reservation-expired")
            );
        }
        return switch (task.type()) {
            case INSPECT -> CompletableFuture.completedFuture(inspect(worker, task));
            case WATER -> water(worker, task);
            case TREAT_DISEASE -> care(worker, task, CareAction.TREAT);
            case SAVE_DYING_PLANTS ->
                    care(worker, task, CareAction.REPAIR_AND_RELIEVE_STRESS);
            case CLEAN -> care(worker, task, CareAction.CLEAN);
            case HARVEST -> harvest(worker, task);
            case MOVE_BATCH -> CompletableFuture.completedFuture(moveBatch(worker, task));
            case LOAD_DRYING_RACK -> loadDrying(worker, task);
            case UNLOAD_DRYING_RACK -> unloadDrying(worker, task);
            case PACKAGE -> packageBatch(worker, task);
            case PLANT -> plant(worker, task);
        };
    }

    private ActionResult inspect(WorkerRecord worker, WorkerTask task) {
        Optional<Plant> target = plantTarget(worker, task);
        if (target.isEmpty()) {
            return ActionResult.failure("target-unavailable");
        }
        Plant plant = target.get();
        return ActionResult.success(
                3L,
                Map.of(
                        "plant", plant.uniqueId().toString(),
                        "stage", plant.growthStage().name(),
                        "health", plant.health(),
                        "hydration", plant.hydration(),
                        "disease", plant.diseaseId().orElse("none")
                )
        );
    }

    private CompletableFuture<ActionResult> water(
            WorkerRecord worker,
            WorkerTask task
    ) {
        Optional<Plant> target = plantTarget(worker, task);
        if (target.isEmpty()) {
            return CompletableFuture.completedFuture(
                    ActionResult.failure("target-unavailable")
            );
        }
        Optional<ReservedResource> resource = takeResource(
                worker.worker().farmId(),
                task,
                WATER_TOOLS,
                Set.of(Material.WATER_BUCKET, Material.POTION)
        );
        if (resource.isEmpty()) {
            return CompletableFuture.completedFuture(
                    ActionResult.failure("missing-resource")
            );
        }
        YamlConfiguration yaml = configs.get("plants.yml");
        double amount = yaml.getDouble("care.watering-can-hydration", 28.0);
        double efficiency = worker.worker().specialization().bonusFor(task.type());
        return plants.waterByWorker(
                        target.get().uniqueId(),
                        worker.worker().workerUuid(),
                        amount,
                        Math.min(2.0, efficiency)
                )
                .thenApply(updated -> {
                    finishResource(resource.get(), true);
                    return ActionResult.success(
                            8L,
                            Map.of("hydration", updated.hydration())
                    );
                })
                .exceptionally(failure -> {
                    finishResource(resource.get(), false);
                    return ActionResult.failure("target-unavailable");
                });
    }

    private CompletableFuture<ActionResult> care(
            WorkerRecord worker,
            WorkerTask task,
            CareAction action
    ) {
        Optional<Plant> target = plantTarget(worker, task);
        if (target.isEmpty()) {
            return CompletableFuture.completedFuture(
                    ActionResult.failure("target-unavailable")
            );
        }
        Set<CustomItemType> custom = action == CareAction.CLEAN
                ? CLEANING_TOOLS
                : TREATMENT_TOOLS;
        Set<Material> vanilla = action == CareAction.CLEAN
                ? Set.of(Material.BRUSH)
                : Set.of(Material.HONEY_BOTTLE, Material.BONE_MEAL);
        Optional<ReservedResource> resource = takeResource(
                worker.worker().farmId(),
                task,
                custom,
                vanilla
        );
        if (resource.isEmpty()) {
            return CompletableFuture.completedFuture(
                    ActionResult.failure("missing-resource")
            );
        }
        YamlConfiguration yaml = configs.get("plants.yml");
        double amount = switch (action) {
            case TREAT -> yaml.getDouble(
                    "care.treatment-spray-disease-reduction",
                    45.0
            );
            case CLEAN -> yaml.getDouble(
                    "care.cleaning-kit-cleanliness",
                    35.0
            );
            case FEED -> yaml.getDouble(
                    "care.nutrient-pack-nutrition",
                    24.0
            );
            case REPAIR_AND_RELIEVE_STRESS -> yaml.getDouble(
                    "care.repair-kit-condition",
                    40.0
            );
        };
        double efficiency = Math.min(
                2.0,
                worker.worker().specialization().bonusFor(task.type())
        );
        return plants.careByWorker(
                        target.get().uniqueId(),
                        worker.worker().workerUuid(),
                        action,
                        amount,
                        efficiency
                )
                .thenApply(updated -> {
                    finishResource(resource.get(), true);
                    return ActionResult.success(
                            10L,
                            Map.of(
                                    "health", updated.health(),
                                    "cleanliness", updated.cleanliness()
                            )
                    );
                })
                .exceptionally(failure -> {
                    finishResource(resource.get(), false);
                    return ActionResult.failure("target-unavailable");
                });
    }

    private CompletableFuture<ActionResult> harvest(
            WorkerRecord worker,
            WorkerTask task
    ) {
        Optional<Plant> target = plantTarget(worker, task);
        if (target.isEmpty() || !harvestable(target.get())) {
            return CompletableFuture.completedFuture(
                    ActionResult.failure("target-unavailable"));
        }
        Optional<FarmStorage> destination = reserveHarvestStorage(
                worker.worker().farmId());
        if (destination.isEmpty()) {
            return CompletableFuture.completedFuture(
                    ActionResult.failure("harvest-storage-full"));
        }
        FarmStorage storage = destination.get();
        CompletableFuture<ActionResult> delivery = plants.harvestByWorker(
                        target.get().uniqueId(),
                        worker.worker().workerUuid(),
                        worker.worker().farmId(),
                        storage.storageId(),
                        deliveries)
                .thenCompose(harvested -> onMainComposed(() -> deliverHarvest(storage, harvested)));
        return delivery.whenComplete((ignored, failure) -> onMainNow(() ->
                        releaseHarvestStorage(storage.storageId())))
                .exceptionally(failure ->
                        ActionResult.failure("target-unavailable"));
    }

    private Optional<FarmStorage> reserveHarvestStorage(UUID farmId) {
        requireMainThread();
        for (FarmStorage storage : farms.storages(farmId).stream()
                .filter(FarmStorage::enabled)
                .filter(value -> value.type() == StorageType.FRESH_HARVEST_STORAGE)
                .sorted(Comparator.comparing(value -> value.storageId().toString()))
                .toList()) {
            Optional<Container> holder = container(storage.position());
            if (holder.isEmpty()) {
                continue;
            }
            int free = freeSlots(holder.get().getInventory());
            int reserved = harvestReservations.getOrDefault(storage.storageId(), 0);
            if (free <= reserved) {
                continue;
            }
            harvestReservations.put(storage.storageId(), reserved + 1);
            return Optional.of(storage);
        }
        return Optional.empty();
    }

    private void releaseHarvestStorage(UUID storageId) {
        int current = harvestReservations.getOrDefault(storageId, 0);
        if (current <= 1) {
            harvestReservations.remove(storageId);
        } else {
            harvestReservations.put(storageId, current - 1);
        }
    }

    private int freeSlots(Inventory inventory) {
        int free = 0;
        for (ItemStack item : inventory.getStorageContents()) {
            if (item == null || item.getType().isAir()) {
                free++;
            }
        }
        return free;
    }

    private CompletableFuture<Optional<ItemStack>> issueDeliveryItem(
            PlantRepository.HarvestedBatch harvested
    ) {
        ProductBatch batch = harvested.batch();
        WorkerHarvestDeliveryRepository.HarvestDelivery delivery =
                harvested.delivery();
        return secureItems.registryRecord(delivery.itemInstanceId()).thenCompose(existing -> onMainComposed(() -> {
            if (existing.isPresent()) {
                ItemRegistryRecord record = existing.orElseThrow();
                if (record.state() == ItemRegistryState.CONSUMED || record.state() == ItemRegistryState.RESERVED) {
                    // A downstream durable operation already owns/used this identity.
                    // Reissuing it would create a ghost copy in the harvest storage.
                    return deliveries.markDelivered(delivery.deliveryId(), java.time.Instant.now())
                            .thenCompose(ignored -> onMainComposed(() -> {
                                secureItems.endDelivery(delivery.itemInstanceId());
                                return CompletableFuture.completedFuture(Optional.<ItemStack>empty());
                            }));
                }
                if (record.state() != ItemRegistryState.ACTIVE
                        || !Objects.equals(record.batchId(), batch.batchId())
                        || !Objects.equals(record.ownerUuid(), batch.ownerUuid())
                        || !record.itemType().equals(CustomItemType.FRESH_BATCH.name())) {
                    return CompletableFuture.failedFuture(new IllegalStateException(
                            "Harvest output registry identity is unavailable or inconsistent"));
                }
                // The batch may have evolved since the crash. The persisted item
                // payload (especially quality and createdAt) must remain exact.
                SignedItemData data = new SignedItemData(CustomItemType.valueOf(record.itemType()),
                        record.itemId(), record.instanceId(), record.batchId(), record.ownerUuid(),
                        Objects.requireNonNull(record.quality(), "Harvest registry quality"),
                        record.strainId(), record.createdAt(), record.schemaVersion());
                return CompletableFuture.completedFuture(Optional.of(secureItems.recreate(
                        plugin.getServer().getConsoleSender(), data, batch)));
            }
            return secureItems.issueDeterministic(
                    plugin.getServer().getConsoleSender(), CustomItemType.FRESH_BATCH,
                    batch.ownerUuid(), batch, delivery.itemInstanceId(), delivery.itemCreatedAt())
                    .thenApply(Optional::of);
        }));
    }

    private CompletableFuture<ActionResult> deliverHarvest(
            FarmStorage destination,
            PlantRepository.HarvestedBatch harvested
    ) {
        requireMainThread();
        UUID deliveryId = harvested.delivery().deliveryId();
        if (!harvestDeliveriesInFlight.add(deliveryId)) return CompletableFuture.completedFuture(
                ActionResult.success(16L, Map.of("delivery", "pending")));
        CompletableFuture<ActionResult> result;
        try {
            result = issueDeliveryItem(harvested).thenCompose(item -> item.isPresent()
                    ? onMainComposed(() -> deliverBatch(destination, item.orElseThrow(), harvested))
                    : CompletableFuture.completedFuture(ActionResult.success(16L,
                            Map.of("batch", harvested.batch().batchId().toString(), "delivery", "already-used"))));
        } catch (Throwable failure) {
            result = CompletableFuture.failedFuture(failure);
        }
        return result.whenComplete((ignored, failure) -> onMainNow(() -> harvestDeliveriesInFlight.remove(deliveryId)));
    }

    private CompletableFuture<ActionResult> deliverBatch(
            FarmStorage destination,
            ItemStack item,
            PlantRepository.HarvestedBatch harvested
    ) {
        WorkerHarvestDeliveryRepository.HarvestDelivery delivery =
                harvested.delivery();
        ProductBatch batch = harvested.batch();
        Optional<Container> holder = container(destination.position());
        if (holder.isEmpty()) {
            return CompletableFuture.completedFuture(
                    ActionResult.success(16L, Map.of(
                            "batch", batch.batchId().toString(),
                            "delivery", "pending")));
        }
        Inventory inventory = holder.get().getInventory();
        boolean present = containsInstance(inventory, delivery.itemInstanceId());
        if (!present && inventory.firstEmpty() < 0) {
            return CompletableFuture.completedFuture(
                    ActionResult.success(16L, Map.of(
                            "batch", batch.batchId().toString(),
                            "delivery", "pending")));
        }
        secureItems.beginDelivery(delivery.itemInstanceId());
        if (!present) {
            Map<Integer, ItemStack> overflow = inventory.addItem(item);
            if (!overflow.isEmpty()) {
                return CompletableFuture.completedFuture(
                        ActionResult.success(16L, Map.of(
                                "batch", batch.batchId().toString(),
                                "delivery", "pending")));
            }
        }
        return storageSaves.save(destination.position(), () -> container(destination.position())
                        .map(current -> containsInstance(current.getInventory(), delivery.itemInstanceId()))
                        .orElse(false))
                .thenCompose(saved -> deliveries.markDelivered(delivery.deliveryId(), java.time.Instant.now()))
                .thenCompose(ignored -> onMain(() -> {
                    secureItems.endDelivery(delivery.itemInstanceId());
                    return ActionResult.success(16L, Map.of(
                            "batch", batch.batchId().toString(),
                            "quality", batch.qualityScore(),
                            "weight", batch.freshWeight(),
                            "delivery", "delivered"));
                }));
    }

    private boolean containsInstance(Inventory inventory, UUID instanceId) {
        for (ItemStack existing : inventory.getStorageContents()) {
            if (existing == null || existing.getType().isAir()) {
                continue;
            }
            ItemSigner.Verification verification = secureItems.verify(existing);
            if (verification.valid()
                    && instanceId.equals(
                    verification.data().uniqueInstanceId())) {
                return true;
            }
        }
        return false;
    }

    /** Retries durable deliveries after a full container or server restart. */
    public void recoverPendingDeliveries() {
        requireMainThread();
        if (recoveringDeliveries) {
            return;
        }
        recoveringDeliveries = true;
        CompletableFuture<Void> harvested = deliveries.pending().thenCompose(rows -> {
            CompletableFuture<?>[] futures = rows.stream()
                    .map(this::recoverDelivery)
                    .toArray(CompletableFuture[]::new);
            return CompletableFuture.allOf(futures);
        });
        CompletableFuture<Void> processed = processing == null
                ? CompletableFuture.completedFuture(null)
                : processing.transfers().pending().thenCompose(rows -> {
                    CompletableFuture<?>[] futures = rows.stream()
                            .map(delivery -> onMainComposed(() -> deliverProcessingOutput(delivery)))
                            .toArray(CompletableFuture[]::new);
                    return CompletableFuture.allOf(futures);
                });
        CompletableFuture.allOf(harvested, processed).whenComplete((ignored, failure) -> onMainNow(() -> {
            recoveringDeliveries = false;
            if (failure != null) {
                plugin.getLogger().log(
                        java.util.logging.Level.WARNING,
                        "Worker harvest delivery recovery failed",
                        failure);
            }
        }));
    }

    private CompletableFuture<Void> recoverDelivery(
            WorkerHarvestDeliveryRepository.HarvestDelivery delivery
    ) {
        Optional<FarmStorage> storage = farms.storages(delivery.farmId()).stream()
                .filter(value -> value.storageId().equals(delivery.storageId()))
                .findFirst();
        if (storage.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return plants.batch(delivery.batchId()).thenCompose(optional -> {
            if (optional.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            ProductBatch batch = optional.get();
            PlantRepository.HarvestedBatch harvested =
                    new PlantRepository.HarvestedBatch(batch, delivery);
            return onMainComposed(() -> deliverHarvest(storage.get(), harvested))
                    .thenApply(ignored -> null);
        });
    }

    private CompletableFuture<ActionResult> plant(
            WorkerRecord worker,
            WorkerTask task
    ) {
        if (task.target().position().isEmpty()) {
            return CompletableFuture.completedFuture(
                    ActionResult.failure("target-unavailable")
            );
        }
        Optional<Location> target = location(task.target().position().get());
        if (target.isEmpty()) {
            return CompletableFuture.completedFuture(
                    ActionResult.failure("target-unavailable")
            );
        }
        Optional<ReservedResource> resource = takeResource(
                worker.worker().farmId(),
                task,
                SEEDS,
                Set.of()
        );
        if (resource.isEmpty()) {
            return CompletableFuture.completedFuture(
                    ActionResult.failure("missing-resource")
            );
        }
        Block station = target.get().getBlock();
        return plants.plantByWorker(
                        worker.worker().workerUuid(),
                        worker.worker().ownerUuid(),
                        worker.worker().farmId(),
                        station,
                        resource.get().item()
                )
                .thenApply(planted -> {
                    finishResource(resource.get(), true);
                    return ActionResult.success(
                            14L,
                            Map.of(
                                    "plant", planted.uniqueId().toString(),
                                    "strain", planted.strainId()
                            )
                    );
                })
                .exceptionally(failure -> {
                    finishResource(resource.get(), false);
                    return ActionResult.failure("target-unavailable");
                });
    }

    private ActionResult moveBatch(WorkerRecord worker, WorkerTask task) {
        Optional<InventoryItem> source = findBatch(
                task.target().targetId(),
                worker.worker().farmId()
        );
        if (source.isEmpty()) {
            return ActionResult.failure("missing-resource");
        }
        Optional<Container> destination = destination(worker, task);
        if (destination.isEmpty() || destination.get().getInventory().firstEmpty() < 0) {
            return ActionResult.failure("target-unavailable");
        }
        InventoryItem selected = source.get();
        ItemStack current = selected.inventory().getItem(selected.slot());
        if (current == null || current.getType().isAir()
                || secureItems.verify(current).valid() == false) {
            return ActionResult.failure("missing-resource");
        }
        ItemStack moved = current.clone();
        moved.setAmount(1);
        if (current.getAmount() == 1) {
            selected.inventory().setItem(selected.slot(), null);
        } else {
            current.setAmount(current.getAmount() - 1);
            selected.inventory().setItem(selected.slot(), current);
        }
        Map<Integer, ItemStack> overflow =
                destination.get().getInventory().addItem(moved);
        if (!overflow.isEmpty()) {
            selected.inventory().addItem(moved);
            return ActionResult.failure("target-unavailable");
        }
        long experience = switch (task.type()) {
            case PACKAGE -> 12L;
            case LOAD_DRYING_RACK, UNLOAD_DRYING_RACK -> 10L;
            default -> 6L;
        };
        return ActionResult.success(
                experience,
                Map.of("batch", task.target().targetId().toString())
        );
    }

    private CompletableFuture<ActionResult> loadDrying(WorkerRecord worker, WorkerTask task) {
        if (processing == null || task.target().position().isEmpty()) {
            return CompletableFuture.completedFuture(ActionResult.failure("target-unavailable"));
        }
        Optional<InventoryItem> source = findBatch(task.target().targetId(), worker.worker().farmId());
        Optional<ProcessingManager.Station> station = location(task.target().position().get())
                .filter(value -> value.getWorld().isChunkLoaded(value.getBlockX() >> 4, value.getBlockZ() >> 4))
                .flatMap(value -> processing.station(value.getBlock()));
        // A restarted task may still see the consumed physical reference in storage.
        boolean loaded = processing.stationSnapshots(
                            farms.get(worker.worker().farmId()).orElseThrow(), ProcessingStationType.DRYING_RACK)
                    .stream().anyMatch(snapshot -> snapshot.batchId().filter(task.target().targetId()::equals)
                            .isPresent() && snapshot.ownerUuid().filter(worker.worker().ownerUuid()::equals).isPresent());
        if (loaded) {
            source.ifPresent(selected -> {
                ItemSigner.Verification old = secureItems.verify(selected.inventory().getItem(selected.slot()));
                if (old.valid() && old.data().itemType() == CustomItemType.FRESH_BATCH) {
                    removeStorageInstance(worker.worker().farmId(), old.data().uniqueInstanceId());
                }
            });
            return CompletableFuture.completedFuture(ActionResult.success(10L,
                    Map.of("batch", task.target().targetId().toString())));
        }
        if (source.isEmpty() || station.isEmpty()) {
            return CompletableFuture.completedFuture(ActionResult.failure("missing-resource"));
        }
        InventoryItem selected = source.get();
        ItemStack input = selected.inventory().getItem(selected.slot());
        ItemSigner.Verification verification = secureItems.verify(input);
        if (!verification.valid() || input.getAmount() != 1) {
            return CompletableFuture.completedFuture(ActionResult.failure("missing-resource"));
        }
        UUID instance = verification.data().uniqueInstanceId();
        return processing.loadDryingByWorker(input.clone(), worker.worker().ownerUuid(),
                        station.get().stationId())
                .thenCompose(batch -> onMain(() -> {
                    removeStorageInstance(worker.worker().farmId(), instance);
                    return ActionResult.success(10L, Map.of("batch", batch.batchId().toString()));
                }));
    }

    private CompletableFuture<ActionResult> unloadDrying(WorkerRecord worker, WorkerTask task) {
        if (processing == null || task.target().position().isEmpty()) {
            return CompletableFuture.completedFuture(ActionResult.failure("target-unavailable"));
        }
        String operation = "worker-unload:" + task.taskId();
        return processing.transfers().find(operation).thenCompose(existing -> onMainComposed(() -> {
            if (existing.isPresent()) return deliverProcessingOutput(existing.get());
            Optional<FarmStorage> destination = outputStorage(worker.worker().farmId(), StorageType.DRY_PRODUCT_STORAGE);
            Optional<ProcessingManager.Station> station = location(task.target().position().get())
                    .filter(value -> value.getWorld().isChunkLoaded(value.getBlockX() >> 4, value.getBlockZ() >> 4))
                    .flatMap(value -> processing.station(value.getBlock()));
            if (destination.isEmpty() || station.isEmpty()) {
                return CompletableFuture.completedFuture(ActionResult.failure("target-unavailable"));
            }
            return processing.unloadDryingByWorker(station.get().stationId(), task.target().targetId(),
                            worker.worker().ownerUuid(), worker.worker().farmId(), destination.get().storageId(), operation)
                    .thenCompose(delivery -> onMainComposed(() -> deliverProcessingOutput(delivery)));
        }));
    }

    private CompletableFuture<ActionResult> packageBatch(WorkerRecord worker, WorkerTask task) {
        if (processing == null) return CompletableFuture.completedFuture(ActionResult.failure("target-unavailable"));
        String operation = "worker-package:" + task.taskId();
        return processing.transfers().find(operation).thenCompose(existing -> onMainComposed(() -> {
            if (existing.isPresent()) return deliverProcessingOutput(existing.get());
            Optional<InventoryItem> source = findBatch(task.target().targetId(), worker.worker().farmId());
            Optional<FarmStorage> destination = outputStorage(worker.worker().farmId(), StorageType.PACKAGED_PRODUCT_STORAGE);
            if (source.isEmpty() || destination.isEmpty()) {
                return CompletableFuture.completedFuture(ActionResult.failure("missing-resource"));
            }
            ItemStack item = source.get().inventory().getItem(source.get().slot());
            ItemSigner.Verification verified = secureItems.verify(item);
            if (!verified.valid() || item.getAmount() != 1) {
                return CompletableFuture.completedFuture(ActionResult.failure("missing-resource"));
            }
            UUID instance = verified.data().uniqueInstanceId();
            return secureItems.validateActive(item.clone(), Set.of(CustomItemType.DRY_BATCH, CustomItemType.CURED_BATCH),
                            worker.worker().ownerUuid())
                    .thenCompose(input -> processing.transfers().packageBatch(input.identity(),
                            worker.worker().ownerUuid(), worker.worker().farmId(), destination.get().storageId(),
                            operation, java.time.Instant.now()))
                    .thenCompose(delivery -> onMainComposed(() -> {
                        removeStorageInstance(worker.worker().farmId(), instance);
                        return deliverProcessingOutput(delivery);
                    }));
        }));
    }

    private CompletableFuture<ActionResult> deliverProcessingOutput(ProcessingTransferService.Delivery delivery) {
        requireMainThread();
        if (delivery.delivered()) {
            secureItems.endDelivery(delivery.data().uniqueInstanceId());
            return CompletableFuture.completedFuture(
                    ActionResult.success(10L, Map.of("batch", delivery.data().batchId().toString())));
        }
        if (!processingDeliveriesInFlight.add(delivery.operationId())) {
            return CompletableFuture.completedFuture(ActionResult.failure("target-unavailable"));
        }
        CompletableFuture<ActionResult> result;
        try {
            Optional<FarmStorage> storage = farms.storages(delivery.farmId()).stream()
                    .filter(FarmStorage::enabled)
                    .filter(value -> value.storageId().equals(delivery.storageId())).findFirst();
            Optional<Container> holder = storage.flatMap(value -> container(value.position()));
            if (delivery.itemState() == ItemRegistryState.CONSUMED
                    || delivery.itemState() == ItemRegistryState.RESERVED) {
                result = processing.transfers().delivered(delivery.operationId(), java.time.Instant.now())
                        .thenCompose(ignored -> onMain(() -> {
                            secureItems.endDelivery(delivery.data().uniqueInstanceId());
                            return ActionResult.success(10L, Map.of());
                        }));
            } else if (delivery.itemState() != ItemRegistryState.ACTIVE) {
                result = CompletableFuture.failedFuture(new IllegalStateException(
                        "Processing output registry identity is unavailable"));
            } else if (holder.isEmpty()) {
                result = CompletableFuture.completedFuture(ActionResult.failure("target-unavailable"));
            } else {
                Inventory inventory = holder.get().getInventory();
                boolean present = containsInstance(inventory, delivery.data().uniqueInstanceId());
                if (!present && inventory.firstEmpty() < 0) {
                    result = CompletableFuture.completedFuture(ActionResult.failure("harvest-storage-full"));
                } else {
                    ProductBatch batch = processing.batch(delivery.data().batchId()).orElseThrow();
                    secureItems.beginDelivery(delivery.data().uniqueInstanceId());
                    if (!present) inventory.setItem(inventory.firstEmpty(), secureItems.recreate(
                            plugin.getServer().getConsoleSender(), delivery.data(), batch));
                    BlockPosition position = storage.orElseThrow().position();
                    result = storageSaves.save(position, () -> container(position)
                                    .map(current -> containsInstance(current.getInventory(),
                                            delivery.data().uniqueInstanceId())).orElse(false))
                            .thenCompose(saved -> processing.transfers().delivered(
                                    delivery.operationId(), java.time.Instant.now()))
                            .thenCompose(ignored -> onMain(() -> {
                                secureItems.endDelivery(delivery.data().uniqueInstanceId());
                                return ActionResult.success(10L, Map.of("batch", batch.batchId().toString()));
                            }));
                }
            }
        } catch (Throwable failure) {
            result = CompletableFuture.failedFuture(failure);
        }
        return result.whenComplete((ignored, failure) -> onMainNow(() ->
                processingDeliveriesInFlight.remove(delivery.operationId())));
    }

    private Optional<FarmStorage> outputStorage(UUID farmId, StorageType type) {
        return farms.storages(farmId).stream().filter(FarmStorage::enabled)
                .filter(storage -> storage.type() == type)
                .sorted(Comparator.comparing(storage -> storage.storageId().toString()))
                .filter(storage -> container(storage.position())
                        .map(holder -> holder.getInventory().firstEmpty() >= 0).orElse(false)).findFirst();
    }

    private void removeStorageInstance(UUID farmId, UUID instanceId) {
        for (FarmStorage storage : farms.storages(farmId)) {
            container(storage.position()).ifPresent(holder -> {
                Inventory inventory = holder.getInventory();
                for (int slot = 0; slot < inventory.getSize(); slot++) {
                    ItemSigner.Verification verified = secureItems.verify(inventory.getItem(slot));
                    if (verified.valid() && verified.data().uniqueInstanceId().equals(instanceId)) {
                        inventory.setItem(slot, null);
                    }
                }
            });
        }
    }

    /** Bounded scans of linked farm inventories, never the world or unloaded chunks. */
    public List<ProcessingTask> processingTasks(Farm farm, int maximum) {
        requireMainThread();
        if (processing == null || maximum < 1) return List.of();
        List<ProcessingTask> result = new ArrayList<>();
        List<ProcessingManager.StationSnapshot> racks = processing.stationSnapshots(farm, ProcessingStationType.DRYING_RACK);
        if (outputStorage(farm.farmId(), StorageType.DRY_PRODUCT_STORAGE).isPresent()) {
            for (ProcessingManager.StationSnapshot rack : racks) {
                if (result.size() >= maximum) break;
                if (rack.exists() && rack.status() == ProcessingManager.StationStatus.READY
                        && rack.ownerUuid().filter(farm.ownerUuid()::equals).isPresent()) {
                    UUID batch = rack.batchId().orElseThrow();
                    result.add(processingTask(WorkerTaskType.UNLOAD_DRYING_RACK, batch,
                            position(rack.station()), rack.station().stationId().toString()));
                }
            }
        }
        List<ProcessingManager.StationSnapshot> free = new ArrayList<>(racks.stream()
                .filter(ProcessingManager.StationSnapshot::exists)
                .filter(rack -> rack.status() == ProcessingManager.StationStatus.FREE).toList());
        Set<UUID> seen = new java.util.HashSet<>();
        Optional<FarmStorage> packaged = outputStorage(farm.farmId(), StorageType.PACKAGED_PRODUCT_STORAGE);
        for (FarmStorage storage : farms.storages(farm.farmId()).stream().filter(FarmStorage::enabled)
                .sorted(Comparator.comparing(value -> value.storageId().toString())).toList()) {
            Optional<Container> holder = container(storage.position());
            if (holder.isEmpty()) continue;
            for (ItemStack item : holder.get().getInventory().getContents()) {
                if (result.size() >= maximum) return List.copyOf(result);
                if (item == null || item.getAmount() != 1) continue;
                ItemSigner.Verification verified = secureItems.verify(item);
                if (!verified.valid() || verified.data().batchId() == null
                        || !farm.ownerUuid().equals(verified.data().ownerUuid())
                        || !seen.add(verified.data().batchId())) continue;
                UUID batch = verified.data().batchId();
                if (storage.type() == StorageType.FRESH_HARVEST_STORAGE
                        && verified.data().itemType() == CustomItemType.FRESH_BATCH && !free.isEmpty()
                        && processing.batch(batch).map(value -> value.state() == BatchState.FRESH).orElse(false)) {
                    ProcessingManager.StationSnapshot rack = free.removeFirst();
                    result.add(processingTask(WorkerTaskType.LOAD_DRYING_RACK, batch,
                            position(rack.station()), rack.station().stationId().toString()));
                } else if (storage.type() == StorageType.DRY_PRODUCT_STORAGE && packaged.isPresent()
                        && (verified.data().itemType() == CustomItemType.DRY_BATCH
                        || verified.data().itemType() == CustomItemType.CURED_BATCH)
                        && processing.batch(batch).map(value -> value.state() == BatchState.DRIED
                        || value.state() == BatchState.CURED).orElse(false)) {
                    result.add(processingTask(WorkerTaskType.PACKAGE, batch,
                            packaged.get().position(), "package"));
                }
            }
        }
        return List.copyOf(result);
    }

    private ProcessingTask processingTask(WorkerTaskType type, UUID batch, BlockPosition position, String suffix) {
        return new ProcessingTask(type, new TaskTarget("batch", batch, Optional.of(position)),
                "auto:" + type.name().toLowerCase(java.util.Locale.ROOT) + ':' + batch + ':' + suffix);
    }

    private BlockPosition position(ProcessingManager.Station station) {
        return new BlockPosition(station.position().worldUuid(), station.position().x(),
                station.position().y(), station.position().z());
    }

    public record ProcessingTask(WorkerTaskType type, TaskTarget target, String idempotencyKey) { }

    private Optional<Container> destination(
            WorkerRecord worker,
            WorkerTask task
    ) {
        if (task.target().position().isPresent()) {
            Optional<Container> explicit = container(task.target().position().get());
            if (explicit.isPresent()) {
                return explicit;
            }
        }
        StorageType type = switch (task.type()) {
            case UNLOAD_DRYING_RACK -> StorageType.DRY_PRODUCT_STORAGE;
            case PACKAGE -> StorageType.PACKAGED_PRODUCT_STORAGE;
            case MOVE_BATCH -> StorageType.GENERAL_STORAGE;
            default -> null;
        };
        if (type == null) {
            return Optional.empty();
        }
        return farms.storages(worker.worker().farmId()).stream()
                .filter(FarmStorage::enabled)
                .filter(storage -> storage.type() == type)
                .sorted(Comparator.comparing(storage -> storage.storageId().toString()))
                .map(FarmStorage::position)
                .map(this::container)
                .flatMap(Optional::stream)
                .findFirst();
    }

    private Optional<Plant> plantTarget(WorkerRecord worker, WorkerTask task) {
        if (!task.target().targetType().equals("plant")) {
            return Optional.empty();
        }
        return plants.plant(task.target().targetId())
                .filter(plant -> plant.farmId()
                        .map(worker.worker().farmId()::equals)
                        .orElse(false));
    }

    private boolean harvestable(Plant plant) {
        return plant.growthStage() == GrowthStage.READY;
    }

    private Optional<ReservedResource> takeResource(
            UUID farmId,
            WorkerTask task,
            Set<CustomItemType> customTypes,
            Set<Material> vanillaTypes
    ) {
        Optional<FarmStorage> storage = requiredStorage(farmId, task.type());
        if (storage.isEmpty()) {
            return Optional.empty();
        }
        Optional<Container> holder = container(storage.get().position());
        if (holder.isEmpty()) {
            return Optional.empty();
        }
        Optional<Integer> found = findResource(
                holder.get().getInventory(),
                customTypes,
                vanillaTypes
        );
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Inventory inventory = holder.get().getInventory();
        ItemStack current = inventory.getItem(found.get());
        if (current == null) {
            return Optional.empty();
        }
        ItemSigner.Verification verification = secureItems.verify(current);
        boolean reusable = verification.valid()
                && customTypes.contains(verification.data().itemType())
                && verification.data().itemType() != CustomItemType.SEED;
        ItemStack reserved = current.clone();
        reserved.setAmount(1);
        if (!reusable) {
            if (current.getAmount() == 1) {
                inventory.setItem(found.get(), null);
            } else {
                current.setAmount(current.getAmount() - 1);
                inventory.setItem(found.get(), current);
            }
        }
        return Optional.of(new ReservedResource(
                inventory,
                reserved,
                reusable,
                storage.get().position()
        ));
    }

    private void finishResource(ReservedResource resource, boolean success) {
        Runnable action = () -> {
            if (!success && !resource.reusable()) {
                Map<Integer, ItemStack> overflow =
                        resource.inventory().addItem(resource.item());
                overflow.values().forEach(item ->
                        location(resource.storagePosition()).ifPresent(location ->
                                location.getWorld().dropItemNaturally(location, item)));
            }
        };
        if (plugin.getServer().isPrimaryThread()) {
            action.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, action);
        }
    }

    private Optional<Integer> findResource(
            Inventory inventory,
            Set<CustomItemType> customTypes,
            Set<Material> vanillaTypes
    ) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            ItemSigner.Verification verified = secureItems.verify(item);
            if (verified.valid()
                    && customTypes.contains(verified.data().itemType())) {
                return Optional.of(slot);
            }
            if (!verified.valid() && vanillaTypes.contains(item.getType())) {
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }

    private Optional<InventoryItem> findBatch(UUID batchId, UUID farmId) {
        Collection<FarmStorage> candidates = farmId == null
                ? farms.all().stream()
                .flatMap(farm -> farms.storages(farm.farmId()).stream())
                .toList()
                : farms.storages(farmId);
        List<FarmStorage> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparing(storage -> storage.storageId().toString()));
        for (FarmStorage storage : ordered) {
            Optional<Container> holder = container(storage.position());
            if (holder.isEmpty()) {
                continue;
            }
            Inventory inventory = holder.get().getInventory();
            for (int slot = 0; slot < inventory.getSize(); slot++) {
                ItemStack item = inventory.getItem(slot);
                if (item == null || item.getType().isAir()) {
                    continue;
                }
                ItemSigner.Verification verified = secureItems.verify(item);
                if (verified.valid()
                        && BATCH_ITEMS.contains(verified.data().itemType())
                        && batchId.equals(verified.data().batchId())) {
                    return Optional.of(new InventoryItem(
                            inventory,
                            slot,
                            location(storage.position()).orElseThrow()
                    ));
                }
            }
        }
        return Optional.empty();
    }

    private Optional<Container> container(BlockPosition position) {
        Optional<Location> location = location(position);
        if (location.isEmpty()) {
            return Optional.empty();
        }
        World world = location.get().getWorld();
        if (world == null || !world.isChunkLoaded(
                position.x() >> 4,
                position.z() >> 4)) {
            return Optional.empty();
        }
        Block block = world.getBlockAt(position.x(), position.y(), position.z());
        return block.getState() instanceof Container container
                ? Optional.of(container)
                : Optional.empty();
    }

    private Optional<Location> location(BlockPosition position) {
        World world = plugin.getServer().getWorld(position.worldUuid());
        return world == null
                ? Optional.empty()
                : Optional.of(new Location(
                        world,
                        position.x(),
                        position.y(),
                        position.z()
                ));
    }

    private <T> CompletableFuture<T> onMainComposed(
            java.util.function.Supplier<CompletableFuture<T>> action
    ) {
        if (plugin.getServer().isPrimaryThread()) {
            try {
                return action.get();
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                action.get().whenComplete((result, failure) -> {
                    if (failure == null) future.complete(result);
                    else future.completeExceptionally(failure);
                });
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        });
        return future;
    }

    private void onMainNow(Runnable action) {
        if (plugin.getServer().isPrimaryThread()) action.run();
        else plugin.getServer().getScheduler().runTask(plugin, action);
    }

    private CompletableFuture<ActionResult> onMain(
            java.util.function.Supplier<ActionResult> action
    ) {
        if (plugin.getServer().isPrimaryThread()) {
            try {
                return CompletableFuture.completedFuture(action.get());
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
        CompletableFuture<ActionResult> future = new CompletableFuture<>();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                future.complete(action.get());
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        });
        return future;
    }

    private void requireMainThread() {
        if (!plugin.getServer().isPrimaryThread()) {
            throw new IllegalStateException(
                    "Worker world actions must run on the server thread"
            );
        }
    }

    public record ActionResult(
            boolean success,
            String reason,
            long experience,
            Map<String, Object> report
    ) {
        public ActionResult {
            reason = Objects.requireNonNull(reason, "reason");
            if (experience < 0L) {
                throw new IllegalArgumentException("Experience cannot be negative");
            }
            report = Map.copyOf(Objects.requireNonNull(report, "report"));
        }

        public static ActionResult success(
                long experience,
                Map<String, ?> report
        ) {
            Map<String, Object> values = new java.util.LinkedHashMap<>();
            report.forEach(values::put);
            return new ActionResult(true, "completed", experience, values);
        }

        public static ActionResult failure(String reason) {
            return new ActionResult(false, reason, 0L, Map.of());
        }
    }

    private record ReservedResource(
            Inventory inventory,
            ItemStack item,
            boolean reusable,
            BlockPosition storagePosition
    ) {
        private ReservedResource {
            Objects.requireNonNull(inventory, "inventory");
            item = Objects.requireNonNull(item, "item").clone();
            Objects.requireNonNull(storagePosition, "storagePosition");
        }

        @Override
        public ItemStack item() {
            return item.clone();
        }
    }

    private record InventoryItem(
            Inventory inventory,
            int slot,
            Location location
    ) {
        private InventoryItem {
            Objects.requireNonNull(inventory, "inventory");
            if (slot < 0 || slot >= inventory.getSize()) {
                throw new IllegalArgumentException("Invalid inventory slot");
            }
            location = Objects.requireNonNull(location, "location").clone();
        }

        @Override
        public Location location() {
            return location.clone();
        }
    }
}
