package com.verdantvice;

import com.verdantvice.api.DefaultVerdantViceApi;
import com.verdantvice.api.VerdantViceApi;
import com.verdantvice.command.VerdantViceCommand;
import com.verdantvice.config.ConfigManager;
import com.verdantvice.database.DatabaseConfig;
import com.verdantvice.database.DatabaseManager;
import com.verdantvice.database.item.ItemRegistryRepository;
import com.verdantvice.diagnostic.DiagnosticService;
import com.verdantvice.effects.PlayerEffectListener;
import com.verdantvice.effects.PlayerEffectManager;
import com.verdantvice.farm.FarmManager;
import com.verdantvice.farm.FarmProtectionListener;
import com.verdantvice.genetics.GeneticsManager;
import com.verdantvice.gui.DefaultGuiDataProvider;
import com.verdantvice.gui.GuiListener;
import com.verdantvice.gui.GuiManager;
import com.verdantvice.herbalist.HerbalistMarketManager;
import com.verdantvice.herbalist.TutorialBookService;
import com.verdantvice.i18n.TranslationService;
import com.verdantvice.integration.DefaultPlaceholderValues;
import com.verdantvice.integration.RegionProtectionService;
import com.verdantvice.integration.VerdantPlaceholderExpansion;
import com.verdantvice.item.CustomItemFactory;
import com.verdantvice.notification.NotificationService;
import com.verdantvice.plant.PlantDisplayListener;
import com.verdantvice.plant.PlantDisplayService;
import com.verdantvice.plant.PlantManager;
import com.verdantvice.plant.PlantWorldListener;
import com.verdantvice.processing.ProcessingListener;
import com.verdantvice.processing.ProcessingManager;
import com.verdantvice.profile.PlayerProfileListener;
import com.verdantvice.profile.PlayerProfileManager;
import com.verdantvice.resourcepack.ResourcePackListener;
import com.verdantvice.resourcepack.ResourcePackService;
import com.verdantvice.security.ItemSecurityListener;
import com.verdantvice.security.ItemSigner;
import com.verdantvice.security.SecureItemService;
import com.verdantvice.security.SigningSecretManager;
import com.verdantvice.storage.BatchRepository;
import com.verdantvice.storage.CustomStrainRepository;
import com.verdantvice.storage.FarmRepository;
import com.verdantvice.storage.PlantRepository;
import com.verdantvice.storage.PlayerDataRepository;
import com.verdantvice.storage.PlayerProfileRepository;
import com.verdantvice.strain.StrainConfigLoader;
import com.verdantvice.strain.StrainRegistry;
import com.verdantvice.trade.EconomyService;
import com.verdantvice.worker.WorkerManager;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

public final class VerdantVicePlugin extends JavaPlugin {
    private final AtomicReference<PluginState> state =
            new AtomicReference<>(PluginState.NEW);
    private final List<BukkitTask> scheduledTasks = new ArrayList<>();

    private ConfigManager configs;
    private TranslationService translations;
    private DatabaseManager database;
    private StrainRegistry strains;
    private ItemSigner itemSigner;
    private SecureItemService secureItems;
    private RegionProtectionService regionProtection;
    private EconomyService economy;
    private FarmManager farms;
    private GeneticsManager genetics;
    private PlantManager plants;
    private ProcessingManager processing;
    private PlayerProfileManager profiles;
    private PlayerEffectManager effects;
    private NotificationService notifications;
    private ResourcePackService resourcePack;
    private GuiManager gui;
    private TutorialBookService books;
    private HerbalistMarketManager herbalists;
    private WorkerManager workers;
    private DiagnosticService diagnostics;
    private PlantDisplayService displays;
    private VerdantViceApi publicApi;
    private byte[] signingSecret;

    @Override
    public void onEnable() {
        if (!state.compareAndSet(PluginState.NEW, PluginState.STARTING)) {
            return;
        }
        try {
            configs = new ConfigManager(this);
            configs.bootstrap();
            translations = new TranslationService(this, configs);
            translations.load();
            signingSecret = new SigningSecretManager(configs).loadOrCreate();
            itemSigner = new ItemSigner(this, signingSecret);
            strains = new StrainConfigLoader(configs).load();

            DatabaseConfig databaseConfig = DatabaseConfig.fromBukkit(
                    configs.get("database.yml"),
                    getDataFolder().toPath()
            );
            database = new DatabaseManager(databaseConfig, getLogger());
            registerCommand();
            database.start().whenComplete((report, failure) -> {
                if (failure != null) {
                    Bukkit.getScheduler().runTask(this, () -> failStartup(failure));
                    return;
                }
                Bukkit.getScheduler().runTask(this, () -> initializeRuntime(report.currentVersion()));
            });
        } catch (Throwable failure) {
            failStartup(failure);
        }
    }

    private void initializeRuntime(int migrationVersion) {
        if (state.get() != PluginState.STARTING) {
            return;
        }
        try {
            PlayerDataRepository playerData = new PlayerDataRepository(database);
            FarmRepository farmRepository = new FarmRepository(database);
            PlantRepository plantRepository = new PlantRepository(database);
            BatchRepository batchRepository = new BatchRepository(database);
            PlayerProfileRepository profileRepository =
                    new PlayerProfileRepository(database);
            ItemRegistryRepository itemRegistry = new ItemRegistryRepository(database);
            CustomStrainRepository customStrains = new CustomStrainRepository(database);

            translations.setPreferenceWriter(playerData::setLocale);
            CustomItemFactory itemFactory =
                    new CustomItemFactory(configs, translations, itemSigner, strains);
            secureItems = new SecureItemService(
                    itemFactory,
                    itemSigner,
                    itemRegistry,
                    Duration.ofSeconds(configs.main().getLong(
                            "security.reservation-timeout-seconds",
                            30L
                    ))
            );
            regionProtection = new RegionProtectionService(this, configs);
            regionProtection.detect();
            economy = new EconomyService(this, configs);
            economy.detect();
            notifications = new NotificationService(this, configs, translations);
            resourcePack = new ResourcePackService(configs, translations);
            farms = new FarmManager(
                    this,
                    configs,
                    farmRepository,
                    regionProtection
            );
            genetics = new GeneticsManager(
                    this,
                    configs,
                    customStrains,
                    strains,
                    secureItems
            );
            plants = new PlantManager(
                    this,
                    configs,
                    translations,
                    farms,
                    plantRepository,
                    batchRepository,
                    secureItems,
                    itemSigner,
                    strains,
                    genetics,
                    signingSecret
            );
            displays = new PlantDisplayService(
                    this,
                    configs,
                    translations,
                    resourcePack,
                    plants
            );
            plants.attachDisplays(displays);
            processing = new ProcessingManager(
                    this,
                    configs,
                    translations,
                    notifications,
                    database,
                    batchRepository,
                    secureItems,
                    itemSigner,
                    signingSecret
            );
            profiles = new PlayerProfileManager(
                    this,
                    profileRepository,
                    translations
            );
            effects = new PlayerEffectManager(
                    this,
                    configs,
                    translations,
                    profiles,
                    processing,
                    secureItems,
                    itemSigner,
                    strains
            );
            books = new TutorialBookService(translations, itemSigner);
            herbalists = new HerbalistMarketManager(
                    this,
                    configs,
                    translations,
                    database,
                    strains,
                    economy,
                    secureItems,
                    itemSigner,
                    itemRegistry,
                    books
            );
            workers = WorkerManager.create(
                    this,
                    configs,
                    translations,
                    farms,
                    plants,
                    herbalists.conversionService(),
                    secureItems,
                    notifications,
                    database
            );
            gui = new GuiManager(
                    this,
                    configs,
                    translations,
                    new DefaultGuiDataProvider(
                            translations,
                            plants,
                            farms,
                            profiles,
                            strains,
                            genetics,
                            workers,
                            herbalists.marketService()
                    )
            );
            diagnostics = new DiagnosticService(
                    this,
                    configs,
                    database,
                    farms,
                    plants,
                    processing
            );

            CompletableFuture<Void> farmsLoaded = farms.load();
            CompletableFuture<Void> geneticsLoaded = genetics.load();
            CompletableFuture<Void> plantsLoaded = plants.load();
            CompletableFuture<Void> processingLoaded = processing.load();
            CompletableFuture<Void> herbalistsLoaded = geneticsLoaded.thenComposeAsync(
            ignored -> herbalists.start(),
            task -> Bukkit.getScheduler().runTask(this, task)
            );
            CompletableFuture<Void> workersLoaded = workers.load();
            CompletableFuture.allOf(
                            farmsLoaded,
                            geneticsLoaded,
                            plantsLoaded,
                            processingLoaded,
                            herbalistsLoaded,
                            workersLoaded
                    )
                    .whenComplete((ignored, failure) -> Bukkit.getScheduler().runTask(
                            this,
                            () -> {
                                if (failure != null) {
                                    failStartup(failure);
                                } else {
                                    finishRuntime(migrationVersion);
                                }
                            }
                    ));
        } catch (Throwable failure) {
            failStartup(failure);
        }
    }

    private void finishRuntime(int migrationVersion) {
        if (state.get() != PluginState.STARTING) {
            return;
        }
        registerListeners();
        displays.restoreLoadedChunks();
        plants.start();
        processing.start();
        workers.start();
        Bukkit.getOnlinePlayers().forEach(profiles::load);
        registerOptionalIntegrations();
        long savePeriod = Math.max(
                100L,
                configs.main().getLong("performance.database-save-seconds", 60L) * 20L
        );
        scheduledTasks.add(Bukkit.getScheduler().runTaskTimer(
                this,
                () -> profiles.saveAll().exceptionally(failure -> {
                    getLogger().log(Level.WARNING, "Periodic profile save failed", failure);
                    return null;
                }),
                savePeriod,
                savePeriod
        ));
        state.set(PluginState.READY);
        publicApi = new DefaultVerdantViceApi(this);
        getServer().getServicesManager().register(
                VerdantViceApi.class,
                publicApi,
                this,
                ServicePriority.Normal
        );

        getLogger().info("Loaded " + strains.size() + " strains.");
        getLogger().info("Loaded " + translations.languageCount() + " languages.");
        getLogger().info("Database: " + database.diagnostics().type()
                + " (schema " + migrationVersion + ").");
        getLogger().info("Economy: " + economy.providerName() + ".");
        getLogger().info("Region adapters: "
                + (regionProtection.activeAdapters().isEmpty()
                ? "none" : String.join(", ", regionProtection.activeAdapters())) + ".");
        getLogger().info("Resource pack: " + (resourcePack.enabled() ? "enabled" : "optional/off") + ".");
        getLogger().info("Plugin enabled successfully.");
    }

    private void registerListeners() {
        var manager = getServer().getPluginManager();
        manager.registerEvents(new GuiListener(gui), this);
        manager.registerEvents(new FarmProtectionListener(
                this,
                farms,
                itemSigner,
                secureItems,
                translations
        ), this);
        manager.registerEvents(new PlantWorldListener(
                this,
                plants,
                itemSigner,
                secureItems,
                translations
        ), this);
        manager.registerEvents(new PlantDisplayListener(displays, plants), this);
        manager.registerEvents(new ProcessingListener(
                this,
                processing,
                itemSigner,
                secureItems,
                translations
        ), this);
        manager.registerEvents(new PlayerProfileListener(
                this,
                profiles,
                plants,
                processing,
                notifications
        ), this);
        manager.registerEvents(new PlayerEffectListener(effects, itemSigner), this);
        manager.registerEvents(new ItemSecurityListener(itemSigner, translations), this);
        manager.registerEvents(new ResourcePackListener(resourcePack), this);
        manager.registerEvents(workers.listener(), this);
    }

    private void registerOptionalIntegrations() {
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                new VerdantPlaceholderExpansion(
                        this,
                        new DefaultPlaceholderValues(
                                farms,
                                plants,
                                profiles,
                                strains,
                                workers,
                                herbalists.marketService()
                        )
                ).register();
                getLogger().info("PlaceholderAPI integration enabled.");
            } catch (LinkageError | RuntimeException failure) {
                getLogger().log(
                        Level.WARNING,
                        "PlaceholderAPI adapter could not be registered",
                        failure
                );
            }
        }
        for (String name : List.of("ItemsAdder", "Oraxen", "LuckPerms")) {
            if (getServer().getPluginManager().isPluginEnabled(name)) {
                getLogger().info(name + " detected; public item/permission APIs remain compatible.");
            }
        }
    }

    private void registerCommand() {
        PluginCommand command = Objects.requireNonNull(
                getCommand("verdantvice"),
                "Command verdantvice is missing from plugin.yml"
        );
        VerdantViceCommand executor = new VerdantViceCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void failStartup(Throwable failure) {
        Throwable cause = unwrap(failure);
        getLogger().log(Level.SEVERE, "VerdantVice startup failed safely", cause);
        state.set(PluginState.FAILED);
        getServer().getPluginManager().disablePlugin(this);
    }

    @Override
    public void onDisable() {
        PluginState previous = state.getAndSet(PluginState.STOPPING);
        scheduledTasks.forEach(BukkitTask::cancel);
        scheduledTasks.clear();
        Bukkit.getScheduler().cancelTasks(this);
        HandlerList.unregisterAll(this);
        if (plants != null && Bukkit.isPrimaryThread()) {
            plants.stop();
        }
        if (processing != null && Bukkit.isPrimaryThread()) {
            processing.stop();
        }
        if (workers != null && Bukkit.isPrimaryThread()) {
            workers.stop();
        }
        if (herbalists != null && Bukkit.isPrimaryThread()) {
            herbalists.close();
        }
        if (displays != null && Bukkit.isPrimaryThread()) {
            displays.shutdown();
        }
        if ((profiles != null || workers != null)
                && database != null
                && database.isRunning()) {
            try {
                CompletableFuture<Void> profileSave = profiles == null
                        ? CompletableFuture.completedFuture(null)
                        : profiles.saveAll();
                CompletableFuture<Void> workerFlush = workers == null
                        ? CompletableFuture.completedFuture(null)
                        : workers.flush();
                CompletableFuture.allOf(profileSave, workerFlush)
                        .get(10, TimeUnit.SECONDS);
            } catch (Exception failure) {
                getLogger().log(
                        Level.WARNING,
                        "Final profile/worker persistence did not finish",
                        failure
                );
            }
        }
        if (database != null) {
            database.close();
        }
        getServer().getServicesManager().unregisterAll(this);
        state.set(PluginState.STOPPED);
        if (previous != PluginState.NEW) {
            getLogger().info("Plugin disabled; schedulers and database connections are closed.");
        }
    }

    public boolean ready() {
        return state.get() == PluginState.READY;
    }

    public PluginState state() {
        return state.get();
    }

    public ConfigManager configs() {
        return require(configs, "configuration");
    }

    public TranslationService translations() {
        return require(translations, "translations");
    }

    public DatabaseManager database() {
        return require(database, "database");
    }

    public StrainRegistry strains() {
        return require(strains, "strains");
    }

    public ItemSigner itemSigner() {
        return require(itemSigner, "item signer");
    }

    public SecureItemService secureItems() {
        return require(secureItems, "secure items");
    }

    public FarmManager farms() {
        return require(farms, "farms");
    }

    public GeneticsManager genetics() {
        return require(genetics, "genetics");
    }

    public PlantManager plants() {
        return require(plants, "plants");
    }

    public ProcessingManager processing() {
        return require(processing, "processing");
    }

    public PlayerProfileManager profiles() {
        return require(profiles, "profiles");
    }

    public ResourcePackService resourcePack() {
        return require(resourcePack, "resource pack");
    }

    public GuiManager gui() {
        return require(gui, "GUI");
    }

    public TutorialBookService books() {
        return require(books, "books");
    }

    public HerbalistMarketManager herbalists() {
        return require(herbalists, "herbalists");
    }

    public WorkerManager workers() {
        return require(workers, "workers");
    }

    public VerdantViceApi api() {
        return require(publicApi, "public API");
    }

    public DiagnosticService diagnostics() {
        return require(diagnostics, "diagnostics");
    }

    public EconomyService economy() {
        return require(economy, "economy");
    }

    public NotificationService notifications() {
        return require(notifications, "notifications");
    }

    public ConfigManager.ReloadReport reloadSafe() {
        if (!ready()) {
            throw new IllegalStateException("Plugin is not ready");
        }
        ConfigManager.ReloadReport report = configs.reloadSafe();
        translations.load();
        if (herbalists != null && Bukkit.isPrimaryThread()) {
            herbalists.refreshNames();
        }
        StrainRegistry reloaded = new StrainConfigLoader(configs).load();
        reloaded.all().forEach(strain -> {
            if (strains.find(strain.id()).isPresent()) {
                strains.replace(strain);
            } else {
                strains.register(strain);
            }
        });
        regionProtection.detect();
        economy.detect();
        return report;
    }

    private <T> T require(T value, String module) {
        if (value == null) {
            throw new IllegalStateException(module + " module is not initialized");
        }
        return value;
    }

    private Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    public enum PluginState {
        NEW,
        STARTING,
        READY,
        FAILED,
        STOPPING,
        STOPPED
    }
}
