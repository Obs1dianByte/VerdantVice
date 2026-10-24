package com.verdantvice.gui;

import com.verdantvice.common.BlockPosition;
import com.verdantvice.farm.Farm;
import com.verdantvice.farm.FarmMember;
import com.verdantvice.farm.FarmPermission;
import com.verdantvice.farm.FarmStorage;
import com.verdantvice.farm.FarmManager;
import com.verdantvice.farm.StorageType;
import com.verdantvice.genetics.GeneticsManager;
import com.verdantvice.i18n.TranslationService;
import com.verdantvice.market.PaperMarketService;
import com.verdantvice.notification.NotificationService;
import com.verdantvice.notification.NotificationType;
import com.verdantvice.plant.GrowthStage;
import com.verdantvice.plant.Plant;
import com.verdantvice.plant.PlantManager;
import com.verdantvice.processing.ProcessingManager;
import com.verdantvice.processing.ProcessingStationType;
import com.verdantvice.processing.ProductBatch;
import com.verdantvice.profile.PlayerProfile;
import com.verdantvice.profile.PlayerProfileManager;
import com.verdantvice.profile.SkillTree;
import com.verdantvice.strain.Strain;
import com.verdantvice.strain.StrainRegistry;
import com.verdantvice.text.ExternalNameSanitizer;
import com.verdantvice.worker.WorkerManager;
import com.verdantvice.worker.WorkerTaskType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public final class DefaultGuiDataProvider implements GuiDataProvider {
    private final TranslationService translations;
    private final PlantManager plants;
    private final FarmManager farms;
    private final PlayerProfileManager profiles;
    private final StrainRegistry strains;
    private final GeneticsManager genetics;
    private final WorkerManager workers;
    private final PaperMarketService markets;
    private final ProcessingManager processing;
    private final NotificationService notifications;

    public DefaultGuiDataProvider(
            TranslationService translations,
            PlantManager plants,
            FarmManager farms,
            PlayerProfileManager profiles,
            StrainRegistry strains,
            GeneticsManager genetics,
            WorkerManager workers,
            PaperMarketService markets,
            ProcessingManager processing,
            NotificationService notifications
    ) {
        this.translations = Objects.requireNonNull(translations, "translations");
        this.plants = Objects.requireNonNull(plants, "plants");
        this.farms = Objects.requireNonNull(farms, "farms");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.strains = Objects.requireNonNull(strains, "strains");
        this.genetics = Objects.requireNonNull(genetics, "genetics");
        this.workers = Objects.requireNonNull(workers, "workers");
        this.markets = Objects.requireNonNull(markets, "markets");
        this.processing = Objects.requireNonNull(processing, "processing");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
    }

    @Override
    public GuiContext resolveContext(
            Player player,
            MenuType type,
            GuiContext requested
    ) {
        if (!usesFarmContext(type)
                || requested.farmId().isPresent()
                || type == MenuType.PLANTS) {
            return requested;
        }
        Optional<Farm> selected = farms.owned(player.getUniqueId())
                .or(() -> farms.forPlayer(player.getUniqueId()).stream()
                        .filter(farm -> farm.can(player.getUniqueId(), FarmPermission.VIEW))
                        .findFirst());
        return selected.map(farm -> GuiContext.farm(farm.farmId()))
                .orElse(requested);
    }

    @Override
    public Optional<String> accessDeniedKey(
            Player player,
            MenuType type,
            GuiContext context
    ) {
        if (!usesFarmContext(type) || context.farmId().isEmpty()) {
            return Optional.empty();
        }
        Farm farm = farms.get(context.farmId().orElseThrow()).orElse(null);
        if (farm == null) {
            return Optional.of("gui.farm-panel.errors.no-context");
        }
        boolean administrator = player.hasPermission("verdantvice.admin");
        return FarmMenuPolicy.mayOpen(
                farm,
                player.getUniqueId(),
                type,
                administrator
        ) ? Optional.empty() : Optional.of("gui.farm-panel.errors.no-access");
    }

    @Override
    public List<MenuEntry> entries(
            Player player,
            MenuType type,
            GuiContext context
    ) {
        return switch (type) {
            case PLANTS -> plantEntries(player, context);
            case STRAINS -> strainEntries(player);
            case SKILLS -> skillEntries(player);
            case FARM -> farmEntries(player, context);
            case GUIDE -> List.of(simpleEntry(
                    Material.WRITTEN_BOOK,
                    player,
                    "book.basics.title",
                    "item.basics_book.description",
                    "command:vv guide",
                    true
            ));
            case SETTINGS -> settings(player);
            case MARKET -> market(player);
            case WORKERS -> workerEntries(player, context);
            case GENETICS -> geneticEntries(player);
            case STORAGES -> storageEntries(player, context);
            case STORAGE_UNLINK_CONFIRM -> storageConfirmation(player, context);
            case DRYING -> processingEntries(
                    player, context, ProcessingStationType.DRYING_RACK);
            case CURING -> processingEntries(
                    player, context, ProcessingStationType.CURING_CONTAINER);
            case PRIORITIES -> priorityEntries(player, context);
            case PERMISSIONS -> permissionEntries(player, context);
            case SALARY -> salaryEntries(player, context);
            case STATISTICS -> statisticEntries(player, context);
            case NOTIFICATIONS -> notificationEntries(player);
            case UPGRADES -> unavailableUpgrade(player, context);
            case CONTRACTS, DRYING_RACK, CURING_CONTAINER,
                 GRINDER, MIXER, ROLLING, MAIN -> List.of();
        };
    }

    @Override
    public Map<String, ?> titlePlaceholders(
            Player player,
            MenuType type,
            GuiContext context
    ) {
        if (!usesFarmContext(type)) {
            return Map.of();
        }
        if (context.farmId().isEmpty()) {
            return type == MenuType.FARM
                    ? Map.of("farm", translations.plain(player, "common.none"))
                    : Map.of();
        }
        return Map.of(
                "farm",
                farms.get(context.farmId().orElseThrow())
                        .map(Farm::name)
                        .orElse(translations.plain(player, "common.none"))
        );
    }

    @Override
    public String emptyMessageKey(MenuType type, GuiContext context) {
        return switch (type) {
            case FARM -> "gui.farm-panel.empty.no-farm";
            case PLANTS -> "gui.farm-panel.empty.plants";
            case WORKERS -> "gui.farm-panel.empty.workers";
            case STORAGES -> "gui.farm-panel.empty.storages";
            case DRYING -> "gui.farm-panel.empty.drying";
            case CURING -> "gui.farm-panel.empty.curing";
            case PRIORITIES -> "gui.farm-panel.empty.priorities";
            case PERMISSIONS -> "gui.farm-panel.empty.permissions";
            default -> "gui.empty.generic";
        };
    }

    private List<MenuEntry> farmEntries(Player player, GuiContext context) {
        Farm farm = contextFarm(context).orElse(null);
        if (farm == null) {
            return List.of();
        }
        List<Plant> farmPlants = farmPlants(farm.farmId());
        List<WorkerManager.WorkerSnapshot> farmWorkers = workers.snapshots(farm.farmId());
        List<FarmDashboardSnapshot.StorageRuntimeSnapshot> farmStorages = storageSnapshots(farm);
        List<ProcessingManager.StationSnapshot> drying = processing.stationSnapshots(
                farm, ProcessingStationType.DRYING_RACK);
        List<ProcessingManager.StationSnapshot> curing = processing.stationSnapshots(
                farm, ProcessingStationType.CURING_CONTAINER);
        FarmDashboardSnapshot snapshot = FarmDashboardSnapshot.create(
                farm, farmPlants, farmWorkers, farmStorages, drying, curing);
        boolean admin = player.hasPermission("verdantvice.admin");
        UUID playerId = player.getUniqueId();
        boolean canManageWorkers = FarmMenuPolicy.mayOpen(
                farm, playerId, MenuType.WORKERS, admin);
        boolean canManageStorages = FarmMenuPolicy.mayOpen(
                farm, playerId, MenuType.STORAGES, admin);
        List<MenuEntry> result = new ArrayList<>();

        result.add(new MenuEntry(
                Material.FLOWERING_AZALEA,
                translations.component(player, "gui.farm.plants.name"),
                lines(player,
                        line("gui.farm-panel.dashboard.plants-limit", Map.of(
                                "count", snapshot.plants().count(),
                                "limit", snapshot.plants().limit())),
                        line("gui.farm-panel.dashboard.plants-ready", Map.of(
                                "ready", snapshot.plants().ready())),
                        line("gui.farm-panel.dashboard.plants-sick", Map.of(
                                "sick", snapshot.plants().sick())),
                        line("gui.farm-panel.dashboard.plants-water", Map.of(
                                "count", snapshot.plants().needsWater())),
                        line("gui.farm-panel.dashboard.plants-treatment", Map.of(
                                "count", snapshot.plants().needsTreatment())),
                        line("gui.farm-panel.dashboard.plants-cleaning", Map.of(
                                "count", snapshot.plants().needsCleaning()))
                ),
                "open:plants",
                snapshot.plants().ready() > 0
        ));
        result.add(new MenuEntry(
                Material.VILLAGER_SPAWN_EGG,
                translations.component(player, "gui.farm.workers.name"),
                lines(player,
                        line("gui.farm-panel.dashboard.workers-limit", Map.of(
                                "count", snapshot.workers().count(),
                                "limit", snapshot.workers().limit())),
                        line("gui.farm-panel.dashboard.workers-working", Map.of(
                                "count", snapshot.workers().working())),
                        line("gui.farm-panel.dashboard.workers-idle", Map.of(
                                "count", snapshot.workers().idle())),
                        line("gui.farm-panel.dashboard.workers-unpaid", Map.of(
                                "count", snapshot.workers().unpaid())),
                        line("gui.farm-panel.dashboard.workers-morale", Map.of(
                                "morale", decimal(snapshot.workers().averageMorale()))),
                        line("gui.farm-panel.dashboard.workers-blocked", Map.of(
                                "count", snapshot.workers().blocked()))
                ),
                canManageWorkers ? "open:workers" : null,
                snapshot.workers().blocked() > 0
        ));
        result.add(new MenuEntry(
                Material.CHEST,
                translations.component(player, "gui.farm.storages.name"),
                storageDashboardLore(player, snapshot.storages()),
                canManageStorages ? "open:storages" : null,
                snapshot.storages().unavailable() > 0
        ));
        result.add(processingTile(
                player,
                Material.BARREL,
                "gui.farm.drying.name",
                "open:drying",
                snapshot.drying()
        ));
        result.add(processingTile(
                player,
                Material.CHISELED_BOOKSHELF,
                "gui.farm.curing.name",
                "open:curing",
                snapshot.curing()
        ));
        if (FarmMenuPolicy.mayOpen(farm, playerId, MenuType.PRIORITIES, admin)) {
            result.add(new MenuEntry(
                    Material.COMPARATOR,
                    translations.component(player, "gui.farm.priorities.name"),
                    List.of(translations.component(
                            player,
                            "gui.farm-panel.dashboard.priorities",
                            Map.of("count", farmWorkers.stream()
                                    .filter(worker -> worker.currentTask().isPresent()).count())
                    )),
                    "open:priorities",
                    false
            ));
        }
        if (FarmMenuPolicy.mayOpen(farm, playerId, MenuType.PERMISSIONS, admin)) {
            result.add(new MenuEntry(
                    Material.WRITABLE_BOOK,
                    translations.component(player, "gui.farm.permissions.name"),
                    List.of(
                            translations.component(player, "gui.farm-panel.dashboard.owner",
                                    Map.of("owner", farm.ownerUuid())),
                            translations.component(player, "gui.farm-panel.dashboard.members",
                                    Map.of("count", Math.max(0, farm.members().size() - 1)))
                    ),
                    "open:permissions",
                    false
            ));
        }
        result.add(new MenuEntry(
                Material.EMERALD,
                translations.component(player, "gui.farm.salary.name"),
                lines(player,
                        line("gui.farm-panel.dashboard.salary-storage", Map.of(
                                "state", yesNo(player, snapshot.salary().storagePresent()))),
                        line("gui.farm-panel.dashboard.salary-emeralds", Map.of(
                                "count", salaryEmeralds(player, snapshot.salary()))),
                        line("gui.farm-panel.dashboard.salary-cost", Map.of(
                                "count", snapshot.salary().nextCost())),
                        line("gui.farm-panel.dashboard.salary-paid", Map.of(
                                "count", snapshot.salary().paidWorkers())),
                        line("gui.farm-panel.dashboard.salary-unpaid", Map.of(
                                "count", snapshot.salary().unpaidWorkers()))
                ),
                "open:salary",
                snapshot.salary().unpaidWorkers() > 0
        ));
        result.add(new MenuEntry(
                Material.ENDER_EYE,
                translations.component(player, "gui.farm.statistics.name"),
                List.of(translations.component(
                        player,
                        "gui.farm-panel.dashboard.statistics-current"
                )),
                "open:statistics",
                false
        ));
        result.add(new MenuEntry(
                Material.BELL,
                translations.component(player, "gui.farm.notifications.name"),
                List.of(translations.component(
                        player,
                        "gui.farm-panel.dashboard.notifications"
                )),
                "open:notifications",
                false
        ));
        result.addAll(unavailableUpgrade(player, context));
        if (FarmMenuPolicy.mayDelete(farm, playerId, admin)) {
            result.add(simpleEntry(
                    Material.BARRIER,
                    player,
                    "gui.farm.delete.name",
                    "gui.farm.delete.description",
                    "delete-farm:" + farm.farmId(),
                    false
            ));
        }
        return List.copyOf(result);
    }

    private List<MenuEntry> plantEntries(Player player, GuiContext context) {
        Collection<Plant> candidates = context.farmId()
                .map(this::farmPlants)
                .orElseGet(() -> plants.all().stream()
                        .filter(plant -> canViewPlant(player, plant))
                        .toList());
        Farm farm = contextFarm(context).orElse(null);
        boolean showOwner = farm != null && (
                player.hasPermission("verdantvice.admin")
                        || farm.can(player.getUniqueId(), FarmPermission.MANAGE_WORKERS)
        );
        Instant now = Instant.now();
        return candidates.stream()
                .sorted(Comparator.comparing(Plant::plantedAt))
                .map(plant -> plantEntry(player, plant, showOwner, now))
                .toList();
    }

    private MenuEntry plantEntry(
            Player player,
            Plant plant,
            boolean showOwner,
            Instant now
    ) {
        Strain strain = strains.find(plant.strainId()).orElse(null);
        String display = strain == null ? plant.strainId() : strain.displayName();
        List<Component> lore = new ArrayList<>();
        lore.add(translations.component(player, "gui.plants.exact-health",
                Map.of("health", decimal(plant.health()))));
        lore.add(translations.component(player, "gui.plants.hydration",
                Map.of("hydration", decimal(plant.hydration()))));
        lore.add(translations.component(player, "gui.plants.nutrition",
                Map.of("nutrition", decimal(plant.nutrition()))));
        lore.add(translations.component(player, "gui.plants.cleanliness",
                Map.of("cleanliness", decimal(plant.cleanliness()))));
        lore.add(translations.component(player, "gui.plants.quality-potential",
                Map.of("quality", decimal(plant.qualityPotential()))));
        lore.add(translations.component(player, "gui.farm-panel.plants.disease",
                Map.of("condition", plantCondition(player, plant))));
        lore.add(translations.component(player, "gui.farm-panel.plants.ready-time",
                Map.of("time", readiness(player, plant, now))));
        lore.add(translations.component(player, "gui.farm-panel.common.position",
                positionMap(plant.position())));
        if (showOwner) {
            lore.add(translations.component(player, "gui.farm-panel.plants.owner",
                    Map.of("owner", plant.ownerUuid())));
        }
        return new MenuEntry(
                icon(plant.growthStage()),
                translations.component(player, "gui.plants.details-title", Map.of(
                        "strain", display,
                        "stage", translations.plain(player, stageKey(plant.growthStage()))
                )),
                lore,
                null,
                plant.growthStage() == GrowthStage.READY
        );
    }

    private List<MenuEntry> workerEntries(Player player, GuiContext context) {
        Farm farm = contextFarm(context).orElse(null);
        if (farm == null) {
            return List.of();
        }
        return workers.snapshots(farm.farmId()).stream()
                .map(snapshot -> {
                    var worker = snapshot.record().worker();
                    String workerName = ExternalNameSanitizer.name(
                            snapshot.record().name(),
                            translations.plain(player, "herbalist.title"),
                            64
                    );
                    List<Component> lore = new ArrayList<>();
                    lore.add(translations.component(player, "gui.workers.specialization",
                            Map.of("specialization", translations.plain(
                                    player,
                                    "gui.farm-panel.worker.specializations."
                                            + slug(worker.specialization().name())
                            ))));
                    lore.add(translations.component(player, "gui.farm-panel.worker.state",
                            Map.of("state", translations.plain(
                                    player,
                                    "gui.farm-panel.worker.states."
                                            + slug(worker.state().name())
                            ))));
                    lore.add(translations.component(player, "gui.workers.current-task",
                            Map.of("task", snapshot.currentTask()
                                    .map(task -> taskName(player, task))
                                    .orElse(translations.plain(player, "gui.farm-panel.worker.no-task")))));
                    lore.add(translations.component(player, "gui.workers.morale",
                            Map.of("morale", decimal(worker.morale()))));
                    lore.add(translations.component(player, "gui.workers.experience",
                            Map.of("xp", worker.experience())));
                    lore.add(translations.component(player, "gui.workers.unpaid-days",
                            Map.of("days", worker.unpaidDays())));
                    lore.add(translations.component(player, "gui.farm-panel.worker.salary",
                            Map.of("salary", worker.salaryPerMinecraftDay())));
                    lore.add(translations.component(player, "gui.farm-panel.worker.entity",
                            Map.of("state", yesNo(player, snapshot.entityAvailable()))));
                    snapshot.position().ifPresent(position -> lore.add(translations.component(
                            player,
                            "gui.farm-panel.common.position",
                            positionMap(position)
                    )));
                    lore.add(translations.component(player, "gui.farm-panel.worker.last-task",
                            Map.of("task", snapshot.lastTask()
                                    .map(task -> taskName(player, task))
                                    .orElse(translations.plain(player, "gui.farm-panel.worker.no-task")))));
                    return new MenuEntry(
                            Material.VILLAGER_SPAWN_EGG,
                            translations.component(player, "gui.workers.details-title",
                                    Map.of("worker", workerName)),
                            lore,
                            null,
                            worker.morale() >= 75.0 && worker.unpaidDays() == 0
                    );
                })
                .toList();
    }

    private List<MenuEntry> storageEntries(Player player, GuiContext context) {
        Farm farm = contextFarm(context).orElse(null);
        if (farm == null) {
            return List.of();
        }
        boolean canManage = FarmMenuPolicy.mayUnlinkStorage(
                farm,
                player.getUniqueId(),
                player.hasPermission("verdantvice.admin")
        );
        return storageSnapshots(farm).stream()
                .sorted(Comparator.comparing(snapshot -> snapshot.storage().type().argument()))
                .map(snapshot -> {
                    FarmStorage storage = snapshot.storage();
                    List<Component> lore = new ArrayList<>();
                    lore.add(translations.component(player, "gui.farm-panel.storages.type",
                            Map.of("type", translations.plain(
                                    player,
                                    "gui.farm-panel.storage-types."
                                            + storage.type().argument()
                            ))));
                    lore.add(translations.component(player, "gui.farm-panel.common.position",
                            positionMap(storage.position())));
                    lore.add(translations.component(player, "gui.farm-panel.storages.exists",
                            Map.of("state", yesNo(player, snapshot.exists()))));
                    if (snapshot.exists()) {
                        lore.add(translations.component(player, "gui.farm-panel.storages.slots",
                                Map.of(
                                        "used", snapshot.occupiedSlots(),
                                        "total", snapshot.totalSlots()
                                )));
                    } else {
                        lore.add(translations.component(
                                player,
                                "gui.farm-panel.storages.slots-unavailable"
                        ));
                    }
                    lore.add(translations.component(player, "gui.farm-panel.storages.worker-access",
                            Map.of("state", yesNo(player, snapshot.workerAccessible()))));
                    lore.add(translations.component(player, "gui.farm-panel.storages.salary",
                            Map.of("state", yesNo(
                                    player,
                                    storage.type() == StorageType.SALARY_STORAGE
                            ))));
                    if (canManage) {
                        lore.add(translations.component(
                                player,
                                "gui.farm-panel.storages.unlink-hint"
                        ));
                    }
                    return new MenuEntry(
                            storageIcon(storage.type()),
                            translations.component(player, "gui.farm-panel.storages.entry-title",
                                    Map.of("type", translations.plain(
                                            player,
                                            "gui.farm-panel.storage-types."
                                                    + storage.type().argument()
                                    ))),
                            lore,
                            canManage ? "confirm-storage:" + storage.storageId() : null,
                            !snapshot.exists()
                    );
                })
                .toList();
    }

    private List<MenuEntry> storageConfirmation(Player player, GuiContext context) {
        Farm farm = contextFarm(context).orElse(null);
        UUID storageId = context.subjectId().orElse(null);
        if (farm == null || storageId == null) {
            return List.of();
        }
        FarmStorage storage = farms.storages(farm.farmId()).stream()
                .filter(candidate -> candidate.storageId().equals(storageId))
                .findFirst()
                .orElse(null);
        if (storage == null) {
            return List.of();
        }
        if (!FarmMenuPolicy.mayUnlinkStorage(
                farm,
                player.getUniqueId(),
                player.hasPermission("verdantvice.admin")
        )) {
            return List.of();
        }
        return List.of(
                new MenuEntry(
                        Material.RED_DYE,
                        translations.component(player, "gui.farm-panel.storage-confirm.confirm"),
                        List.of(
                                translations.component(player, "gui.farm-panel.storage-confirm.warning"),
                                translations.component(player, "gui.farm-panel.common.position",
                                        positionMap(storage.position()))
                        ),
                        "unlink-storage:" + storageId,
                        true
                ),
                new MenuEntry(
                        Material.LIME_DYE,
                        translations.component(player, "gui.farm-panel.storage-confirm.cancel"),
                        List.of(),
                        "open:storages",
                        false
                )
        );
    }

    private List<MenuEntry> processingEntries(
            Player player,
            GuiContext context,
            ProcessingStationType type
    ) {
        Farm farm = contextFarm(context).orElse(null);
        if (farm == null) {
            return List.of();
        }
        return processing.stationSnapshots(farm, type).stream()
                .map(snapshot -> processingEntry(player, snapshot))
                .toList();
    }

    private MenuEntry processingEntry(
            Player player,
            ProcessingManager.StationSnapshot snapshot
    ) {
        ProductBatch batch = snapshot.batch().orElse(null);
        List<Component> lore = new ArrayList<>();
        lore.add(translations.component(player, "gui.farm-panel.processing.status",
                Map.of("status", translations.plain(
                        player,
                        "gui.farm-panel.processing.statuses."
                                + slug(snapshot.status().name())
                ))));
        lore.add(translations.component(player, "gui.farm-panel.common.position",
                positionMap(new BlockPosition(
                        snapshot.station().position().worldUuid(),
                        snapshot.station().position().x(),
                        snapshot.station().position().y(),
                        snapshot.station().position().z()
                ))));
        lore.add(translations.component(player, "gui.farm-panel.processing.exists",
                Map.of("state", yesNo(player, snapshot.exists()))));
        if (batch != null) {
            lore.add(translations.component(player, "gui.farm-panel.processing.batch",
                    Map.of("batch", batch.batchId())));
            lore.add(translations.component(player, "gui.farm-panel.processing.strain",
                    Map.of("strain", batchStrains(batch))));
            lore.add(translations.component(player, "gui.farm-panel.processing.weight",
                    Map.of("weight", decimal(batch.dryWeight() > 0.0
                            ? batch.dryWeight() : batch.freshWeight()))));
            lore.add(translations.component(player, "gui.farm-panel.processing.quality",
                    Map.of(
                            "quality", decimal(batch.qualityScore()),
                            "grade", translations.plain(
                                    player,
                                    "quality.grades." + slug(batch.qualityGrade().name())
                            )
                    )));
        }
        snapshot.ownerUuid().ifPresent(owner -> lore.add(translations.component(
                player,
                "gui.farm-panel.processing.owner",
                Map.of("owner", owner)
        )));
        lore.add(translations.component(player, "gui.farm-panel.processing.remaining",
                Map.of("time", duration(snapshot.remainingSeconds()))));
        return new MenuEntry(
                snapshot.station().type().material(),
                translations.component(player, "gui.farm-panel.processing.entry-title",
                        Map.of("status", translations.plain(
                                player,
                                "gui.farm-panel.processing.statuses."
                                        + slug(snapshot.status().name())
                        ))),
                lore,
                null,
                snapshot.status() == ProcessingManager.StationStatus.READY
        );
    }

    private List<MenuEntry> priorityEntries(Player player, GuiContext context) {
        Farm farm = contextFarm(context).orElse(null);
        if (farm == null) {
            return List.of();
        }
        return workers.snapshots(farm.farmId()).stream()
                .filter(snapshot -> snapshot.currentTask().isPresent())
                .map(snapshot -> new MenuEntry(
                        Material.COMPARATOR,
                        translations.component(player, "gui.farm-panel.priorities.entry-title",
                                Map.of("worker", snapshot.record().name())),
                        List.of(
                                translations.component(player, "gui.workers.current-task",
                                        Map.of("task", taskName(
                                                player,
                                                snapshot.currentTask().orElseThrow()
                                        ))),
                                translations.component(player, "gui.farm-panel.priorities.value",
                                        Map.of("priority", snapshot.currentPriority().orElse(0)))
                        ),
                        null,
                        snapshot.currentPriority().orElse(0) >= 80
                ))
                .toList();
    }

    private List<MenuEntry> permissionEntries(Player player, GuiContext context) {
        Farm farm = contextFarm(context).orElse(null);
        if (farm == null) {
            return List.of();
        }
        return farm.members().values().stream()
                .sorted(Comparator.comparing(member -> member.playerUuid().toString()))
                .map(member -> permissionEntry(player, farm, member))
                .toList();
    }

    private MenuEntry permissionEntry(Player player, Farm farm, FarmMember member) {
        String effective = java.util.Arrays.stream(FarmPermission.values())
                .filter(member::can)
                .map(permission -> translations.plain(
                        player,
                        "gui.farm-panel.permissions.values."
                                + slug(permission.name())
                ))
                .collect(Collectors.joining(", "));
        return new MenuEntry(
                member.playerUuid().equals(farm.ownerUuid())
                        ? Material.GOLDEN_HELMET : Material.PLAYER_HEAD,
                translations.component(player, "gui.farm-panel.permissions.entry-title",
                        Map.of("member", member.playerUuid())),
                List.of(
                        translations.component(player, "gui.farm-panel.permissions.role",
                                Map.of("role", translations.plain(
                                        player,
                                        "gui.farm-panel.permissions.roles."
                                                + slug(member.role().name())
                                ))),
                        translations.component(player, "gui.farm-panel.permissions.effective",
                                Map.of("permissions", effective))
                ),
                null,
                member.playerUuid().equals(farm.ownerUuid())
        );
    }

    private List<MenuEntry> salaryEntries(Player player, GuiContext context) {
        Farm farm = contextFarm(context).orElse(null);
        if (farm == null) {
            return List.of();
        }
        FarmDashboardSnapshot snapshot = snapshot(farm);
        return List.of(new MenuEntry(
                Material.EMERALD,
                translations.component(player, "gui.farm-panel.salary.summary-title"),
                lines(player,
                        line("gui.farm-panel.dashboard.salary-storage", Map.of(
                                "state", yesNo(player, snapshot.salary().storagePresent()))),
                        line("gui.farm-panel.dashboard.salary-emeralds", Map.of(
                                "count", salaryEmeralds(player, snapshot.salary()))),
                        line("gui.farm-panel.dashboard.salary-cost", Map.of(
                                "count", snapshot.salary().nextCost())),
                        line("gui.farm-panel.dashboard.salary-paid", Map.of(
                                "count", snapshot.salary().paidWorkers())),
                        line("gui.farm-panel.dashboard.salary-unpaid", Map.of(
                                "count", snapshot.salary().unpaidWorkers()))
                ),
                null,
                snapshot.salary().unpaidWorkers() > 0
        ));
    }

    private List<MenuEntry> statisticEntries(Player player, GuiContext context) {
        Farm farm = contextFarm(context).orElse(null);
        if (farm == null) {
            return List.of();
        }
        List<Plant> farmPlants = farmPlants(farm.farmId());
        FarmDashboardSnapshot snapshot = snapshot(farm);
        String averageQuality = farmPlants.isEmpty()
                ? translations.plain(player, "gui.farm-panel.common.unavailable")
                : decimal(farmPlants.stream()
                        .mapToDouble(Plant::qualityPotential)
                        .average()
                        .orElseThrow());
        return List.of(new MenuEntry(
                Material.SPYGLASS,
                translations.component(player, "gui.farm-panel.statistics.current-title"),
                lines(player,
                        line("gui.farm-panel.statistics.level", Map.of("level", farm.level())),
                        line("gui.farm-panel.statistics.active-plants", Map.of(
                                "count", snapshot.plants().count())),
                        line("gui.farm-panel.statistics.average-quality", Map.of(
                                "quality", averageQuality)),
                        line("gui.farm-panel.statistics.workers", Map.of(
                                "count", snapshot.workers().count())),
                        line("gui.farm-panel.statistics.storages", Map.of(
                                "count", snapshot.storages().count())),
                        line("gui.farm-panel.statistics.processes", Map.of(
                                "count", snapshot.drying().active() + snapshot.curing().active()))
                ),
                null,
                false
        ));
    }

    private List<MenuEntry> notificationEntries(Player player) {
        return java.util.Arrays.stream(NotificationType.values())
                .map(type -> {
                    boolean enabled = notifications.enabled(player, type);
                    String slug = slug(type.name());
                    return new MenuEntry(
                            enabled ? Material.LIME_DYE : Material.GRAY_DYE,
                            translations.component(
                                    player,
                                    "gui.farm-panel.notifications.types." + slug
                            ),
                            List.of(translations.component(
                                    player,
                                    "gui.farm-panel.notifications.state",
                                    Map.of("state", translations.plain(
                                            player,
                                            enabled
                                                    ? "gui.farm-panel.common.enabled"
                                                    : "gui.farm-panel.common.disabled"
                                    ))
                            )),
                            "notification:" + type.name() + ':' + !enabled,
                            enabled
                    );
                })
                .toList();
    }

    private List<MenuEntry> unavailableUpgrade(Player player, GuiContext context) {
        return List.of(new MenuEntry(
                Material.BARRIER,
                translations.component(player, "gui.farm.upgrade.name"),
                List.of(translations.component(
                        player,
                        "gui.farm-panel.upgrades.unavailable"
                )),
                null,
                false
        ));
    }

    private FarmDashboardSnapshot snapshot(Farm farm) {
        return FarmDashboardSnapshot.create(
                farm,
                farmPlants(farm.farmId()),
                workers.snapshots(farm.farmId()),
                storageSnapshots(farm),
                processing.stationSnapshots(farm, ProcessingStationType.DRYING_RACK),
                processing.stationSnapshots(farm, ProcessingStationType.CURING_CONTAINER)
        );
    }

    private List<FarmDashboardSnapshot.StorageRuntimeSnapshot> storageSnapshots(Farm farm) {
        return farms.storages(farm.farmId()).stream()
                .map(this::storageSnapshot)
                .toList();
    }

    private FarmDashboardSnapshot.StorageRuntimeSnapshot storageSnapshot(
            FarmStorage storage
    ) {
        BlockPosition position = storage.position();
        World world = Bukkit.getWorld(position.worldUuid());
        if (world == null || !world.isChunkLoaded(position.x() >> 4, position.z() >> 4)) {
            return new FarmDashboardSnapshot.StorageRuntimeSnapshot(
                    storage, false, false, 0, 0, 0);
        }
        org.bukkit.block.BlockState state = world.getBlockAt(
                position.x(), position.y(), position.z()).getState();
        if (!(state instanceof Container container)) {
            return new FarmDashboardSnapshot.StorageRuntimeSnapshot(
                    storage, false, false, 0, 0, 0);
        }
        Inventory inventory = container.getInventory();
        int occupied = 0;
        int emeralds = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            occupied++;
            if (item.getType() == Material.EMERALD) {
                emeralds += item.getAmount();
            }
        }
        return new FarmDashboardSnapshot.StorageRuntimeSnapshot(
                storage,
                true,
                storage.enabled(),
                occupied,
                inventory.getSize(),
                emeralds
        );
    }

    private List<Plant> farmPlants(UUID farmId) {
        return FarmScopedData.plants(farmId, plants.all());
    }

    private Optional<Farm> contextFarm(GuiContext context) {
        return context.farmId().flatMap(farms::get);
    }

    private boolean usesFarmContext(MenuType type) {
        return switch (type) {
            case FARM, PLANTS, WORKERS, STORAGES, STORAGE_UNLINK_CONFIRM,
                 DRYING, CURING, PRIORITIES, PERMISSIONS, SALARY, STATISTICS,
                 UPGRADES -> true;
            default -> false;
        };
    }

    private List<MenuEntry> strainEntries(Player player) {
        return strains.all().stream()
                .map(strain -> new MenuEntry(
                        Material.WHEAT_SEEDS,
                        Component.text(strain.displayName()),
                        List.of(
                                translations.component(player, "gui.strains.rarity",
                                        Map.of("rarity", strain.rarity().name())),
                                translations.component(player, "gui.strains.growth-time",
                                        Map.of("time", strain.growthDuration().toMinutes() + "m")),
                                translations.component(player, "gui.strains.base-yield",
                                        Map.of("yield", decimal(strain.baseYield()))),
                                translations.component(player, "common.price",
                                        Map.of("price", strain.seedPrice()))
                        ),
                        null,
                        strain.rarity() == com.verdantvice.strain.StrainRarity.LEGENDARY
                ))
                .toList();
    }

    private List<MenuEntry> skillEntries(Player player) {
        PlayerProfile profile = profiles.get(player.getUniqueId()).orElse(null);
        if (profile == null) {
            return List.of();
        }
        List<MenuEntry> result = new ArrayList<>();
        for (SkillTree tree : SkillTree.values()) {
            String slug = tree.name().toLowerCase(Locale.ROOT);
            result.add(new MenuEntry(
                    Material.ENCHANTED_BOOK,
                    translations.component(player, "skills.trees." + slug + ".name"),
                    List.of(
                            translations.component(player, "common.level",
                                    Map.of("level", profile.skill(tree).level())),
                            translations.component(player, "gui.workers.experience",
                                    Map.of("xp", profile.skill(tree).experience())),
                            translations.component(player, "skills.trees." + slug + ".description")
                    ),
                    null,
                    profile.skill(tree).level() >= 20
            ));
        }
        return List.copyOf(result);
    }

    private List<MenuEntry> geneticEntries(Player player) {
        List<MenuEntry> entries = new ArrayList<>();
        entries.add(new MenuEntry(
                Material.EXPERIENCE_BOTTLE,
                translations.component(player, "gui.genetics.crossing"),
                List.of(translations.component(player, "commands.genetics.usage")),
                null,
                false
        ));
        genetics.lines().stream()
                .filter(record -> record.line().creatorUuid().equals(player.getUniqueId()))
                .forEach(record -> {
                    List<String> parents = record.line().parentStrainIds().stream()
                            .sorted()
                            .map(id -> strains.find(id).map(Strain::displayName).orElse(id))
                            .toList();
                    String parentA = parents.isEmpty()
                            ? translations.plain(player, "common.none") : parents.get(0);
                    String parentB = parents.size() < 2
                            ? translations.plain(player, "common.none") : parents.get(1);
                    entries.add(new MenuEntry(
                            Material.WHEAT_SEEDS,
                            Component.text(record.line().name()),
                            List.of(
                                    translations.component(player, "gui.genetics.parent-a",
                                            Map.of("parent", parentA)),
                                    translations.component(player, "gui.genetics.parent-b",
                                            Map.of("parent", parentB)),
                                    translations.component(player, "gui.genetics.generation",
                                            Map.of("generation", record.line().generation())),
                                    translations.component(player, "gui.genetics.stability",
                                            Map.of("stability", decimal(
                                                    record.line().stability() * 100.0) + "%"))
                            ),
                            null,
                            record.line().stability() >= 0.75
                    ));
                });
        return List.copyOf(entries);
    }

    private List<MenuEntry> settings(Player player) {
        return List.of(
                new MenuEntry(Material.NAME_TAG,
                        translations.component(player, "gui.settings.language"),
                        List.of(), "command:vv language", false),
                new MenuEntry(Material.BELL,
                        translations.component(player, "gui.settings.notifications"),
                        List.of(), "open:notifications", false),
                new MenuEntry(Material.PAINTING,
                        translations.component(player, "gui.settings.resourcepack"),
                        List.of(), "command:vv resourcepack", false)
        );
    }

    private List<MenuEntry> market(Player player) {
        return strains.all().stream()
                .map(strain -> new MenuEntry(
                        Material.EMERALD,
                        Component.text(strain.displayName()),
                        List.of(translations.component(player, "common.price",
                                Map.of("price", decimal(markets.cachedPrice(strain.id()))))),
                        null,
                        false
                ))
                .toList();
    }

    private MenuEntry processingTile(
            Player player,
            Material material,
            String nameKey,
            String action,
            FarmDashboardSnapshot.ProcessingMetrics metrics
    ) {
        return new MenuEntry(
                material,
                translations.component(player, nameKey),
                lines(player,
                        line("gui.farm-panel.dashboard.processing-total", Map.of(
                                "count", metrics.count())),
                        line("gui.farm-panel.dashboard.processing-active", Map.of(
                                "count", metrics.active())),
                        line("gui.farm-panel.dashboard.processing-ready", Map.of(
                                "count", metrics.ready())),
                        line("gui.farm-panel.dashboard.processing-free", Map.of(
                                "count", metrics.free()))
                ),
                action,
                metrics.ready() > 0
        );
    }

    private List<Component> storageDashboardLore(
            Player player,
            FarmDashboardSnapshot.StorageMetrics metrics
    ) {
        List<Component> lore = new ArrayList<>();
        lore.add(translations.component(player, "gui.farm-panel.dashboard.storage-total",
                Map.of("count", metrics.count())));
        for (StorageType type : StorageType.values()) {
            lore.add(translations.component(player, "gui.farm-panel.dashboard.storage-type",
                    Map.of(
                            "type", translations.plain(
                                    player,
                                    "gui.farm-panel.storage-types." + type.argument()
                            ),
                            "count", metrics.byType().getOrDefault(type, 0L)
                    )));
        }
        lore.add(translations.component(player, "gui.farm-panel.dashboard.storage-salary",
                Map.of("state", yesNo(player, metrics.salaryPresent()))));
        lore.add(translations.component(player, "gui.farm-panel.dashboard.storage-unavailable",
                Map.of("count", metrics.unavailable())));
        return List.copyOf(lore);
    }

    private MenuEntry simpleEntry(
            Material material,
            Player player,
            String nameKey,
            String descriptionKey,
            String action,
            boolean glowing
    ) {
        return new MenuEntry(
                material,
                translations.component(player, nameKey),
                List.of(translations.component(player, descriptionKey)),
                action,
                glowing
        );
    }

    @SafeVarargs
    private final List<Component> lines(Player player, Map.Entry<String, Map<String, ?>>... lines) {
        List<Component> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, ?>> line : lines) {
            result.add(translations.component(player, line.getKey(), line.getValue()));
        }
        return List.copyOf(result);
    }

    private Map.Entry<String, Map<String, ?>> line(String key, Map<String, ?> values) {
        return Map.entry(key, values);
    }

    private Map<String, Object> positionMap(BlockPosition position) {
        World world = Bukkit.getWorld(position.worldUuid());
        return Map.of(
                "world", world == null ? position.worldUuid() : world.getName(),
                "x", position.x(),
                "y", position.y(),
                "z", position.z()
        );
    }

    private String plantCondition(Player player, Plant plant) {
        if (plant.diseaseId().isPresent()) {
            return plain(player, "gui.farm-panel.plants.diseased",
                    Map.of("disease", plant.diseaseId().orElseThrow()));
        }
        if (plant.pestLevel() >= 15.0) {
            return plain(player, "gui.farm-panel.plants.pests",
                    Map.of("level", decimal(plant.pestLevel())));
        }
        return translations.plain(player, "gui.farm-panel.plants.healthy");
    }

    private String readiness(Player player, Plant plant, Instant now) {
        if (plant.growthStage() == GrowthStage.READY) {
            return translations.plain(player, "gui.farm-panel.plants.ready-now");
        }
        if (!plant.harvestWindowStart().isAfter(now)) {
            return translations.plain(player, stageKey(plant.growthStage()));
        }
        return duration(Math.max(0L, Duration.between(
                now,
                plant.harvestWindowStart()
        ).toSeconds()));
    }

    private String stageKey(GrowthStage stage) {
        return "plant.stages." + slug(stage.name());
    }

    private String taskName(Player player, WorkerTaskType type) {
        return translations.plain(
                player,
                "gui.farm-panel.worker.tasks." + slug(type.name())
        );
    }

    private String batchStrains(ProductBatch batch) {
        return batch.strainComposition().entrySet().stream()
                .map(entry -> strains.find(entry.getKey())
                        .map(Strain::displayName)
                        .orElse(entry.getKey())
                        + " " + Math.round(entry.getValue() * 100.0) + "%")
                .collect(Collectors.joining(", "));
    }

    private String plain(Player player, String key, Map<String, ?> placeholders) {
        return PlainTextComponentSerializer.plainText().serialize(
                translations.component(player, key, placeholders)
        );
    }

    private String yesNo(Player player, boolean value) {
        return translations.plain(
                player,
                value ? "gui.farm-panel.common.yes" : "gui.farm-panel.common.no"
        );
    }

    private String salaryEmeralds(
            Player player,
            FarmDashboardSnapshot.SalaryMetrics salary
    ) {
        return salary.storageAvailable()
                ? Integer.toString(salary.emeralds())
                : translations.plain(player, "gui.farm-panel.common.unavailable");
    }

    private String duration(long seconds) {
        long safe = Math.max(0L, seconds);
        long hours = safe / 3600L;
        long minutes = (safe % 3600L) / 60L;
        long remaining = safe % 60L;
        if (hours > 0L) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0L) {
            return minutes + "m " + remaining + "s";
        }
        return remaining + "s";
    }

    private Material storageIcon(StorageType type) {
        return switch (type) {
            case SEED_STORAGE -> Material.WHEAT_SEEDS;
            case TOOL_STORAGE -> Material.IRON_HOE;
            case FRESH_HARVEST_STORAGE -> Material.FLOWERING_AZALEA;
            case DRY_PRODUCT_STORAGE -> Material.DRIED_KELP_BLOCK;
            case PACKAGED_PRODUCT_STORAGE -> Material.BUNDLE;
            case SALARY_STORAGE -> Material.EMERALD;
            case WATER_STORAGE -> Material.WATER_BUCKET;
            case GENERAL_STORAGE -> Material.CHEST;
        };
    }

    private boolean canViewPlant(Player player, Plant plant) {
        if (plant.ownerUuid().equals(player.getUniqueId())) {
            return true;
        }
        return plant.farmId()
                .flatMap(farms::get)
                .map(farm -> player.hasPermission("verdantvice.admin")
                        || farm.can(player.getUniqueId(), FarmPermission.VIEW))
                .orElse(false);
    }

    private Material icon(GrowthStage stage) {
        return switch (stage) {
            case SEED -> Material.WHEAT_SEEDS;
            case SPROUT -> Material.SMALL_DRIPLEAF;
            case SEEDLING -> Material.AZALEA;
            case VEGETATIVE -> Material.FLOWERING_AZALEA;
            case MATURING -> Material.SPORE_BLOSSOM;
            case READY -> Material.PITCHER_PLANT;
            case OVERRIPE -> Material.WITHER_ROSE;
            case SPOILED -> Material.DEAD_BUSH;
        };
    }

    private String slug(String value) {
        return value.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private String decimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
