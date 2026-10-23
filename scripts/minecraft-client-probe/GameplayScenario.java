package com.verdantvice.clientprobe;

import com.verdantvice.VerdantVicePlugin;
import com.verdantvice.contract.*;
import com.verdantvice.farm.Farm;
import com.verdantvice.herbalist.HerbalistRecord;
import com.verdantvice.herbalist.HerbalistTier;
import com.verdantvice.plant.GrowthStage;
import com.verdantvice.plant.Plant;
import com.verdantvice.processing.BatchState;
import com.verdantvice.processing.ProcessingManager;
import com.verdantvice.processing.ProcessingMenuHolder;
import com.verdantvice.processing.ProcessingStationType;
import com.verdantvice.processing.RollingMenuHolder;
import com.verdantvice.processing.QualityGrade;
import com.verdantvice.security.CustomItemType;
import com.verdantvice.security.SignedItemData;
import com.verdantvice.strain.Strain;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Test-only acceptance driver. Uses the connected CraftPlayer and production managers.
 * SERVER_API steps are deliberately distinguished from packets sent by the real client.
 * No fake player, synthetic click event, replacement game logic or production world is used.
 */
final class GameplayScenario {
    private final JavaPlugin probe;
    private final VerdantVicePlugin target;
    private final BiConsumer<Boolean, String> check;
    private final BiConsumer<String, Throwable> failure;
    private final Consumer<String> event;
    private final UUID bootId = UUID.randomUUID();
    private final List<UUID> plants = new ArrayList<>();
    private final List<UUID> batches = new ArrayList<>();
    private final List<ProcessingManager.Station> drying = new ArrayList<>();
    private final List<ProcessingManager.Station> curing = new ArrayList<>();
    private ProcessingManager.Station grinder;
    private Farm farm;
    private Location origin;
    private UUID joint;
    private UUID worker;
    private UUID merchant;
    private UUID child;
    private int phase;
    private boolean busy;
    private double hydration;
    private double tolerance;
    private int wateringInteractions;
    private final List<Integer> actualRollingSlots = new ArrayList<>();
    private final Map<String, Object> originalProcessing = new LinkedHashMap<>();
    private Object originalPlantPeriod;
    private Object originalProblemChance;

    GameplayScenario(JavaPlugin probe, VerdantVicePlugin target, BiConsumer<Boolean, String> check,
            BiConsumer<String, Throwable> failure, Consumer<String> event) {
        this.probe = probe;
        this.target = target;
        this.check = check;
        this.failure = failure;
        this.event = event;
    }

    boolean started() { return phase > 0 || busy; }
    boolean readyForRestart() { return phase == 6; }
    boolean completed() { return phase == 7; }
    int phase() { return phase; }

    void command(Player player, String requested) {
        String action = requested.toLowerCase(Locale.ROOT);
        if (busy) { message(player, "Scenario operation is still running; please wait."); return; }
        switch (action) {
            case "setup" -> run(player, "setup", () -> setup(player));
            case "process" -> run(player, "processing", () -> process(player));
            case "rolled" -> run(player, "rolling", () -> rolled(player));
            case "used" -> run(player, "effects and tolerance", () -> used(player));
            case "economy" -> run(player, "trade, contracts, genetics and worker", () -> economy(player));
            case "restart" -> run(player, "restart persistence", () -> restart(player));
            default -> message(player, "Gameplay phase=" + phase + ". Commands: setup, process, rolled, used, economy, restart.");
        }
    }

    private void run(Player player, String description, Supplier<CompletableFuture<Void>> action) {
        busy = true;
        event.accept("SCENARIO_START " + description);
        CompletableFuture<Void> operation;
        try { operation = action.get(); }
        catch (Throwable error) { operation = CompletableFuture.failedFuture(error); }
        operation.whenComplete((ignored, error) -> main(() -> {
            busy = false;
            if (error != null) {
                failure.accept("Gameplay " + description, error);
                message(player, "Gameplay FAILED: " + description + ". Inspect the English report.");
            } else {
                event.accept("SCENARIO_PASS " + description);
                save();
            }
            return null;
        }));
    }

    private CompletableFuture<Void> setup(Player player) {
        require(phase == 0, "gameplay fixture is created only once");
        require(Bukkit.getIp().equals("127.0.0.1"), "gameplay fixture is restricted to a loopback server");
        Path folder = target.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        require(folder.toString().replace('\\', '/').contains("/build/minecraft-client/"),
                "gameplay writes are restricted to an isolated Minecraft client test directory");
        event.accept("FIXTURE balance: one-minute drying/curing, rack speed 4, one-second process updates; production defaults unchanged");
        for (String key : List.of("processing.drying.duration-minutes", "processing.drying.speed",
                "processing.curing.duration-minutes", "processing.update-seconds")) {
            originalProcessing.put(key, target.configs().get("quality.yml").get(key));
        }
        originalPlantPeriod = target.configs().main().get("performance.plant-update-seconds");
        originalProblemChance = target.configs().get("plants.yml").get("problems.chance-per-update");
        target.configs().get("quality.yml").set("processing.drying.duration-minutes", 1);
        target.configs().get("quality.yml").set("processing.drying.speed", 4.0);
        target.configs().get("quality.yml").set("processing.curing.duration-minutes", 1);
        target.configs().get("quality.yml").set("processing.update-seconds", 1);
        target.processing().stop();
        target.processing().start();
        target.configs().main().set("performance.plant-update-seconds", 1);
        target.configs().get("plants.yml").set("problems.chance-per-update", 0.0);
        target.plants().stop();
        target.plants().start();
        player.closeInventory();
        player.getInventory().clear();
        int y = player.getWorld().getHighestBlockYAt(96, 96) + 1;
        origin = new Location(player.getWorld(), 96, y, 96);
        for (int dx = -8; dx <= 8; dx++) for (int dz = -8; dz <= 8; dz++) {
            origin.clone().add(dx, -1, dz).getBlock().setType(Material.STONE, false);
            for (int dy = 0; dy <= 3; dy++) origin.clone().add(dx, dy, dz).getBlock().setType(Material.AIR, false);
        }
        player.teleport(origin.clone().add(0.5, 0, 0.5));
        player.getWorld().setTime(3000);
        return target.farms().create(player, "Acceptance Farm").thenCompose(created -> main(() -> {
            farm = created;
            require(farm.ownerUuid().equals(player.getUniqueId()), "actual player owns the persisted farm");
            for (int index = 0; index < 4; index++) {
                Block rack = origin.clone().add(-6 + index * 3, 0, 5).getBlock();
                rack.setType(ProcessingStationType.DRYING_RACK.material(), false);
                drying.add(target.processing().markStation(rack, ProcessingStationType.DRYING_RACK));
                Block jar = origin.clone().add(-6 + index * 3, 0, 7).getBlock();
                jar.setType(ProcessingStationType.CURING_CONTAINER.material(), false);
                curing.add(target.processing().markStation(jar, ProcessingStationType.CURING_CONTAINER));
            }
            Block grind = origin.clone().add(6, 0, 2).getBlock();
            grind.setType(ProcessingStationType.GRINDER.material(), false);
            grinder = target.processing().markStation(grind, ProcessingStationType.GRINDER);
            return null;
        })).thenCompose(ignored -> {
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (int index = 0; index < 4; index++) {
                final int current = index;
                chain = chain.thenCompose(nothing -> main(() -> target.secureItems().issue(player,
                        CustomItemType.SEED, player.getUniqueId(), current % 2 == 0 ? "berry_fog" : "concrete_kush"))
                        .thenCompose(future -> future)).thenCompose(seed -> main(() -> {
                    player.getInventory().addItem(seed);
                    Block pot = origin.clone().add(-6 + current * 3, 0, -4).getBlock();
                    pot.setType(Material.DECORATED_POT, false);
                    target.plants().markStation(pot);
                    event.accept("SERVER_API plant signed seed " + identity(seed).uniqueInstanceId());
                    return target.plants().plant(player, pot, seed);
                }).thenCompose(future -> future)).thenCompose(plant -> main(() -> {
                    plants.add(plant.uniqueId());
                    require(plant.farmId().filter(farm.farmId()::equals).isPresent(),
                            "planted seed is assigned to the actual player's farm " + current);
                    return null;
                }));
            }
            return chain;
        }).thenCompose(ignored -> main(() -> target.secureItems().issue(player, CustomItemType.WATERING_CAN,
                player.getUniqueId(), "")).thenCompose(future -> future)).thenCompose(can -> main(() -> {
            player.getInventory().setItem(0, can);
            player.getInventory().setHeldItemSlot(0);
            hydration = target.plants().plant(plants.getFirst()).orElseThrow().hydration();
            Location eye = origin.clone().add(-5.5, 0, -1);
            eye.setDirection(origin.clone().add(-5.5, 1.0, -3.5).subtract(eye).toVector());
            player.teleport(eye);
            phase = 1;
            message(player, "Setup PASS. Physically right-click the first plant with the watering can, then /vvclient scenario process.");
            return null;
        }));
    }

    private CompletableFuture<Void> process(Player player) {
        require(phase == 1, "processing follows the prepared farm and real client watering");
        Plant first = target.plants().plant(plants.getFirst()).orElseThrow();
        require(wateringInteractions > 0, "real client sent a physical watering interaction packet");
        require(first.hydration() > hydration, "actual-client watering increased plant hydration");
        event.accept("ACTUAL_CLIENT_OUTCOME watering hydration " + hydration + " -> " + first.hydration());
        player.closeInventory();
        player.getInventory().setHeldItemSlot(8);
        player.getInventory().setItem(8, null);
        player.setSneaking(true);
        List<Strain> originalStrains = List.of(target.strains().require("berry_fog"), target.strains().require("concrete_kush"));
        event.accept("FIXTURE temporarily accelerate only the two in-memory strain growth durations to one second; persisted default configuration and existing harvest windows unchanged");
        originalStrains.forEach(strain -> target.strains().replace(new Strain(strain.id(), strain.displayName(),
                strain.rarity(), Duration.ofSeconds(1), strain.difficulty(), strain.baseYield(), strain.qualityPotential(),
                strain.diseaseResistance(), strain.waterConsumption(), strain.lightPreference(), strain.stressSensitivity(),
                strain.smellIntensity(), strain.effectProfile(), strain.negativeEffectProfile(), strain.seedPrice(),
                strain.baseMarketPrice(), strain.visualModel(), strain.particleProfile())));
        CompletableFuture<Void> chain = await(() -> plants.stream().allMatch(id -> target.plants().plant(id)
                        .map(plant -> plant.growthStage() == GrowthStage.READY && plant.growthProgress() >= 100).orElse(false)),
                12, "production scheduled timestamp growth naturally advances all four plants to READY")
                .whenComplete((ignored, error) -> {
                    originalStrains.forEach(target.strains()::replace);
                    target.configs().main().set("performance.plant-update-seconds", originalPlantPeriod);
                    target.configs().get("plants.yml").set("problems.chance-per-update", originalProblemChance);
                    target.plants().stop();
                    target.plants().start();
                    event.accept("FIXTURE_RESTORED original strain balance and production plant scheduler interval");
                });
        for (UUID plant : plants) chain = chain.thenCompose(ignored -> main(() -> {
            event.accept("SERVER_API production harvest interaction " + plant);
            target.plants().interact(player, plant, EquipmentSlot.HAND);
            return null;
        })).thenCompose(ignored -> await(() -> target.plants().plant(plant).isEmpty(), 10, "harvest removes persisted plant"));
        return chain.thenCompose(ignored -> main(() -> {
            player.setSneaking(false);
            List<ItemStack> fresh = items(player, CustomItemType.FRESH_BATCH);
            require(fresh.size() == 4, "four real harvested batches reached the actual client inventory");
            fresh.forEach(item -> batches.add(identity(item).batchId()));
            return null;
        })).thenCompose(ignored -> processStations(player, CustomItemType.FRESH_BATCH, drying, BatchState.DRIED))
                .thenCompose(ignored -> processStations(player, CustomItemType.DRY_BATCH, curing, BatchState.CURED))
                .thenCompose(ignored -> {
                    CompletableFuture<Void> grinding = CompletableFuture.completedFuture(null);
                    for (UUID batch : List.copyOf(batches.subList(0, 2))) grinding = grinding.thenCompose(nothing -> main(() -> {
                        hold(player, batch, CustomItemType.CURED_BATCH);
                        event.accept("SERVER_API grinder input " + batch);
                        target.processing().handleMenuClick(player, holder(grinder), 11);
                        return null;
                    })).thenCompose(nothing -> await(() -> item(player, batch, CustomItemType.GROUND_PRODUCT) != null,
                            10, "grinding creates a signed ground batch"));
                    return grinding;
                }).thenCompose(ignored -> main(() -> {
                    List<ItemStack> ground = items(player, CustomItemType.GROUND_PRODUCT);
                    require(ground.size() == 2 && items(player, CustomItemType.CURED_BATCH).size() == 2,
                            "all four harvests completed drying and curing; two also completed grinding");
                    moveTo(player, ground.get(0), 0);
                    moveTo(player, ground.get(1), 40);
                    player.getInventory().setHeldItemSlot(0);
                    event.accept("SERVER_API mixHands consumes two separate real harvest batches");
                    return target.processing().mixHands(player);
                }).thenCompose(future -> future)).thenCompose(ignored -> main(() -> {
                    require(items(player, CustomItemType.MIXTURE).size() == 1,
                            "mixing delivered one authenticated mixture to the actual player");
                    return target.secureItems().issue(player, CustomItemType.ROLLING_PAPER, player.getUniqueId(), "");
                }).thenCompose(future -> future)).thenCompose(paper -> main(() -> {
                    player.getInventory().addItem(paper);
                    return target.secureItems().issue(player, CustomItemType.FILTER, player.getUniqueId(), "");
                }).thenCompose(future -> future)).thenCompose(filter -> main(() -> {
                    player.getInventory().addItem(filter);
                    // Hide the two reserve batches from ingredient selection by putting the mixture first.
                    moveTo(player, items(player, CustomItemType.MIXTURE).getFirst(), 0);
                    event.accept("SERVER_API startRolling; the next five clicks must come from the real Minecraft client");
                    return target.processing().startRolling(player);
                }).thenCompose(future -> future)).thenCompose(ignored -> main(() -> {
                    require(player.getOpenInventory().getTopInventory().getHolder(false) instanceof RollingMenuHolder,
                            "actual client has the production rolling minigame open");
                    phase = 2;
                    message(player, "Processing PASS. Click the green buttons in slots 10,12,14,16,13 (about one second each), then /vvclient scenario rolled.");
                    return null;
                }));
    }

    private CompletableFuture<Void> processStations(Player player, CustomItemType input,
            List<ProcessingManager.Station> stations, BatchState expected) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int index = 0; index < batches.size(); index++) {
            UUID batch = batches.get(index);
            ProcessingManager.Station station = stations.get(index);
            chain = chain.thenCompose(ignored -> main(() -> {
                hold(player, batch, input);
                event.accept("SERVER_API station input " + station.type() + " batch=" + batch);
                target.processing().handleMenuClick(player, holder(station), 11);
                return null;
            })).thenCompose(ignored -> await(() -> target.processing().activeProcesses(player.getUniqueId())
                    >= stations.indexOf(station) + 1, 10, "station transaction starts " + station.stationId()));
        }
        return chain.thenCompose(ignored -> await(() -> batches.stream().allMatch(id ->
                target.processing().batch(id).map(batch -> batch.state() == expected).orElse(false)),
                85, "timestamp-based production processes reach " + expected)).thenCompose(ignored -> {
            CompletableFuture<Void> outputs = CompletableFuture.completedFuture(null);
            for (int index = 0; index < batches.size(); index++) {
                UUID batch = batches.get(index);
                ProcessingManager.Station station = stations.get(index);
                outputs = outputs.thenCompose(nothing -> main(() -> {
                    target.processing().handleMenuClick(player, holder(station), 15);
                    return null;
                })).thenCompose(nothing -> await(() -> item(player, batch,
                        expected == BatchState.DRIED ? CustomItemType.DRY_BATCH : CustomItemType.CURED_BATCH) != null,
                        10, "station delivers authenticated output " + batch));
            }
            return outputs;
        });
    }

    private CompletableFuture<Void> rolled(Player player) {
        require(phase == 2, "rolling verification follows completed processing");
        require(actualRollingSlots.equals(List.of(10, 12, 14, 16, 13)),
                "actual Minecraft client sent all five expected rolling inventory clicks in order");
        List<ItemStack> joints = items(player, CustomItemType.JOINT);
        require(joints.size() == 1, "five actual client minigame clicks produced one signed rolled item");
        ItemStack stack = joints.getFirst();
        joint = identity(stack).uniqueInstanceId();
        tolerance = target.profiles().require(player.getUniqueId()).tolerance().level();
        moveTo(player, stack, 0);
        player.getInventory().setHeldItemSlot(0);
        player.closeInventory();
        phase = 3;
        message(player, "Rolling PASS. Physically right-click with the rolled item, wait two seconds, then /vvclient scenario used.");
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> used(Player player) {
        require(phase == 3, "product use follows real-client rolling");
        require(findInstance(player, joint) == null, "actual-client product use removed its exact signed identity");
        require(target.profiles().require(player.getUniqueId()).tolerance().level() > tolerance,
                "production effect use increased and persisted the actual player's tolerance");
        require(!player.getActivePotionEffects().isEmpty(), "actual client received Minecraft potion effects");
        return target.secureItems().registryRecord(joint).thenCompose(record -> main(() -> {
            require(record.orElseThrow().state().name().equals("CONSUMED"), "used product identity is durably CONSUMED");
            event.accept("ACTUAL_CLIENT_OUTCOME product use, potion effects and tolerance");
            originalProcessing.forEach((key, value) -> target.configs().get("quality.yml").set(key, value));
            target.processing().stop();
            target.processing().start();
            event.accept("FIXTURE_RESTORED original processing durations, speed and scheduler interval");
            phase = 4;
            message(player, "Effects PASS. Run /vvclient scenario economy for trade, contract, genetics and worker checks.");
            return null;
        }));
    }

    private CompletableFuture<Void> economy(Player player) {
        require(phase == 4, "economy follows the full production processing and effect flow");
        Villager npc = origin.getWorld().spawn(origin.clone().add(3, 0, -1), Villager.class);
        npc.setProfession(Villager.Profession.FARMER);
        npc.setAI(false);
        return target.herbalists().forceCreate(npc, true, Optional.of(HerbalistTier.VERDANT_EXPERT))
                .thenCompose(evaluation -> main(() -> {
                    HerbalistRecord record = evaluation.herbalist().orElseThrow();
                    merchant = record.herbalistUuid();
                    require(record.tier() == HerbalistTier.VERDANT_EXPERT, "real villager converted to the highest herbalist tier");
                    ItemStack saleItem = items(player, CustomItemType.CURED_BATCH).getFirst();
                    event.accept("SERVER_API prepare and commit genuine processed harvest sale");
                    return target.herbalists().tradeService().prepare(saleItem, player.getUniqueId(), merchant)
                            .thenCompose(sale -> target.herbalists().tradeService().commit(sale, "client-sale:" + sale.quoteId())
                                    .thenCompose(result -> main(() -> {
                                        require(result.committed() && result.payout() > 0, "sale consumes authentic processed batch and records a positive payout");
                                        removeInstance(player, identity(saleItem).uniqueInstanceId());
                                        target.economy().deposit(player, result.payout());
                                        return null;
                                    })));
                }).thenCompose(future -> future)).thenCompose(ignored -> contract(player))
                .thenCompose(ignored -> genetics(player))
                .thenCompose(ignored -> main(() -> WorkerGameplayAcceptance.run(probe, target, player,
                        description -> require(true, description))).thenCompose(future -> future))
                .thenCompose(ignored -> main(() -> {
                    worker = target.workers().workers(farm.farmId()).getFirst().worker().workerUuid();
                    player.saveData();
                    player.getWorld().save();
                    phase = 6;
                    message(player, "Gameplay checkpoint reached. Use /vvclient checkpoint; restart this isolated server, rejoin, then /vvclient scenario restart.");
                    return null;
                }));
    }

    private CompletableFuture<Void> contract(Player player) {
        ItemStack delivery = items(player, CustomItemType.CURED_BATCH).getFirst();
        var batch = target.processing().batch(identity(delivery).batchId()).orElseThrow();
        Contract request = Contract.active(UUID.randomUUID(), player.getUniqueId(), merchant,
                ContractType.CURED_PRODUCT, List.of(new ContractRequirement(
                        Optional.of(batch.strainComposition().keySet().iterator().next()), batch.dryWeight(),
                        QualityGrade.COMMON, true, Optional.empty())), Instant.now().plusSeconds(3600),
                new ContractReward(12, 0, 3), 3, Instant.now());
        PaperContractRepository repository;
        try {
            // A deterministic request fixture is necessary because generated requests randomly
            // select any configured strain. Delivery, authenticity, claim and replay remain production code.
            var field = target.herbalists().contractService().getClass().getDeclaredField("repository");
            field.setAccessible(true);
            repository = (PaperContractRepository) field.get(target.herbalists().contractService());
        } catch (ReflectiveOperationException error) { return CompletableFuture.failedFuture(error); }
        event.accept("FIXTURE deterministic CURED_PRODUCT contract matches the fourth genuine harvest; production repository creates it");
        return repository.create(request, 8).thenCompose(stored -> main(() ->
                target.herbalists().contractService().deliver(request.contractId(), delivery,
                        player.getUniqueId(), "client-contract-delivery:" + request.contractId()))
                .thenCompose(future -> future)).thenCompose(result -> main(() -> {
                    require(result.newlyCompleted() && result.status() == ContractDeliveryResult.Status.APPLIED,
                            "real cured harvest satisfies and durably completes a production contract");
                    removeInstance(player, identity(delivery).uniqueInstanceId());
                    return target.herbalists().contractService().claim(request.contractId(), player.getUniqueId(),
                            "client-contract-claim:" + request.contractId());
                }).thenCompose(future -> future)).thenCompose(claimed -> main(() -> {
                    require(claimed.status() == ContractClaimResult.Status.CLAIMED,
                            "completed contract reward is durably claimed");
                    target.economy().deposit(player, claimed.reward().emeralds());
                    return target.herbalists().contractService().claim(request.contractId(), player.getUniqueId(),
                            "client-contract-claim:" + request.contractId());
                }).thenCompose(future -> future)).thenCompose(replayed -> main(() -> {
                    require(replayed.status() == ContractClaimResult.Status.ALREADY_CLAIMED,
                            "repeated contract reward claim is idempotent and does not pay twice");
                    return (Void) null;
                }));
    }

    private CompletableFuture<Void> genetics(Player player) {
        return main(() -> target.secureItems().issue(player, CustomItemType.SEED, player.getUniqueId(), "berry_fog"))
                .thenCompose(future -> future).thenCompose(first -> main(() -> {
                    player.getInventory().addItem(first);
                    return target.secureItems().issue(player, CustomItemType.SEED, player.getUniqueId(), "concrete_kush")
                            .thenCompose(second -> main(() -> {
                                player.getInventory().addItem(second);
                                event.accept("SERVER_API cross genuine signed parent seeds; custom line is explicitly named by this test fixture");
                                return target.genetics().cross(player, "Acceptance Line", first, second);
                            }).thenCompose(future -> future))
                            .thenCompose(result -> target.genetics().deliverPending(player).thenCompose(count -> main(() -> {
                                child = identity(result.seed()).uniqueInstanceId();
                                require(findInstance(player, child) != null, "genetics delivered the exact committed child identity to the actual client");
                                require(findInstance(player, result.firstSeedId()) == null
                                                && findInstance(player, result.secondSeedId()) == null,
                                        "genetics removed both original parent identities without minting replacements");
                                return (Void) null;
                            })));
                }).thenCompose(future -> future));
    }

    private CompletableFuture<Void> restart(Player player) {
        YamlConfiguration stored = YamlConfiguration.loadConfiguration(probe.getDataFolder().toPath().resolve("gameplay-state.yml").toFile());
        require(stored.getInt("phase") == 6, "a completed gameplay run exists before restart verification");
        require(!bootId.toString().equals(stored.getString("probe-boot-id")),
                "restart verification uses a different actual probe plugin boot");
        UUID farmId = UUID.fromString(stored.getString("farm"));
        UUID workerId = UUID.fromString(stored.getString("worker"));
        UUID childId = UUID.fromString(stored.getString("child"));
        UUID usedId = UUID.fromString(stored.getString("joint"));
        require(target.farms().get(farmId).isPresent(), "farm survives a real Paper process restart");
        require(target.workers().worker(workerId).isPresent(), "hired worker survives a real Paper process restart");
        ItemStack seed = findInstance(player, childId);
        require(seed != null, "exact genetic child UUID survives player and server restart");
        require(target.profiles().require(player.getUniqueId()).tolerance().level() > stored.getDouble("initial-tolerance"),
                "increased tolerance survives a real Paper process restart");
        return target.secureItems().validateActive(seed, Set.of(CustomItemType.SEED), player.getUniqueId())
                .thenCompose(ignored -> target.secureItems().registryRecord(usedId)).thenCompose(record -> main(() -> {
                    require(record.orElseThrow().state().name().equals("CONSUMED"), "consumed rolled identity stays consumed after process restart");
                    return WorkerGameplayAcceptance.verifyRestart(probe, target, player,
                            description -> require(true, description));
                }).thenCompose(future -> future)).thenCompose(ignored -> main(() -> {
                    phase = 7;
                    message(player, "Gameplay restart PASS. Reports contain exact identities and distinguish server API from real client actions.");
                    return null;
                }));
    }

    private void save() {
        if (farm == null) return;
        try {
            YamlConfiguration state = new YamlConfiguration();
            state.set("phase", phase);
            state.set("probe-boot-id", bootId.toString());
            state.set("farm", farm.farmId().toString());
            state.set("worker", worker == null ? null : worker.toString());
            state.set("merchant", merchant == null ? null : merchant.toString());
            state.set("child", child == null ? null : child.toString());
            state.set("joint", joint == null ? null : joint.toString());
            state.set("initial-tolerance", tolerance);
            state.set("updated-utc", Instant.now().toString());
            state.set("source-batches", batches.stream().map(UUID::toString).toList());
            state.save(probe.getDataFolder().toPath().resolve("gameplay-state.yml").toFile());
        } catch (Exception error) { throw new IllegalStateException("Cannot persist gameplay restart evidence", error); }
    }

    private CompletableFuture<Void> await(BooleanSupplier condition, int seconds, String description) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        Runnable poll = new Runnable() {
            @Override public void run() {
                try {
                    if (condition.getAsBoolean()) {
                        require(true, description);
                        future.complete(null);
                    } else if (System.nanoTime() > deadline) future.completeExceptionally(new IllegalStateException("Timed out: " + description));
                    else Bukkit.getScheduler().runTaskLater(probe, this, 5L);
                } catch (Throwable error) { future.completeExceptionally(error); }
            }
        };
        Bukkit.getScheduler().runTask(probe, poll);
        return future;
    }

    private <T> CompletableFuture<T> main(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Runnable action = () -> {
            try { future.complete(supplier.get()); }
            catch (Throwable error) { future.completeExceptionally(error); }
        };
        if (Bukkit.isPrimaryThread()) action.run(); else Bukkit.getScheduler().runTask(probe, action);
        return future;
    }

    private ProcessingMenuHolder holder(ProcessingManager.Station station) {
        return new ProcessingMenuHolder(station.stationId(), station.type());
    }

    void actualInteraction(Player player, String type) {
        if (phase != 1) return;
        var held = target.itemSigner().verify(player.getInventory().getItemInMainHand());
        if (held.valid() && held.data().itemType() == CustomItemType.WATERING_CAN) {
            wateringInteractions++;
            event.accept("ACTUAL_CLIENT_INTERACTION " + type + " held=WATERING_CAN");
        }
    }

    void actualInventoryClick(InventoryClickEvent click) {
        if (phase != 2 || !(click.getWhoClicked() instanceof Player player)) return;
        if (!(click.getView().getTopInventory().getHolder(false) instanceof RollingMenuHolder holder)
                || !holder.playerId().equals(player.getUniqueId()) || click.getRawSlot() < 0
                || click.getRawSlot() >= click.getView().getTopInventory().getSize()) return;
        require(click.isCancelled(), "real rolling GUI click cannot extract signed ingredients");
        actualRollingSlots.add(click.getRawSlot());
        event.accept("ACTUAL_CLIENT_ROLLING_CLICK slot=" + click.getRawSlot() + " click=" + click.getClick());
    }

    private SignedItemData identity(ItemStack stack) {
        var verification = target.itemSigner().verify(stack);
        if (!verification.valid()) throw new IllegalStateException("Invalid signed gameplay item");
        return verification.data();
    }

    private List<ItemStack> items(Player player, CustomItemType type) {
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack == null || stack.getType().isAir()) continue;
            var verified = target.itemSigner().verify(stack);
            if (verified.valid() && verified.data().itemType() == type) result.add(stack.clone());
        }
        return result;
    }

    private ItemStack item(Player player, UUID batch, CustomItemType type) {
        return items(player, type).stream().filter(stack -> batch.equals(identity(stack).batchId())).findFirst().orElse(null);
    }

    private ItemStack findInstance(Player player, UUID instance) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack == null || stack.getType().isAir()) continue;
            var verified = target.itemSigner().verify(stack);
            if (verified.valid() && verified.data().uniqueInstanceId().equals(instance)) return stack.clone();
        }
        return null;
    }

    private void removeInstance(Player player, UUID instance) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack != null && !stack.getType().isAir()) {
                var verified = target.itemSigner().verify(stack);
                if (verified.valid() && instance.equals(verified.data().uniqueInstanceId())) player.getInventory().setItem(slot, null);
            }
        }
    }

    private void moveTo(Player player, ItemStack stack, int slot) {
        ItemStack displaced = player.getInventory().getItem(slot);
        UUID identity = identity(stack).uniqueInstanceId();
        removeInstance(player, identity);
        if (displaced != null && !displaced.getType().isAir()
                && (!target.itemSigner().verify(displaced).valid()
                || !identity.equals(identity(displaced).uniqueInstanceId()))) player.getInventory().addItem(displaced);
        player.getInventory().setItem(slot, stack);
    }

    private void hold(Player player, UUID batch, CustomItemType type) {
        ItemStack input = item(player, batch, type);
        require(input != null, "actual inventory contains expected processing input " + type + " " + batch);
        moveTo(player, input, 0);
        player.getInventory().setHeldItemSlot(0);
    }

    private void require(boolean condition, String description) { check.accept(condition, description); }
    private void message(Player player, String text) { player.sendMessage(Component.text(text)); probe.getLogger().info(text); }
}
