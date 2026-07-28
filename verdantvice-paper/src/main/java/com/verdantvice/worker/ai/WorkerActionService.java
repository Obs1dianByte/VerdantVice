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
import com.verdantvice.security.CustomItemType;
import com.verdantvice.security.ItemSigner;
import com.verdantvice.security.SecureItemService;
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

    public WorkerActionService(
            JavaPlugin plugin,
            ConfigManager configs,
            FarmManager farms,
            PlantManager plants,
            SecureItemService secureItems
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.farms = Objects.requireNonNull(farms, "farms");
        this.plants = Objects.requireNonNull(plants, "plants");
        this.secureItems = Objects.requireNonNull(secureItems, "secureItems");
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
            case MOVE_BATCH, LOAD_DRYING_RACK, UNLOAD_DRYING_RACK, PACKAGE ->
                    CompletableFuture.completedFuture(moveBatch(worker, task));
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
        if (target.isEmpty()) {
            return CompletableFuture.completedFuture(
                    ActionResult.failure("target-unavailable")
            );
        }
        if (!harvestable(target.get())) {
            return CompletableFuture.completedFuture(
                    ActionResult.failure("target-unavailable")
            );
        }
        Optional<FarmStorage> destination = farms.storages(
                        worker.worker().farmId()
                ).stream()
                .filter(FarmStorage::enabled)
                .filter(storage -> storage.type() == StorageType.FRESH_HARVEST_STORAGE)
                .filter(storage -> container(storage.position())
                        .map(value -> value.getInventory().firstEmpty() >= 0)
                        .orElse(false))
                .sorted(Comparator.comparing(storage -> storage.storageId().toString()))
                .findFirst();
        if (destination.isEmpty()) {
            return CompletableFuture.completedFuture(
                    ActionResult.failure("missing-resource")
            );
        }
        return plants.harvestByWorker(
                        target.get().uniqueId(),
                        worker.worker().workerUuid()
                )
                .thenCompose(batch -> secureItems.issue(
                                plugin.getServer().getConsoleSender(),
                                CustomItemType.FRESH_BATCH,
                                worker.worker().ownerUuid(),
                                batch
                        )
                        .thenCompose(item -> onMain(() ->
                                deliverBatch(destination.get(), item, batch))))
                .exceptionally(failure ->
                        ActionResult.failure("target-unavailable"));
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

    private ActionResult deliverBatch(
            FarmStorage destination,
            ItemStack item,
            ProductBatch batch
    ) {
        Optional<Container> container = container(destination.position());
        if (container.isEmpty()) {
            location(destination.position()).ifPresent(location ->
                    location.getWorld().dropItemNaturally(location, item));
            return ActionResult.failure("target-unavailable");
        }
        Map<Integer, ItemStack> overflow =
                container.get().getInventory().addItem(item);
        overflow.values().forEach(leftover ->
                location(destination.position()).ifPresent(location ->
                        location.getWorld().dropItemNaturally(location, leftover)));
        return ActionResult.success(
                16L,
                Map.of(
                        "batch", batch.batchId().toString(),
                        "quality", batch.qualityScore(),
                        "weight", batch.freshWeight()
                )
        );
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
        return plant.growthStage() != GrowthStage.SEED
                && plant.growthStage() != GrowthStage.SPROUT
                && plant.growthStage() != GrowthStage.SEEDLING
                && plant.growthStage() != GrowthStage.SPOILED;
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
