package com.verdantvice.gui;

import com.verdantvice.farm.Farm;
import com.verdantvice.farm.FarmManager;
import com.verdantvice.genetics.GeneticsManager;
import com.verdantvice.i18n.TranslationService;
import com.verdantvice.market.PaperMarketService;
import com.verdantvice.plant.GrowthStage;
import com.verdantvice.plant.PlantManager;
import com.verdantvice.profile.PlayerProfile;
import com.verdantvice.profile.PlayerProfileManager;
import com.verdantvice.profile.SkillTree;
import com.verdantvice.strain.Strain;
import com.verdantvice.strain.StrainRegistry;
import com.verdantvice.worker.WorkerManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class DefaultGuiDataProvider implements GuiDataProvider {
    private final TranslationService translations;
    private final PlantManager plants;
    private final FarmManager farms;
    private final PlayerProfileManager profiles;
    private final StrainRegistry strains;
    private final GeneticsManager genetics;
    private final WorkerManager workers;
    private final PaperMarketService markets;

    public DefaultGuiDataProvider(
            TranslationService translations,
            PlantManager plants,
            FarmManager farms,
            PlayerProfileManager profiles,
            StrainRegistry strains,
            GeneticsManager genetics,
            WorkerManager workers,
            PaperMarketService markets
    ) {
        this.translations = Objects.requireNonNull(translations, "translations");
        this.plants = Objects.requireNonNull(plants, "plants");
        this.farms = Objects.requireNonNull(farms, "farms");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.strains = Objects.requireNonNull(strains, "strains");
        this.genetics = Objects.requireNonNull(genetics, "genetics");
        this.workers = Objects.requireNonNull(workers, "workers");
        this.markets = Objects.requireNonNull(markets, "markets");
    }

    @Override
    public List<MenuEntry> entries(Player player, MenuType type) {
        return switch (type) {
            case PLANTS -> plants(player);
            case STRAINS -> strains(player);
            case SKILLS -> skills(player);
            case FARM -> farm(player);
            case GUIDE -> List.of(entry(
                    Material.WRITTEN_BOOK,
                    player,
                    "book.basics.title",
                    "item.basics_book.description",
                    "command:vv guide",
                    true
            ));
            case SETTINGS -> settings(player);
            case MARKET -> market(player);
            case WORKERS -> workers(player);
            case GENETICS -> genetics(player);
            case CONTRACTS, STORAGES, DRYING, CURING,
                 PRIORITIES, PERMISSIONS, SALARY, STATISTICS, NOTIFICATIONS,
                 UPGRADES, DRYING_RACK, CURING_CONTAINER, GRINDER, MIXER, ROLLING ->
                    List.of();
            case MAIN -> List.of();
        };
    }

    @Override
    public Map<String, ?> titlePlaceholders(Player player, MenuType type) {
        if (type != MenuType.FARM) {
            return Map.of();
        }
        return Map.of(
                "farm",
                farms.owned(player.getUniqueId())
                        .map(Farm::name)
                        .orElse(translations.plain(player, "common.none"))
        );
    }

    private List<MenuEntry> plants(Player player) {
        return plants.all().stream()
                .filter(plant -> canViewPlant(player, plant))
                .sorted(Comparator.comparing(value -> value.plantedAt()))
                .map(plant -> {
                    Strain strain = strains.find(plant.strainId()).orElse(null);
                    String display = strain == null ? plant.strainId() : strain.displayName();
                    List<Component> lore = List.of(
                            translations.component(player, "gui.plants.exact-health",
                                    Map.of("health", decimal(plant.health()))),
                            translations.component(player, "gui.plants.hydration",
                                    Map.of("hydration", decimal(plant.hydration()))),
                            translations.component(player, "gui.plants.quality-potential",
                                    Map.of("quality", decimal(plant.qualityPotential())))
                    );
                    return new MenuEntry(
                            icon(plant.growthStage()),
                            translations.component(player, "gui.plants.details-title", Map.of(
                                    "strain", display,
                                    "stage", translations.plain(
                                            player,
                                            "plant.stages."
                                                    + plant.growthStage().name()
                                                    .toLowerCase(Locale.ROOT)
                                    )
                            )),
                            lore,
                            null,
                            plant.growthStage() == GrowthStage.READY
                    );
                })
                .toList();
    }

    private List<MenuEntry> strains(Player player) {
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

    private List<MenuEntry> skills(Player player) {
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

    private List<MenuEntry> genetics(Player player) {
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
                            .map(id -> strains.find(id)
                                    .map(Strain::displayName)
                                    .orElse(id))
                            .toList();
                    String parentA = parents.isEmpty()
                            ? translations.plain(player, "common.none")
                            : parents.get(0);
                    String parentB = parents.size() < 2
                            ? translations.plain(player, "common.none")
                            : parents.get(1);
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
                                                    record.line().stability() * 100.0
                                            ) + "%"))
                            ),
                            null,
                            record.line().stability() >= 0.75
                    ));
                });
        return List.copyOf(entries);
    }

    private List<MenuEntry> farm(Player player) {
        Farm farm = farms.owned(player.getUniqueId()).orElse(null);
        if (farm == null) {
            return List.of();
        }
        long farmPlants = plants.all().stream()
                .filter(plant -> plant.farmId().filter(farm.farmId()::equals).isPresent())
                .count();
        long ready = plants.all().stream()
                .filter(plant -> plant.farmId().filter(farm.farmId()::equals).isPresent())
                .filter(plant -> plant.growthStage() == GrowthStage.READY)
                .count();
        return List.of(
                new MenuEntry(
                        Material.FLOWERING_AZALEA,
                        translations.component(player, "gui.farm.plants.name"),
                        List.of(translations.component(player, "gui.farm.plants.description",
                                Map.of("count", farmPlants, "sick", 0, "ready", ready))),
                        "open:plants",
                        ready > 0
                ),
                entry(Material.VILLAGER_SPAWN_EGG, player,
                        "gui.farm.workers.name", "gui.farm.workers.description",
                        "open:workers", false),
                entry(Material.CHEST, player,
                        "gui.farm.storages.name", "gui.farm.storages.description",
                        "open:storages", false),
                entry(Material.BARREL, player,
                        "gui.farm.drying.name", "gui.farm.drying.description",
                        "open:drying", false),
                entry(Material.CHISELED_BOOKSHELF, player,
                        "gui.farm.curing.name", "gui.farm.curing.description",
                        "open:curing", false),
                entry(Material.COMPARATOR, player,
                        "gui.farm.priorities.name", "gui.farm.priorities.description",
                        "open:priorities", false),
                entry(Material.WRITABLE_BOOK, player,
                        "gui.farm.permissions.name", "gui.farm.permissions.description",
                        "open:permissions", false),
                entry(Material.EMERALD, player,
                        "gui.farm.salary.name", "gui.farm.salary.description",
                        "open:salary", false),
                entry(Material.ENDER_EYE, player,
                        "gui.farm.statistics.name", "gui.farm.statistics.description",
                        "open:statistics", false),
                entry(Material.BELL, player,
                        "gui.farm.notifications.name", "gui.farm.notifications.description",
                        "open:notifications", false),
                entry(Material.EXPERIENCE_BOTTLE, player,
                        "gui.farm.upgrade.name", "gui.farm.upgrade.description",
                        "open:upgrades", false),
                entry(Material.BARRIER, player,
                        "gui.farm.delete.name", "gui.farm.delete.description",
                        "command:vv farm delete", false)
        );
    }

    private List<MenuEntry> settings(Player player) {
        return List.of(
                new MenuEntry(
                        Material.NAME_TAG,
                        translations.component(player, "gui.settings.language"),
                        List.of(),
                        "command:vv language",
                        false
                ),
                new MenuEntry(
                        Material.BELL,
                        translations.component(player, "gui.settings.notifications"),
                        List.of(),
                        "command:vv notifications",
                        false
                ),
                new MenuEntry(
                        Material.PAINTING,
                        translations.component(player, "gui.settings.resourcepack"),
                        List.of(),
                        "command:vv resourcepack",
                        false
                )
        );
    }

    private List<MenuEntry> market(Player player) {
        return strains.all().stream()
                .map(strain -> new MenuEntry(
                        Material.EMERALD,
                        Component.text(strain.displayName()),
                        List.of(translations.component(
                                player,
                                "common.price",
                                Map.of("price", decimal(markets.cachedPrice(strain.id())))
                        )),
                        null,
                        false
                ))
                .toList();
    }

    private List<MenuEntry> workers(Player player) {
        Farm farm = farms.owned(player.getUniqueId()).orElse(null);
        if (farm == null) {
            return List.of();
        }
        return workers.workers(farm.farmId()).stream()
                .map(record -> {
                    var worker = record.worker();
                    return new MenuEntry(
                            Material.VILLAGER_SPAWN_EGG,
                            translations.component(
                                    player,
                                    "gui.workers.details-title",
                                    Map.of("worker", record.name())
                            ),
                            List.of(
                                    translations.component(
                                            player,
                                            "gui.workers.morale",
                                            Map.of("morale", decimal(worker.morale()))
                                    ),
                                    translations.component(
                                            player,
                                            "gui.workers.specialization",
                                            Map.of(
                                                    "specialization",
                                                    worker.specialization().name()
                                            )
                                    ),
                                    translations.component(
                                            player,
                                            "gui.workers.experience",
                                            Map.of("xp", worker.experience())
                                    ),
                                    translations.component(
                                            player,
                                            "gui.workers.current-task",
                                            Map.of("task", worker.state().name())
                                    ),
                                    translations.component(
                                            player,
                                            "gui.workers.unpaid-days",
                                            Map.of("days", worker.unpaidDays())
                                    )
                            ),
                            null,
                            worker.morale() >= 75.0
                    );
                })
                .toList();
    }

    private MenuEntry entry(
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
                List.of(translations.component(player, descriptionKey,
                        Map.of("count", 0, "morale", 0))),
                action,
                glowing
        );
    }

    private boolean canViewPlant(
            Player player,
            com.verdantvice.plant.Plant plant
    ) {
        if (plant.ownerUuid().equals(player.getUniqueId())) {
            return true;
        }
        org.bukkit.World world = org.bukkit.Bukkit.getWorld(plant.position().worldUuid());
        if (world == null) {
            return false;
        }
        org.bukkit.Location location = new org.bukkit.Location(
                world,
                plant.position().x(),
                plant.position().y(),
                plant.position().z()
        );
        return farms.at(location)
                .map(farm -> farm.members().containsKey(player.getUniqueId()))
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

    private String decimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
