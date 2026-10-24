package com.verdantvice.clientprobe;

import com.verdantvice.VerdantVicePlugin;
import com.verdantvice.api.event.VerdantWorkerTaskEvent;
import com.verdantvice.common.BlockPosition;
import com.verdantvice.farm.Farm;
import com.verdantvice.farm.StorageLinkResult;
import com.verdantvice.farm.StorageType;
import com.verdantvice.herbalist.HerbalistTier;
import com.verdantvice.plant.Plant;
import com.verdantvice.security.CustomItemType;
import com.verdantvice.worker.TaskTarget;
import com.verdantvice.worker.WorkerManager;
import com.verdantvice.worker.WorkerPaymentRepository;
import com.verdantvice.worker.WorkerRecord;
import com.verdantvice.worker.WorkerState;
import com.verdantvice.worker.WorkerTaskRepository;
import com.verdantvice.worker.WorkerTaskStatus;
import com.verdantvice.worker.WorkerTaskType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Real worker scheduler/pathfinder acceptance, restricted to the isolated client server. */
final class WorkerGameplayAcceptance {
    private static final int STARTING_EMERALDS = 32;
    private static final int STARTING_WATER = 8;
    private static final String STATE_FILE = "worker-gameplay-state.yml";
    private static final EnumSet<WorkerState> REQUIRED_EVENTS = EnumSet.of(
            WorkerState.WALKING_TO_STORAGE, WorkerState.COLLECTING_RESOURCE,
            WorkerState.WALKING_TO_TARGET, WorkerState.WATERING, WorkerState.SEARCHING_TASK);

    private WorkerGameplayAcceptance() {}

    static CompletableFuture<Void> run(JavaPlugin probe, VerdantVicePlugin target,
            Player player, Consumer<String> check) {
        return main(probe, () -> prepare(probe, target, player, check))
                .thenCompose(fixture -> fixture.run());
    }

    static CompletableFuture<Void> verifyRestart(JavaPlugin probe, VerdantVicePlugin target,
            Player player, Consumer<String> check) {
        return main(probe, () -> {
            requireIsolated(target, player);
            File file = stateFile(probe);
            require(file.isFile(), "Completed worker acceptance state exists before restart verification");
            YamlConfiguration state = YamlConfiguration.loadConfiguration(file);
            require(state.getBoolean("completed"), "Worker acceptance completed before restart");
            require(player.getUniqueId().toString().equals(state.getString("owner")),
                    "Worker restart uses the same connected player");
            UUID workerId = uuid(state, "worker");
            WorkerRecord worker = target.workers().worker(workerId).orElseThrow(() ->
                    new IllegalStateException("Persisted worker was not restored into the production manager"));
            World world = Bukkit.getWorld(uuid(state, "world"));
            require(world != null && player.getWorld().equals(world), "Worker restart restores the same world UUID");
            require(worker.worker().farmId().equals(uuid(state, "farm")), "Worker remains assigned to the persisted farm");
            require(worker.entityUuid().equals(uuid(state, "entity")), "Worker retains the same villager UUID");
            require(Bukkit.getEntity(worker.entityUuid()) instanceof Villager entity
                            && entity.isValid() && target.workers().isWorker(entity),
                    "Actual worker villager and worker marker survive server restart");
            Plant plant = target.plants().plant(uuid(state, "plant")).orElseThrow(() ->
                    new IllegalStateException("Watered plant was not restored"));
            require(plant.hydration() > state.getDouble("hydration-before"), "Worker watering survives server restart");
            require(plant.workerAssigned().filter(workerId::equals).isPresent(),
                    "Watered plant retains its worker assignment after restart");
            require(world.getFullTime() / 24000L == state.getLong("salary-day"),
                    "Restart salary check remains in the tested Minecraft day");
            check.accept("same worker, villager, farm and watered plant survive a real process restart");
            return new RestartState(state, world, workerId);
        }).thenCompose(saved -> afterTicks(probe, 100L).thenCompose(ignored ->
                readEvidence(target, uuid(saved.state(), "task"), saved.workerId())
                        .thenCombine(target.secureItems().registryRecord(uuid(saved.state(), "contract")),
                                (evidence, contract) -> new RestartEvidence(saved, evidence,
                                        contract.map(record -> record.state().name()).orElse("MISSING")))))
                .thenCompose(result -> main(probe, () -> {
                    YamlConfiguration state = result.saved().state();
                    validatePaid(result.evidence(), result.saved().workerId(), state.getLong("salary-day"),
                            state.getInt("salary-amount"));
                    require(result.contractState().equals("CONSUMED"), "Hiring contract stays consumed after restart");
                    require(count(result.saved().world().getBlockAt(102, state.getInt("y"), 90), Material.EMERALD)
                                    == state.getInt("expected-emeralds"),
                            "Restarted scheduler does not debit the same Minecraft-day salary twice");
                    require(count(result.saved().world().getBlockAt(102, state.getInt("y"), 92), Material.WATER_BUCKET)
                                    == state.getInt("expected-water"),
                            "Completed watering task does not consume water again after restart");
                    check.accept("completed WATER task, one PAID salary and exact storage balances remain unchanged after restart");
                    return null;
                }));
    }

    private static Fixture prepare(JavaPlugin probe, VerdantVicePlugin target, Player player,
            Consumer<String> check) {
        requireIsolated(target, player);
        require(!stateFile(probe).exists(), "Worker acceptance fixtures are not silently recreated");
        Farm farm = target.farms().owned(player.getUniqueId()).orElseThrow(() ->
                new IllegalStateException("Gameplay farm must exist before worker acceptance"));
        require(target.workers().workers(farm.farmId()).isEmpty(), "Worker acceptance starts with no hired workers");
        World world = player.getWorld();
        int y = world.getHighestBlockYAt(102, 90) + 1;
        for (int z : new int[] {90, 92, 94}) {
            require(farm.region().contains(new BlockPosition(world.getUID(), 102, y, z)),
                    "Worker fixture lies inside the owned farm");
            require(world.getBlockAt(102, y, z).getType().isAir(), "Worker fixture does not overwrite an occupied block");
        }
        Block salary = world.getBlockAt(102, y, 90);
        salary.setType(Material.BARREL, false);
        ((Container) salary.getState()).getInventory().setItem(0, new ItemStack(Material.EMERALD, STARTING_EMERALDS));
        Block water = world.getBlockAt(102, y, 92);
        water.setType(Material.BARREL, false);
        Inventory inventory = ((Container) water.getState()).getInventory();
        for (int slot = 0; slot < STARTING_WATER; slot++) inventory.setItem(slot, new ItemStack(Material.WATER_BUCKET));
        Block pot = world.getBlockAt(102, y, 94);
        pot.setType(Material.DECORATED_POT, false);
        target.plants().markStation(pot);
        long day = world.getFullTime() / 24000L;
        world.setFullTime(day * 24000L + 2000L);
        return new Fixture(probe, target, player, check, farm, world, salary, water, pot, day);
    }

    private static final class Fixture {
        private final JavaPlugin probe;
        private final VerdantVicePlugin target;
        private final Player player;
        private final Consumer<String> check;
        private final Farm farm;
        private final World world;
        private final Block salary;
        private final Block water;
        private final Block pot;
        private final long day;
        private Villager villager;
        private UUID seedId;
        private UUID contractId;
        private Plant planted;
        private WorkerRecord hired;
        private UUID taskId;
        private double maximumMovement;
        private long deadline;
        private final Listener taskListener = new Listener() {};
        private final EnumSet<WorkerState> taskEvents = EnumSet.noneOf(WorkerState.class);

        private Fixture(JavaPlugin probe, VerdantVicePlugin target, Player player, Consumer<String> check,
                Farm farm, World world, Block salary, Block water, Block pot, long day) {
            this.probe = probe;
            this.target = target;
            this.player = player;
            this.check = check;
            this.farm = farm;
            this.world = world;
            this.salary = salary;
            this.water = water;
            this.pot = pot;
            this.day = day;
        }

        private CompletableFuture<Void> run() {
            CompletableFuture<Void> operation = main(probe, () -> {
                        Bukkit.getPluginManager().registerEvent(VerdantWorkerTaskEvent.class,
                                taskListener, EventPriority.MONITOR, (listener, event) -> {
                                    VerdantWorkerTaskEvent taskEvent = (VerdantWorkerTaskEvent) event;
                                    if (taskId != null && taskId.equals(taskEvent.taskId())
                                            && hired.worker().workerUuid().equals(taskEvent.workerId())) {
                                        require(Bukkit.isPrimaryThread(), "Actual worker task events run on the main thread");
                                        taskEvents.add(taskEvent.state());
                                    }
                                }, probe);
                        return target.farms().linkStorage(player, farm.farmId(), salary, StorageType.SALARY_STORAGE);
                    })
                    .thenCompose(future -> future).thenCompose(linked -> main(probe, () -> {
                        require(linked.status() == StorageLinkResult.Status.LINKED, "Actual salary barrel links through FarmManager: " + linked.status());
                        return target.farms().linkStorage(player, farm.farmId(), water, StorageType.WATER_STORAGE);
                    })).thenCompose(future -> future).thenCompose(linked -> main(probe, () -> {
                        require(linked.status() == StorageLinkResult.Status.LINKED, "Actual water barrel links through FarmManager: " + linked.status());
                        check.accept("salary and water barrels are linked through production farm persistence");
                        return target.secureItems().issue(player, CustomItemType.SEED, player.getUniqueId(), "berry_fog");
                    })).thenCompose(future -> future).thenCompose(seed -> main(probe, () -> {
                        seedId = target.secureItems().verify(seed).data().uniqueInstanceId();
                        require(player.getInventory().addItem(seed).isEmpty(), "Player inventory accepts worker fixture seed");
                        return target.plants().plant(player, pot, seed);
                    })).thenCompose(future -> future).thenCompose(plant -> main(probe, () -> {
                        planted = plant;
                        require(plant.farmId().filter(farm.farmId()::equals).isPresent(), "Watering target belongs to the existing farm");
                        villager = world.spawn(new Location(world, 100.5, pot.getY(), 90.5), Villager.class);
                        villager.setAdult();
                        villager.setProfession(Villager.Profession.FARMER);
                        villager.setPersistent(true);
                        villager.setRemoveWhenFarAway(false);
                        villager.setInvulnerable(true);
                        return target.herbalists().forceCreate(villager, true, Optional.of(HerbalistTier.VERDANT_EXPERT));
                    })).thenCompose(future -> future).thenCompose(evaluation -> main(probe, () -> {
                        require(evaluation.herbalist().filter(record -> record.tier() == HerbalistTier.VERDANT_EXPERT).isPresent(),
                                "Real farmer becomes an eligible expert herbalist before hiring");
                        return target.secureItems().issue(player, CustomItemType.PLANTATION_CONTRACT, player.getUniqueId(), "");
                    })).thenCompose(future -> future).thenCompose(contract -> main(probe, () -> {
                        contractId = target.secureItems().verify(contract).data().uniqueInstanceId();
                        ItemStack previous = player.getInventory().getItemInMainHand();
                        if (!previous.getType().isAir()) {
                            int empty = player.getInventory().firstEmpty();
                            require(empty >= 0, "Inventory has space to preserve the item held before hiring");
                            player.getInventory().setItem(empty, previous.clone());
                        }
                        player.getInventory().setItemInMainHand(contract);
                        return target.workers().hire(player, villager);
                    })).thenCompose(future -> future).thenCompose(result -> main(probe, () -> {
                        require(result.status() == WorkerManager.HireStatus.HIRED, "Production worker hire succeeds: " + result.status());
                        hired = result.worker().orElseThrow();
                        require(hired.entityUuid().equals(villager.getUniqueId()) && target.workers().isWorker(villager),
                                "Hired worker retains the real villager UUID and persistent marker");
                        check.accept("expert herbalist is hired using an authentic signed plantation contract");
                        return target.secureItems().registryRecord(contractId);
                    })).thenCompose(future -> future).thenCompose(contract -> main(probe, () -> {
                        require(contract.isPresent() && contract.get().state().name().equals("CONSUMED"), "Hiring consumes the contract registry identity");
                        require(!contains(player, contractId, target), "Hiring removes the consumed physical contract");
                        return target.workers().submitTask(farm.farmId(), WorkerTaskType.WATER,
                                new TaskTarget("plant", planted.uniqueId(), Optional.of(planted.position())),
                                Duration.ofSeconds(90), "client-acceptance-water:" + planted.uniqueId());
                    })).thenCompose(future -> future).thenCompose(task -> main(probe, () -> {
                        taskId = task.task().taskId();
                        deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
                        CompletableFuture<Void> finished = new CompletableFuture<>();
                        poll(finished);
                        return finished;
                    })).thenCompose(future -> future).thenCompose(ignored -> afterTicks(probe, 60L))
                    .thenCompose(ignored -> readEvidence(target, taskId, hired.worker().workerUuid()))
                    .thenCompose(evidence -> main(probe, () -> {
                        validatePaid(evidence, hired.worker().workerUuid(), day, hired.worker().salaryPerMinecraftDay());
                        require(count(salary, Material.EMERALD) == STARTING_EMERALDS - hired.worker().salaryPerMinecraftDay(),
                                "Salary balance remains stable after further scheduler ticks");
                        require(count(water, Material.WATER_BUCKET) == STARTING_WATER - 1, "Exactly one water resource was consumed");
                        check.accept("actual worker AI navigated, watered once and completed the persisted task");
                        check.accept("production task events include storage navigation, resource collection, target navigation, watering and completion");
                        check.accept("one PAID salary ledger debited the linked emerald barrel exactly once");
                        save();
                        return null;
                    }));
            return operation.whenComplete((ignored, failure) -> main(probe, () -> {
                HandlerList.unregisterAll(taskListener);
                return null;
            }));
        }

        private void poll(CompletableFuture<Void> finished) {
            if (finished.isDone()) return;
            try {
                require(player.isOnline() && villager.isValid(), "Connected player and actual worker remain available");
                maximumMovement = Math.max(maximumMovement, villager.getLocation().distanceSquared(
                        new Location(world, 100.5, pot.getY(), 90.5)));
                readEvidence(target, taskId, hired.worker().workerUuid()).whenComplete((evidence, failure) ->
                        main(probe, () -> {
                            if (failure != null) { finished.completeExceptionally(failure); return null; }
                            try {
                                Optional<WorkerTaskRepository.StoredTask> task = evidence.task();
                                if (task.isPresent() && task.get().task().status() == WorkerTaskStatus.COMPLETED
                                        && evidence.payments().size() == 1
                                        && evidence.payments().getFirst().status() == WorkerPaymentRepository.PaymentStatus.PAID
                                        && taskEvents.containsAll(REQUIRED_EVENTS)) {
                                    validatePaid(evidence, hired.worker().workerUuid(), day, hired.worker().salaryPerMinecraftDay());
                                    Plant current = target.plants().plant(planted.uniqueId()).orElseThrow();
                                    require(current.hydration() > planted.hydration(), "Actual worker watering increases hydration");
                                    require(current.workerAssigned().filter(hired.worker().workerUuid()::equals).isPresent(),
                                            "Watered plant records the actual hired worker");
                                    require(maximumMovement > 0.25, "Worker moved through the real Paper pathfinder");
                                    finished.complete(null);
                                } else if (System.nanoTime() >= deadline) {
                                    throw new IllegalStateException("Worker acceptance timed out: task="
                                            + task.map(value -> value.task().status().name()).orElse("MISSING")
                                            + ", worker=" + target.workers().worker(hired.worker().workerUuid())
                                                    .map(value -> value.worker().state().name()).orElse("MISSING")
                                            + ", events=" + taskEvents + ", payments=" + evidence.payments()
                                            + ", emeralds=" + count(salary, Material.EMERALD));
                                } else {
                                    Bukkit.getScheduler().runTaskLater(probe, () -> poll(finished), 20L);
                                }
                            } catch (Throwable error) { finished.completeExceptionally(error); }
                            return null;
                        }).exceptionally(error -> { finished.completeExceptionally(error); return null; }));
            } catch (Throwable error) { finished.completeExceptionally(error); }
        }

        private void save() {
            try {
                YamlConfiguration state = new YamlConfiguration();
                state.set("completed", true);
                state.set("owner", player.getUniqueId().toString());
                state.set("world", world.getUID().toString());
                state.set("farm", farm.farmId().toString());
                state.set("worker", hired.worker().workerUuid().toString());
                state.set("entity", villager.getUniqueId().toString());
                state.set("plant", planted.uniqueId().toString());
                state.set("seed", seedId.toString());
                state.set("contract", contractId.toString());
                state.set("task", taskId.toString());
                state.set("y", pot.getY());
                state.set("salary-day", day);
                state.set("salary-amount", hired.worker().salaryPerMinecraftDay());
                state.set("expected-emeralds", STARTING_EMERALDS - hired.worker().salaryPerMinecraftDay());
                state.set("expected-water", STARTING_WATER - 1);
                state.set("hydration-before", planted.hydration());
                state.set("hydration-after", target.plants().plant(planted.uniqueId()).orElseThrow().hydration());
                state.set("maximum-movement-squared", maximumMovement);
                state.set("task-events", taskEvents.stream().map(Enum::name).toList());
                state.set("completed-utc", Instant.now().toString());
                player.saveData();
                world.save();
                require(probe.getDataFolder().isDirectory() || probe.getDataFolder().mkdirs(), "Probe state directory exists");
                state.save(stateFile(probe));
            } catch (Exception failure) { throw new IllegalStateException("Cannot persist worker acceptance evidence", failure); }
        }
    }

    private static CompletableFuture<Evidence> readEvidence(VerdantVicePlugin target, UUID task, UUID worker) {
        return new WorkerTaskRepository(target.database()).find(task).thenCombine(
                new WorkerPaymentRepository(target.database()).history(worker, 10), Evidence::new);
    }

    private static void validatePaid(Evidence evidence, UUID worker, long day, int salary) {
        WorkerTaskRepository.StoredTask task = evidence.task().orElseThrow(() -> new IllegalStateException("Worker task row is missing"));
        require(task.task().status() == WorkerTaskStatus.COMPLETED, "Persisted worker task is COMPLETED");
        require(task.task().workerUuid().filter(worker::equals).isPresent(), "Actual hired worker completed the task");
        require(evidence.payments().size() == 1, "Exactly one salary ledger row exists for the newly hired worker");
        WorkerPaymentRepository.PaymentRecord payment = evidence.payments().getFirst();
        require(payment.status() == WorkerPaymentRepository.PaymentStatus.PAID && payment.paidAt().isPresent(), "Salary ledger is durably PAID");
        require(payment.amount() == salary && payment.currency().equals("EMERALD"), "Salary ledger uses the configured emerald amount");
        require(payment.operationId().equals("worker-salary:" + worker + ':' + day), "Salary ledger retains the tested worker/day operation identity");
    }

    private static boolean contains(Player player, UUID instance, VerdantVicePlugin target) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType().isAir()) continue;
            var verification = target.secureItems().verify(item);
            if (verification.valid() && verification.data().uniqueInstanceId().equals(instance)) return true;
        }
        return false;
    }

    private static int count(Block block, Material material) {
        require(block.getState() instanceof Container, "Fixture storage remains a real container");
        int total = 0;
        for (ItemStack item : ((Container) block.getState()).getInventory().getContents()) {
            if (item != null && item.getType() == material) total += item.getAmount();
        }
        return total;
    }

    private static void requireIsolated(VerdantVicePlugin target, Player player) {
        require(Bukkit.isPrimaryThread(), "Worker world checks run on the main server thread");
        require(player.isOnline(), "Worker acceptance uses a connected real player");
        require(Bukkit.getIp().equals("127.0.0.1"), "Worker fixtures are restricted to a loopback server");
        require(target.getServer().getWorldContainer().toPath().toAbsolutePath().normalize().toString()
                        .replace('\\', '/').contains("/build/minecraft-client/"),
                "Worker fixtures are restricted to the isolated client acceptance directory");
    }

    private static File stateFile(JavaPlugin probe) { return new File(probe.getDataFolder(), STATE_FILE); }
    private static UUID uuid(YamlConfiguration state, String key) {
        return UUID.fromString(java.util.Objects.requireNonNull(state.getString(key), "Missing worker state key: " + key));
    }
    private static void require(boolean condition, String description) {
        if (!condition) throw new IllegalStateException(description);
    }

    private static CompletableFuture<Void> afterTicks(JavaPlugin probe, long ticks) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        try { Bukkit.getScheduler().runTaskLater(probe, () -> future.complete(null), ticks); }
        catch (Throwable failure) { future.completeExceptionally(failure); }
        return future;
    }

    private static <T> CompletableFuture<T> main(JavaPlugin probe, Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Runnable action = () -> {
            try { future.complete(supplier.get()); }
            catch (Throwable failure) { future.completeExceptionally(failure); }
        };
        try { if (Bukkit.isPrimaryThread()) action.run(); else Bukkit.getScheduler().runTask(probe, action); }
        catch (Throwable failure) { future.completeExceptionally(failure); }
        return future;
    }

    private record Evidence(Optional<WorkerTaskRepository.StoredTask> task,
            List<WorkerPaymentRepository.PaymentRecord> payments) {}
    private record RestartState(YamlConfiguration state, World world, UUID workerId) {}
    private record RestartEvidence(RestartState saved, Evidence evidence, String contractState) {}
}
