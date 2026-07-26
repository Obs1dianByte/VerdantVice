package com.verdantvice.plant;

import com.verdantvice.api.event.VerdantPlantHarvestEvent;
import com.verdantvice.api.event.VerdantPlantPlacedEvent;
import com.verdantvice.api.event.VerdantPlantStageChangeEvent;
import com.verdantvice.common.BlockPosition;
import com.verdantvice.config.ConfigManager;
import com.verdantvice.farm.FarmManager;
import com.verdantvice.farm.FarmPermission;
import com.verdantvice.genetics.GeneticProfile;
import com.verdantvice.genetics.SeedGeneticsResolver;
import com.verdantvice.harvest.HarvestService;
import com.verdantvice.harvest.HarvestTiming;
import com.verdantvice.harvest.HarvestWindowService;
import com.verdantvice.i18n.TranslationService;
import com.verdantvice.processing.BatchAuthenticityService;
import com.verdantvice.processing.ProductBatch;
import com.verdantvice.plant.growth.GrowthPolicy;
import com.verdantvice.plant.growth.PlantGrowthService;
import com.verdantvice.plant.growth.PlantUpdate;
import com.verdantvice.plant.growth.WateringResult;
import com.verdantvice.security.CustomItemType;
import com.verdantvice.security.ItemSigner;
import com.verdantvice.security.SecureItemService;
import com.verdantvice.security.SignedItemData;
import com.verdantvice.storage.BatchRepository;
import com.verdantvice.storage.PlantRepository;
import com.verdantvice.strain.Strain;
import com.verdantvice.strain.StrainRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Main-thread cache and bounded timestamp scheduler for plants. SQL writes are
 * asynchronous; Bukkit world/display changes are marshalled back to the main
 * thread.
 */
public final class PlantManager implements PlantViewSource, PlantInteractionHandler {
    private static final Set<CustomItemType> SEED_ONLY = Set.of(CustomItemType.SEED);

    private final JavaPlugin plugin;
    private final ConfigManager configs;
    private final TranslationService translations;
    private final FarmManager farms;
    private final PlantRepository repository;
    private final BatchRepository batchRepository;
    private final SecureItemService secureItems;
    private final ItemSigner signer;
    private final StrainRegistry strains;
    private final SeedGeneticsResolver geneticsResolver;
    private final byte[] signingSecret;
    private final NamespacedKey stationKey;
    private final PlantGrowthService growthService = new PlantGrowthService();
    private final com.verdantvice.plant.care.PlantCareService careService =
            new com.verdantvice.plant.care.PlantCareService();
    private final HarvestWindowService windowService = new HarvestWindowService();
    private final HarvestService harvestService = new HarvestService(windowService);
    private final BatchAuthenticityService authenticity = new BatchAuthenticityService();
    private final Map<UUID, PlantRepository.StoredPlant> plants = new ConcurrentHashMap<>();
    private final Map<ChunkKey, Set<UUID>> chunkIndex = new ConcurrentHashMap<>();
    private final Set<BlockPosition> pendingStations = ConcurrentHashMap.newKeySet();
    private final ArrayDeque<UUID> updateQueue = new ArrayDeque<>();

    private PlantDisplayService displays;
    private BukkitTask updateTask;

    public PlantManager(
            JavaPlugin plugin,
            ConfigManager configs,
            TranslationService translations,
            FarmManager farms,
            PlantRepository repository,
            BatchRepository batchRepository,
            SecureItemService secureItems,
            ItemSigner signer,
            StrainRegistry strains,
            SeedGeneticsResolver geneticsResolver,
            byte[] signingSecret
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.translations = Objects.requireNonNull(translations, "translations");
        this.farms = Objects.requireNonNull(farms, "farms");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.batchRepository = Objects.requireNonNull(batchRepository, "batchRepository");
        this.secureItems = Objects.requireNonNull(secureItems, "secureItems");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.strains = Objects.requireNonNull(strains, "strains");
        this.geneticsResolver = Objects.requireNonNull(geneticsResolver, "geneticsResolver");
        this.signingSecret = Objects.requireNonNull(signingSecret, "signingSecret").clone();
        this.stationKey = new NamespacedKey(plugin, "plant_station");
    }

    public void attachDisplays(PlantDisplayService displays) {
        if (this.displays != null) {
            throw new IllegalStateException("Plant displays are already attached");
        }
        this.displays = Objects.requireNonNull(displays, "displays");
    }

    public CompletableFuture<Void> load() {
        return repository.loadAll().thenCompose(rows -> onMain(() -> {
            plants.clear();
            chunkIndex.clear();
            updateQueue.clear();
            for (PlantRepository.StoredPlant row : rows) {
                plants.put(row.plant().uniqueId(), row);
                index(row.plant());
                updateQueue.addLast(row.plant().uniqueId());
            }
        }));
    }

    public void start() {
        requireMainThread();
        if (updateTask != null) {
            return;
        }
        long period = Math.max(
                20L,
                configs.main().getLong("performance.plant-update-seconds", 30L) * 20L
        );
        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::processCycle, period, period);
    }

    public void stop() {
        requireMainThread();
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        pendingStations.clear();
    }

    public CompletableFuture<Plant> plant(
            Player player,
            Block station,
            ItemStack seedItem
    ) {
        requireMainThread();
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(station, "station");
        BlockPosition position = position(station.getLocation());
        if (!pendingStations.add(position)) {
            return CompletableFuture.failedFuture(new IllegalStateException("busy"));
        }
        try {
            validatePlanting(player, station, position);
            ItemSigner.Verification verified = signer.verify(seedItem);
            if (!verified.valid() || verified.data().itemType() != CustomItemType.SEED) {
                throw new IllegalArgumentException("invalid_item");
            }
            SignedItemData seed = verified.data();
            Strain strain = strains.require(seed.strain());
            GeneticProfile genetics = genetics(seed);
            Instant now = Instant.now();
            Optional<UUID> farmId = farms.at(station.getLocation()).map(com.verdantvice.farm.Farm::farmId);
            Plant candidate = Plant.planted(
                    UUID.randomUUID(),
                    player.getUniqueId(),
                    position,
                    strain,
                    genetics,
                    now,
                    farmId
            ).withEnvironment(
                    station.getLightLevel(),
                    100.0,
                    Optional.empty(),
                    0.0
            );
            VerdantPlantPlacedEvent event = new VerdantPlantPlacedEvent(
                    player,
                    candidate.uniqueId(),
                    station.getLocation(),
                    strain.id()
            );
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                throw new IllegalStateException("canceled");
            }
            String operationId = "plant:" + candidate.uniqueId();
            return secureItems.consume(seedItem, SEED_ONLY, player.getUniqueId(), operationId)
                    .thenCompose(consumed -> repository.create(candidate, genetics))
                    .thenCompose(ignored -> onMain(() -> {
                        removeInstance(player, seed.uniqueInstanceId());
                        PlantRepository.StoredPlant stored =
                                new PlantRepository.StoredPlant(candidate, genetics, 0L);
                        plants.put(candidate.uniqueId(), stored);
                        index(candidate);
                        updateQueue.addLast(candidate.uniqueId());
                        if (displays != null) {
                            displays.spawn(candidate);
                        }
                        player.getWorld().spawnParticle(
                                org.bukkit.Particle.HAPPY_VILLAGER,
                                station.getLocation().add(0.5, 0.8, 0.5),
                                12,
                                0.25,
                                0.2,
                                0.25,
                                0.0
                        );
                        player.playSound(
                                station.getLocation(),
                                org.bukkit.Sound.ITEM_CROP_PLANT,
                                0.8f,
                                1.1f
                        );
                        player.sendMessage(translations.component(
                                player,
                                "plant.interaction.planted",
                                Map.of("strain", strain.displayName())
                        ));
                    }).thenApply(ignoredAgain -> candidate))
                    .whenComplete((value, failure) -> pendingStations.remove(position));
        } catch (RuntimeException failure) {
            pendingStations.remove(position);
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Override
    public void interact(Player player, UUID plantId, EquipmentSlot hand) {
        requireMainThread();
        if (hand != EquipmentSlot.HAND) {
            return;
        }
        PlantRepository.StoredPlant stored = plants.get(plantId);
        if (stored == null) {
            player.sendMessage(translations.component(player, "errors.target-not-found"));
            return;
        }
        Plant plant = stored.plant();
        Location location = location(plant.position());
        if (location == null || !farms.can(player, location, FarmPermission.VIEW)) {
            player.sendMessage(translations.component(player, "errors.farm-permission"));
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            if (player.isSneaking()) {
                harvest(player, stored);
            } else {
                inspect(player, stored, false);
            }
            return;
        }
        ItemSigner.Verification verification = signer.verify(held);
        if (!verification.valid()) {
            player.sendMessage(translations.component(player, "errors.invalid-item"));
            return;
        }
        switch (verification.data().itemType()) {
            case PLANT_SCANNER -> inspect(player, stored, true);
            case WATERING_CAN -> water(player, stored, held);
            case CLEANING_KIT, TREATMENT_SPRAY, NUTRIENT_PACK, REPAIR_KIT ->
                    care(player, stored, held, verification.data().itemType());
            default -> player.sendMessage(translations.component(player, "errors.wrong-item-state"));
        }
    }

    public Optional<PlantRepository.StoredPlant> stored(UUID plantId) {
        return Optional.ofNullable(plants.get(plantId));
    }

    public Optional<Plant> at(Location location) {
        BlockPosition target = position(location);
        return plants.values().stream()
                .map(PlantRepository.StoredPlant::plant)
                .filter(plant -> plant.position().equals(target))
                .findFirst();
    }

    public CompletableFuture<Boolean> remove(UUID plantId, UUID actor, String reason) {
        PlantRepository.StoredPlant stored = plants.get(plantId);
        if (stored == null) {
            return CompletableFuture.completedFuture(false);
        }
        return repository.delete(plantId, reason, actor).thenCompose(deleted -> onMain(() -> {
            if (!deleted) {
                return;
            }
            plants.remove(plantId);
            deindex(stored.plant());
            updateQueue.remove(plantId);
            if (displays != null) {
                displays.remove(plantId);
            }
        }).thenApply(ignored -> deleted));
    }

    @Override
    public Collection<Plant> plantsInChunk(UUID worldUuid, int chunkX, int chunkZ) {
        Set<UUID> ids = chunkIndex.getOrDefault(new ChunkKey(worldUuid, chunkX, chunkZ), Set.of());
        return ids.stream()
                .map(plants::get)
                .filter(Objects::nonNull)
                .map(PlantRepository.StoredPlant::plant)
                .toList();
    }

    @Override
    public Optional<Plant> plant(UUID plantId) {
        return Optional.ofNullable(plants.get(plantId)).map(PlantRepository.StoredPlant::plant);
    }

    public Collection<Plant> all() {
        return plants.values().stream().map(PlantRepository.StoredPlant::plant).toList();
    }

    /**
     * Applies worker watering through the same catch-up simulation and
     * optimistic persistence path used by player care.
     */
    public CompletableFuture<Plant> waterByWorker(
            UUID plantId,
            UUID workerUuid,
            double requestedAmount,
            double efficiency
    ) {
        requireMainThread();
        Objects.requireNonNull(workerUuid, "workerUuid");
        PlantRepository.StoredPlant stored = plants.get(
                Objects.requireNonNull(plantId, "plantId")
        );
        if (stored == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("target_unavailable")
            );
        }
        Instant now = Instant.now();
        Plant caughtUp = growthService.update(
                stored.plant(),
                strains.require(stored.plant().strainId()),
                stored.genetics(),
                now,
                growthPolicy(),
                true
        ).plant();
        double adjusted = Math.max(0.01, Math.min(100.0, requestedAmount * efficiency));
        Plant updated = growthService.water(caughtUp, adjusted, now).plant()
                .assignWorker(Optional.of(workerUuid));
        return persistWorkerUpdate(stored, updated, "WORKER_WATERED");
    }

    /**
     * Applies a real care action without fabricating a player inventory.
     */
    public CompletableFuture<Plant> careByWorker(
            UUID plantId,
            UUID workerUuid,
            com.verdantvice.plant.care.CareAction action,
            double requestedAmount,
            double efficiency
    ) {
        requireMainThread();
        Objects.requireNonNull(workerUuid, "workerUuid");
        PlantRepository.StoredPlant stored = plants.get(
                Objects.requireNonNull(plantId, "plantId")
        );
        Objects.requireNonNull(action, "action");
        if (stored == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("target_unavailable")
            );
        }
        Instant now = Instant.now();
        Plant caughtUp = growthService.update(
                stored.plant(),
                strains.require(stored.plant().strainId()),
                stored.genetics(),
                now,
                growthPolicy(),
                true
        ).plant();
        double boundedEfficiency = Math.max(0.1, Math.min(2.0, efficiency));
        com.verdantvice.plant.care.CareResult result = switch (action) {
            case FEED -> careService.feed(
                    caughtUp,
                    requestedAmount,
                    boundedEfficiency,
                    now
            );
            case CLEAN -> careService.clean(
                    caughtUp,
                    requestedAmount,
                    boundedEfficiency,
                    now
            );
            case TREAT -> careService.treat(
                    caughtUp,
                    requestedAmount,
                    boundedEfficiency,
                    now
            );
            case REPAIR_AND_RELIEVE_STRESS ->
                    careService.repairAndRelieveStress(
                            caughtUp,
                            requestedAmount,
                            boundedEfficiency,
                            now
                    );
        };
        return persistWorkerUpdate(
                stored,
                result.plant().assignWorker(Optional.of(workerUuid)),
                "WORKER_CARE_" + action.name()
        );
    }

    /**
     * Creates and signs a database-owned fresh batch, then removes the plant.
     * Delivery of the signed inventory reference is handled by the worker
     * action service after this future completes.
     */
    public CompletableFuture<ProductBatch> harvestByWorker(
            UUID plantId,
            UUID workerUuid
    ) {
        requireMainThread();
        Objects.requireNonNull(workerUuid, "workerUuid");
        PlantRepository.StoredPlant stored = plants.get(
                Objects.requireNonNull(plantId, "plantId")
        );
        if (stored == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("target_unavailable")
            );
        }
        Plant plant = stored.plant();
        if (plant.growthStage() == GrowthStage.SEED
                || plant.growthStage() == GrowthStage.SPROUT
                || plant.growthStage() == GrowthStage.SEEDLING
                || plant.growthStage() == GrowthStage.SPOILED) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("plant_not_ready")
            );
        }
        ProductBatch raw = harvestService.harvest(
                UUID.randomUUID(),
                plant,
                strains.require(plant.strainId()),
                stored.genetics(),
                Instant.now()
        );
        ProductBatch batch = authenticity.sign(raw, signingSecret);
        return batchRepository.create(batch)
                .thenCompose(ignored -> repository.delete(
                        plant.uniqueId(),
                        "WORKER_HARVESTED",
                        workerUuid
                ))
                .thenCompose(deleted -> {
                    if (!deleted) {
                        return CompletableFuture.failedFuture(
                                new IllegalStateException(
                                        "Concurrent worker harvest for " + plant.uniqueId()
                                )
                        );
                    }
                    return onMain(() -> {
                        plants.remove(plant.uniqueId());
                        deindex(plant);
                        updateQueue.remove(plant.uniqueId());
                        if (displays != null) {
                            displays.remove(plant.uniqueId());
                        }
                    });
                })
                .thenApply(ignored -> batch);
    }

    /**
     * Plants an authentic seed taken from a linked farm storage. The signed
     * seed is consumed through the anti-dupe registry before the plant row is
     * created.
     */
    public CompletableFuture<Plant> plantByWorker(
            UUID workerUuid,
            UUID ownerUuid,
            UUID farmId,
            Block station,
            ItemStack seedItem
    ) {
        requireMainThread();
        Objects.requireNonNull(workerUuid, "workerUuid");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(farmId, "farmId");
        Objects.requireNonNull(station, "station");
        Objects.requireNonNull(seedItem, "seedItem");
        BlockPosition position = position(station.getLocation());
        if (!pendingStations.add(position)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("busy")
            );
        }
        try {
            com.verdantvice.farm.Farm farm = farms.get(farmId)
                    .orElseThrow(() -> new IllegalArgumentException("unknown_farm"));
            if (!farm.ownerUuid().equals(ownerUuid)
                    || !farm.region().contains(position)
                    || !farm.settings().workersMayPlant()) {
                throw new IllegalStateException("farm_permission");
            }
            if (station.getType() != Material.DECORATED_POT
                    || !isStation(station)
                    || at(station.getLocation()).isPresent()) {
                throw new IllegalArgumentException("invalid_station");
            }
            ItemSigner.Verification verified = signer.verify(seedItem);
            if (!verified.valid()
                    || verified.data().itemType() != CustomItemType.SEED) {
                throw new IllegalArgumentException("invalid_item");
            }
            SignedItemData seed = verified.data();
            Strain strain = strains.require(seed.strain());
            if ((strain.rarity() == com.verdantvice.strain.StrainRarity.RARE
                    || strain.rarity()
                    == com.verdantvice.strain.StrainRarity.LEGENDARY)
                    && !farm.settings().allowRareSeeds()) {
                throw new IllegalStateException("rare_seed_disallowed");
            }
            if (strain.rarity()
                    == com.verdantvice.strain.StrainRarity.LEGENDARY
                    && farm.settings().legendarySeedRequiresApproval()) {
                throw new IllegalStateException("legendary_seed_requires_approval");
            }
            long farmPlants = plants.values().stream()
                    .map(PlantRepository.StoredPlant::plant)
                    .filter(plant -> plant.farmId().map(farmId::equals).orElse(false))
                    .count();
            if (farmPlants >= farm.plantLimit()) {
                throw new IllegalStateException("limit_reached");
            }
            int perChunk = plantsInChunk(
                    position.worldUuid(),
                    Math.floorDiv(position.x(), 16),
                    Math.floorDiv(position.z(), 16)
            ).size();
            if (perChunk >= configs.main().getInt(
                    "performance.max-plants-per-chunk",
                    32
            )) {
                throw new IllegalStateException("chunk_limit");
            }
            double minimum = configs.main().getDouble(
                    "limits.minimum-station-distance",
                    2.0
            );
            double squared = minimum * minimum;
            boolean tooClose = plants.values().stream()
                    .map(PlantRepository.StoredPlant::plant)
                    .filter(plant -> plant.position().worldUuid()
                            .equals(position.worldUuid()))
                    .anyMatch(plant ->
                            distanceSquared(plant.position(), position) < squared);
            if (tooClose) {
                throw new IllegalStateException("too_close");
            }
            GeneticProfile genetics = genetics(seed);
            Instant now = Instant.now();
            Plant candidate = Plant.planted(
                    UUID.randomUUID(),
                    ownerUuid,
                    position,
                    strain,
                    genetics,
                    now,
                    Optional.of(farmId)
            ).withEnvironment(
                    station.getLightLevel(),
                    100.0,
                    Optional.empty(),
                    0.0
            ).assignWorker(Optional.of(workerUuid));
            String operation = "worker-plant:"
                    + workerUuid + ':' + seed.uniqueInstanceId();
            return secureItems.consume(
                            seedItem,
                            SEED_ONLY,
                            workerUuid,
                            operation
                    )
                    .thenCompose(consumed -> repository.create(candidate, genetics))
                    .thenCompose(ignored -> onMain(() -> {
                        plants.put(
                                candidate.uniqueId(),
                                new PlantRepository.StoredPlant(
                                        candidate,
                                        genetics,
                                        0L
                                )
                        );
                        index(candidate);
                        updateQueue.addLast(candidate.uniqueId());
                        if (displays != null) {
                            displays.spawn(candidate);
                        }
                    }))
                    .thenApply(ignored -> candidate)
                    .whenComplete((value, failure) ->
                            pendingStations.remove(position));
        } catch (RuntimeException failure) {
            pendingStations.remove(position);
            return CompletableFuture.failedFuture(failure);
        }
    }

    public CompletableFuture<Plant> setStage(UUID plantId, GrowthStage stage) {
        requireMainThread();
        PlantRepository.StoredPlant stored = plants.get(plantId);
        if (stored == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("unknown_plant")
            );
        }
        Plant current = stored.plant();
        double progress = switch (stage) {
            case SEED -> 0.0;
            case SPROUT -> 2.0;
            case SEEDLING -> 12.0;
            case VEGETATIVE -> 30.0;
            case MATURING -> 65.0;
            case READY -> 100.0;
            case OVERRIPE, SPOILED -> 120.0;
        };
        Plant updated = current.withSimulation(
                stage,
                progress,
                stage == GrowthStage.SPOILED ? 0.0 : current.health(),
                current.hydration(),
                current.nutrition(),
                current.stress(),
                current.yieldPotential(),
                stage == GrowthStage.SPOILED ? 0.0 : current.qualityPotential(),
                Instant.now()
        );
        return repository.save(updated, "ADMIN_STAGE")
                .thenCompose(ignored -> onMain(() -> {
                    plants.put(
                            plantId,
                            new PlantRepository.StoredPlant(
                                    updated,
                                    stored.genetics(),
                                    stored.rowVersion() + 1L
                            )
                    );
                    if (displays != null) {
                        displays.update(updated);
                    }
                }).thenApply(mainThreadResult -> updated));
    }

    public void inspect(Player player, UUID plantId, boolean advanced) {
        requireMainThread();
        PlantRepository.StoredPlant stored = plants.get(plantId);
        if (stored == null) {
            player.sendMessage(translations.component(player, "errors.target-not-found"));
            return;
        }
        inspect(player, stored, advanced);
    }

    public boolean isStation(Block block) {
        return block.getState() instanceof TileState tile
                && tile.getPersistentDataContainer().has(stationKey, PersistentDataType.BYTE);
    }

    public void markStation(Block block) {
        requireMainThread();
        if (!(block.getState() instanceof TileState tile)) {
            throw new IllegalArgumentException("Plant station must be a tile state");
        }
        tile.getPersistentDataContainer().set(stationKey, PersistentDataType.BYTE, (byte) 1);
        tile.update(true, false);
    }

    private void processCycle() {
        requireMainThread();
        int maximum = Math.max(1, configs.main().getInt("performance.plants-per-cycle", 100));
        Instant now = Instant.now();
        for (int processed = 0; processed < maximum && !updateQueue.isEmpty(); processed++) {
            UUID id = updateQueue.removeFirst();
            updateQueue.addLast(id);
            PlantRepository.StoredPlant stored = plants.get(id);
            if (stored == null) {
                continue;
            }
            Plant plant = stored.plant();
            Strain strain;
            try {
                strain = strains.require(plant.strainId());
            } catch (IllegalArgumentException missingStrain) {
                plugin.getLogger().log(Level.WARNING,
                        "Plant {0} references missing strain {1}",
                        new Object[]{plant.uniqueId(), plant.strainId()});
                continue;
            }
            World world = Bukkit.getWorld(plant.position().worldUuid());
            boolean loaded = world != null && world.isChunkLoaded(
                    Math.floorDiv(plant.position().x(), 16),
                    Math.floorDiv(plant.position().z(), 16)
            );
            PlantUpdate update = growthService.update(
                    plant,
                    strain,
                    stored.genetics(),
                    now,
                    growthPolicy(),
                    loaded
            );
            Plant updated = update.plant();
            if (loaded) {
                Location at = location(updated.position());
                if (at != null) {
                    updated = updated.withEnvironment(
                            at.getBlock().getLightLevel(),
                            updated.cleanliness(),
                            updated.diseaseId(),
                            updated.pestLevel()
                    );
                }
            }
            updated = maybeDevelopProblem(updated);
            if (sameSimulation(plant, updated)) {
                continue;
            }
            persistUpdate(stored, updated, "GROWTH_UPDATED");
        }
    }

    private Plant maybeDevelopProblem(Plant plant) {
        if (plant.diseaseId().isPresent()) {
            return plant;
        }
        double chance = configs.get("plants.yml").getDouble("problems.chance-per-update", 0.025);
        double stabilityPenalty = Math.max(0.0, 1.0 - plants.get(plant.uniqueId())
                .genetics().stabilityModifier()) * 0.04;
        if (ThreadLocalRandom.current().nextDouble() >= chance + stabilityPenalty) {
            return plant;
        }
        double pest = Math.min(100.0, plant.pestLevel() + ThreadLocalRandom.current().nextDouble(5.0, 18.0));
        return plant.withEnvironment(
                plant.lightLevel(),
                plant.cleanliness(),
                Optional.of("fictional_mold"),
                pest
        );
    }

    private void persistUpdate(
            PlantRepository.StoredPlant oldStored,
            Plant updated,
            String eventType
    ) {
        repository.save(updated, eventType).whenComplete((ignored, failure) -> {
            if (failure != null) {
                plugin.getLogger().log(Level.WARNING,
                        "Could not persist plant " + updated.uniqueId(), failure);
                return;
            }
            onMain(() -> {
                PlantRepository.StoredPlant current = plants.get(updated.uniqueId());
                if (current == null || current.plant().lastUpdatedAt()
                        .isAfter(oldStored.plant().lastUpdatedAt())) {
                    return;
                }
                plants.put(
                        updated.uniqueId(),
                        new PlantRepository.StoredPlant(
                                updated,
                                oldStored.genetics(),
                                oldStored.rowVersion() + 1L
                        )
                );
                if (oldStored.plant().growthStage() != updated.growthStage()) {
                    VerdantPlantStageChangeEvent event = new VerdantPlantStageChangeEvent(
                            updated.uniqueId(),
                            oldStored.plant().growthStage(),
                            updated.growthStage()
                    );
                    Bukkit.getPluginManager().callEvent(event);
                }
                if (displays != null) {
                    displays.update(updated);
                }
            });
        });
    }

    private CompletableFuture<Plant> persistWorkerUpdate(
            PlantRepository.StoredPlant oldStored,
            Plant updated,
            String eventType
    ) {
        return repository.save(updated, eventType)
                .thenCompose(ignored -> onMain(() -> {
                    PlantRepository.StoredPlant current =
                            plants.get(updated.uniqueId());
                    if (current == null) {
                        throw new IllegalStateException("target_unavailable");
                    }
                    if (current.plant().lastUpdatedAt()
                            .isAfter(oldStored.plant().lastUpdatedAt())) {
                        throw new IllegalStateException(
                                "plant_changed_during_worker_action"
                        );
                    }
                    plants.put(
                            updated.uniqueId(),
                            new PlantRepository.StoredPlant(
                                    updated,
                                    oldStored.genetics(),
                                    oldStored.rowVersion() + 1L
                            )
                    );
                    if (oldStored.plant().growthStage() != updated.growthStage()) {
                        Bukkit.getPluginManager().callEvent(
                                new VerdantPlantStageChangeEvent(
                                        updated.uniqueId(),
                                        oldStored.plant().growthStage(),
                                        updated.growthStage()
                                )
                        );
                    }
                    if (displays != null) {
                        displays.update(updated);
                    }
                }))
                .thenApply(ignored -> updated);
    }

    private void water(Player player, PlantRepository.StoredPlant stored, ItemStack tool) {
        Location plantLocation = location(stored.plant().position());
        if (plantLocation == null || !farms.can(player, plantLocation, FarmPermission.WATER)) {
            player.sendMessage(translations.component(player, "errors.farm-permission"));
            return;
        }
        Instant now = Instant.now();
        Plant caughtUp = growthService.update(
                stored.plant(),
                strains.require(stored.plant().strainId()),
                stored.genetics(),
                now,
                growthPolicy(),
                true
        ).plant();
        double amount = configs.get("plants.yml").getDouble("care.watering-can-hydration", 28.0);
        WateringResult result = growthService.water(caughtUp, amount, now);
        damageTool(player, tool);
        persistUpdate(stored, result.plant(), "WATERED");
        player.sendMessage(translations.component(
                player,
                "plant.interaction.watered",
                Map.of("hydration", decimal(result.plant().hydration()))
        ));
    }

    private void care(
            Player player,
            PlantRepository.StoredPlant stored,
            ItemStack tool,
            CustomItemType type
    ) {
        Location plantLocation = location(stored.plant().position());
        if (plantLocation == null || !farms.can(player, plantLocation, FarmPermission.WATER)) {
            player.sendMessage(translations.component(player, "errors.farm-permission"));
            return;
        }
        Instant now = Instant.now();
        Plant caughtUp = growthService.update(
                stored.plant(),
                strains.require(stored.plant().strainId()),
                stored.genetics(),
                now,
                growthPolicy(),
                true
        ).plant();
        YamlConfiguration yaml = configs.get("plants.yml");
        com.verdantvice.plant.care.CareResult result = switch (type) {
            case CLEANING_KIT -> careService.clean(
                    caughtUp,
                    yaml.getDouble("care.cleaning-kit-cleanliness", 35.0),
                    1.0,
                    now
            );
            case TREATMENT_SPRAY -> careService.treat(
                    caughtUp,
                    yaml.getDouble("care.treatment-spray-disease-reduction", 45.0),
                    1.0,
                    now
            );
            case NUTRIENT_PACK -> careService.feed(
                    caughtUp,
                    yaml.getDouble("care.nutrient-pack-nutrition", 24.0),
                    1.0,
                    now
            );
            case REPAIR_KIT -> careService.repairAndRelieveStress(
                    caughtUp,
                    yaml.getDouble("care.repair-kit-condition", 40.0),
                    1.0,
                    now
            );
            default -> throw new IllegalArgumentException("Unsupported care tool");
        };
        damageTool(player, tool);
        persistUpdate(stored, result.plant(), "CARE_" + result.action().name());
        String key = switch (type) {
            case CLEANING_KIT -> "plant.interaction.cleaned";
            case TREATMENT_SPRAY -> "plant.interaction.treated";
            default -> "plant.interaction.repaired";
        };
        player.sendMessage(translations.component(
                player,
                key,
                Map.of(
                        "cleanliness", decimal(result.plant().cleanliness()),
                        "condition", result.action().name().toLowerCase(Locale.ROOT)
                )
        ));
    }

    private void harvest(Player player, PlantRepository.StoredPlant stored) {
        Plant plant = stored.plant();
        Location plantLocation = location(plant.position());
        if (plantLocation == null || !farms.can(player, plantLocation, FarmPermission.HARVEST)) {
            player.sendMessage(translations.component(player, "errors.farm-permission"));
            return;
        }
        if (plant.growthStage() == GrowthStage.SEED
                || plant.growthStage() == GrowthStage.SPROUT
                || plant.growthStage() == GrowthStage.SEEDLING
                || plant.growthStage() == GrowthStage.SPOILED) {
            player.sendMessage(translations.component(player, "plant.interaction.not-ready",
                    Map.of("timing", windowService.assess(plant, Instant.now()).name())));
            return;
        }
        ProductBatch raw;
        try {
            raw = harvestService.harvest(
                    UUID.randomUUID(),
                    plant,
                    strains.require(plant.strainId()),
                    stored.genetics(),
                    Instant.now()
            );
        } catch (RuntimeException failure) {
            player.sendMessage(translations.component(player, "errors.wrong-item-state"));
            return;
        }
        ProductBatch batch = authenticity.sign(raw, signingSecret);
        VerdantPlantHarvestEvent event = new VerdantPlantHarvestEvent(
                player,
                plant.uniqueId(),
                batch.batchId(),
                batch.harvestTiming()
        );
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }
        batchRepository.create(batch)
                .thenCompose(ignored -> secureItems.issue(
                        player,
                        CustomItemType.FRESH_BATCH,
                        player.getUniqueId(),
                        batch.batchId(),
                        plant.strainId(),
                        batch.qualityScore()
                ))
                .thenCompose(item -> repository.delete(
                        plant.uniqueId(),
                        "HARVESTED",
                        player.getUniqueId()
                ).thenApply(deleted -> new HarvestDelivery(item, deleted)))
                .whenComplete((delivery, failure) -> onMain(() -> {
                    if (failure != null || delivery == null || !delivery.deleted()) {
                        plugin.getLogger().log(Level.WARNING,
                                "Harvest transaction failed for plant " + plant.uniqueId(), failure);
                        player.sendMessage(translations.component(player, "errors.database-unavailable"));
                        return;
                    }
                    plants.remove(plant.uniqueId());
                    deindex(plant);
                    updateQueue.remove(plant.uniqueId());
                    if (displays != null) {
                        displays.remove(plant.uniqueId());
                    }
                    giveOrDrop(player, delivery.item());
                    player.sendMessage(translations.component(
                            player,
                            "plant.interaction.harvested",
                            Map.of(
                                    "weight", decimal(batch.freshWeight()),
                                    "strain", strains.require(plant.strainId()).displayName(),
                                    "timing", batch.harvestTiming().name()
                            )
                    ));
                }));
    }

    private void inspect(Player player, PlantRepository.StoredPlant stored, boolean scanner) {
        Plant plant = stored.plant();
        Strain strain = strains.require(plant.strainId());
        Duration until = windowService.timeUntilWindow(plant, Instant.now());
        List<Component> lines = new ArrayList<>();
        lines.add(translations.component(player, "gui.plants.details-title", Map.of(
                "strain", strain.displayName(),
                "stage", translations.plain(player,
                        "plant.stages." + plant.growthStage().name().toLowerCase(Locale.ROOT))
        )));
        lines.add(translations.component(player, "gui.plants.novice-summary",
                Map.of("condition", overall(player, plant))));
        lines.add(translations.component(player, "gui.plants.approximate-ready",
                Map.of("time", duration(until))));
        if (scanner || player.hasPermission("verdantvice.admin.inspect")) {
            lines.add(translations.component(player, "gui.plants.exact-health",
                    Map.of("health", decimal(plant.health()))));
            lines.add(translations.component(player, "gui.plants.hydration",
                    Map.of("hydration", decimal(plant.hydration()))));
            lines.add(translations.component(player, "gui.plants.stress",
                    Map.of("stress", decimal(plant.stress()))));
            lines.add(translations.component(player, "gui.plants.conditions",
                    Map.of("conditions", plant.diseaseId().orElse(
                            translations.plain(player, "common.none")))));
            lines.add(translations.component(player, "gui.plants.quality-potential",
                    Map.of("quality", decimal(plant.qualityPotential()))));
            lines.add(translations.component(player, "gui.plants.yield-potential",
                    Map.of("yield", decimal(plant.yieldPotential()))));
            lines.add(translations.component(player, "gui.plants.harvest-window",
                    Map.of("window", plant.harvestWindowStart() + " – " + plant.harvestWindowEnd())));
            lines.add(translations.component(player, "gui.plants.genetics",
                    Map.of("genetics", stored.genetics().geneticClass().name())));
        }
        Component message = Component.join(JoinConfiguration.newlines(), lines);
        player.sendMessage(message);
        if (displays != null) {
            displays.showInspection(player, plant, message);
        }
    }

    private void validatePlanting(Player player, Block station, BlockPosition position) {
        if (!player.hasPermission("verdantvice.plant")) {
            throw new IllegalStateException("no_permission");
        }
        if (station.getType() != Material.DECORATED_POT || !isStation(station)) {
            throw new IllegalArgumentException("invalid_station");
        }
        if (!farms.can(player, station.getLocation(), FarmPermission.PLANT)) {
            throw new IllegalStateException("farm_permission");
        }
        if (at(station.getLocation()).isPresent()) {
            throw new IllegalStateException("busy");
        }
        long owned = plants.values().stream()
                .filter(row -> row.plant().ownerUuid().equals(player.getUniqueId()))
                .count();
        int playerLimit = configs.main().getInt("limits.plants-per-player", 64);
        if (owned >= playerLimit && !player.hasPermission("verdantvice.admin")) {
            throw new IllegalStateException("limit_reached");
        }
        int perChunk = plantsInChunk(
                position.worldUuid(),
                Math.floorDiv(position.x(), 16),
                Math.floorDiv(position.z(), 16)
        ).size();
        if (perChunk >= configs.main().getInt("performance.max-plants-per-chunk", 32)) {
            throw new IllegalStateException("chunk_limit");
        }
        double minimum = configs.main().getDouble("limits.minimum-station-distance", 2.0);
        double squared = minimum * minimum;
        boolean tooClose = plants.values().stream()
                .map(PlantRepository.StoredPlant::plant)
                .filter(plant -> plant.position().worldUuid().equals(position.worldUuid()))
                .anyMatch(plant -> distanceSquared(plant.position(), position) < squared);
        if (tooClose) {
            throw new IllegalStateException("too_close");
        }
    }

    private GeneticProfile genetics(SignedItemData seed) {
        return geneticsResolver.profileForSeed(seed);
    }

    private GrowthPolicy growthPolicy() {
        YamlConfiguration plantsConfig = configs.get("plants.yml");
        return new GrowthPolicy(
                configs.main().getBoolean("performance.offline-growth", true),
                configs.main().getBoolean("performance.unloaded-chunk-growth", true),
                Duration.ofDays(7),
                plantsConfig.getDouble("growth.hydration-loss-per-hour", 3.0),
                plantsConfig.getDouble("growth.nutrition-loss-per-hour", 1.0),
                plantsConfig.getDouble("growth.base-health-loss-per-hour", 0.15),
                2.0,
                Duration.ofHours(plantsConfig.getLong("growth.spoiled-hours", 30L))
        );
    }

    private boolean sameSimulation(Plant first, Plant second) {
        return first.growthStage() == second.growthStage()
                && Math.abs(first.growthProgress() - second.growthProgress()) < 0.0001
                && Math.abs(first.hydration() - second.hydration()) < 0.0001
                && Math.abs(first.health() - second.health()) < 0.0001
                && first.diseaseId().equals(second.diseaseId())
                && Math.abs(first.pestLevel() - second.pestLevel()) < 0.0001;
    }

    private void damageTool(Player player, ItemStack tool) {
        boolean usable = signer.usesRemaining(tool) > 0;
        boolean remains = usable && signer.usesRemaining(tool) > 1;
        if (!usable || !remains) {
            player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        } else {
            signer.setUsesRemaining(tool, signer.usesRemaining(tool) - 1);
            player.getInventory().setItemInMainHand(tool);
        }
    }

    private void removeInstance(Player player, UUID instanceId) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
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

    private void index(Plant plant) {
        chunkIndex.computeIfAbsent(key(plant.position()), ignored -> ConcurrentHashMap.newKeySet())
                .add(plant.uniqueId());
    }

    private void deindex(Plant plant) {
        ChunkKey key = key(plant.position());
        Set<UUID> ids = chunkIndex.get(key);
        if (ids != null) {
            ids.remove(plant.uniqueId());
            if (ids.isEmpty()) {
                chunkIndex.remove(key, ids);
            }
        }
    }

    private ChunkKey key(BlockPosition position) {
        return new ChunkKey(
                position.worldUuid(),
                Math.floorDiv(position.x(), 16),
                Math.floorDiv(position.z(), 16)
        );
    }

    private BlockPosition position(Location location) {
        return new BlockPosition(
                Objects.requireNonNull(location.getWorld()).getUID(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ()
        );
    }

    private Location location(BlockPosition position) {
        World world = Bukkit.getWorld(position.worldUuid());
        return world == null ? null : new Location(world, position.x(), position.y(), position.z());
    }

    private double distanceSquared(BlockPosition first, BlockPosition second) {
        long dx = (long) first.x() - second.x();
        long dy = (long) first.y() - second.y();
        long dz = (long) first.z() - second.z();
        return dx * dx + dy * dy + dz * dz;
    }

    private String overall(Player player, Plant plant) {
        double score = (plant.health() + plant.hydration() + plant.cleanliness()
                + (100.0 - plant.stress())) / 4.0;
        String suffix = score >= 85 ? "excellent"
                : score >= 65 ? "good"
                : score >= 45 ? "fair"
                : score >= 25 ? "poor" : "critical";
        return translations.plain(player, "plant.overall." + suffix);
    }

    private String decimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private String duration(Duration duration) {
        long minutes = Math.max(0L, duration.toMinutes());
        if (minutes < 60L) {
            return minutes + "m";
        }
        long hours = minutes / 60L;
        return hours + "h " + (minutes % 60L) + "m";
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
            throw new IllegalStateException("Plant world operations require the main thread");
        }
    }

    private record ChunkKey(UUID world, int x, int z) {
    }

    private record HarvestDelivery(ItemStack item, boolean deleted) {
    }
}
