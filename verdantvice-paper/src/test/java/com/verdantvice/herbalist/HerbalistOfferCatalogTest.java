package com.verdantvice.herbalist;

import com.verdantvice.effects.EffectProfile;
import com.verdantvice.effects.EffectType;
import com.verdantvice.processing.BatchState;
import com.verdantvice.security.CustomItemType;
import com.verdantvice.strain.Strain;
import com.verdantvice.strain.StrainRarity;
import com.verdantvice.strain.StrainRegistry;
import com.verdantvice.trade.SaleItemPolicy;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HerbalistOfferCatalogTest {
    private final Map<String, YamlConfiguration> configurations = new HashMap<>();
    private StrainRegistry strains;
    private HerbalistOfferCatalog catalog;

    @BeforeEach
    void defaults() throws Exception {
        for (String resource : List.of("villagers.yml", "economy.yml", "items.yml",
                "market.yml", "strains.yml")) {
            configurations.put(resource, resource(resource));
        }
        strains = defaultStrains(configurations.get("strains.yml"));
        catalog = new HerbalistOfferCatalog(strains, configurations::get);
    }

    @Test
    void noviceCanDefineAPlantationGrowAndDryTheFirstSellableHarvest() {
        Set<CustomItemType> starterTypes = types(HerbalistTier.NOVICE);
        assertTrue(starterTypes.containsAll(Set.of(CustomItemType.SEED,
                CustomItemType.SELECTION_WAND, CustomItemType.FARM_MANAGER_TABLE,
                CustomItemType.PLANTING_POT, CustomItemType.WATERING_CAN,
                CustomItemType.DRYING_RACK)));

        SaleItemPolicy sales = new SaleItemPolicy();
        Set<CustomItemType> accepted = sales.acceptedTypes(configurations.get("market.yml")
                .getStringList("trade.accepted-item-types"));
        assertFalse(accepted.contains(CustomItemType.FRESH_BATCH));
        assertNull(sales.rejectionReason(CustomItemType.DRY_BATCH, BatchState.DRIED, accepted),
                "The novice drying station must produce a product the default market accepts");
    }

    @Test
    void starterFarmIncludingItsManagementToolsCostsAtMostOneStackOfEmeralds() {
        List<HerbalistOffer> novice = catalog.available(HerbalistTier.NOVICE);
        int cheapestSeed = novice.stream().filter(offer -> offer.kind() == HerbalistOfferKind.SEED)
                .mapToInt(HerbalistOffer::emeraldPrice).min().orElseThrow();
        int setup = cheapestSeed;
        for (String id : List.of("selection-wand", "farm-manager-table", "planting-pot",
                "watering-can", "drying-rack")) {
            setup += offer(HerbalistTier.NOVICE, id).emeraldPrice();
        }
        assertTrue(setup > 0 && setup <= 64, "Default starter farm costs " + setup + " emeralds");
    }

    @Test
    void firstCropProblemsHaveRemediesAndDiagnosticsBeforeMerchantProgress() {
        assertTrue(types(HerbalistTier.NOVICE).containsAll(Set.of(
                CustomItemType.PLANT_SCANNER, CustomItemType.CLEANING_KIT,
                CustomItemType.NUTRIENT_PACK, CustomItemType.TREATMENT_SPRAY,
                CustomItemType.REPAIR_KIT)));
    }

    @Test
    void laterProcessingUnlocksAlwaysIncludeAllTheirRequiredSupplies() {
        assertFalse(types(HerbalistTier.NOVICE).contains(CustomItemType.CURING_CONTAINER));
        assertTrue(types(HerbalistTier.APPRENTICE).contains(CustomItemType.CURING_CONTAINER));
        assertFalse(types(HerbalistTier.APPRENTICE).contains(CustomItemType.GRINDER));
        assertTrue(types(HerbalistTier.JOURNEYMAN).containsAll(Set.of(
                CustomItemType.CURING_CONTAINER, CustomItemType.GRINDER,
                CustomItemType.ROLLING_PAPER, CustomItemType.FILTER)));
        assertEquals(1, offer(HerbalistTier.JOURNEYMAN, "rolling-paper").emeraldPrice());
        assertEquals(1, offer(HerbalistTier.JOURNEYMAN, "filter").emeraldPrice());
        assertFalse(types(HerbalistTier.MASTER).contains(CustomItemType.PLANTATION_CONTRACT));
        assertTrue(types(HerbalistTier.VERDANT_EXPERT).contains(CustomItemType.PLANTATION_CONTRACT));
    }

    @Test
    void everyNonProductItemHasASurvivalMerchantSource() {
        EnumSet<CustomItemType> required = EnumSet.allOf(CustomItemType.class);
        required.removeIf(CustomItemType::batchBound);
        assertEquals(required, types(HerbalistTier.VERDANT_EXPERT));
        assertTrue(catalog.available(HerbalistTier.VERDANT_EXPERT).stream()
                .noneMatch(offer -> offer.itemType().batchBound()),
                "Products must still be earned by growing and processing crops");
    }

    @Test
    void essentialInstructionBooksCanBeBoughtBeforeStarting() {
        Set<TutorialBookService.BookType> books = catalog.available(HerbalistTier.NOVICE).stream()
                .flatMap(offer -> offer.bookType().stream()).collect(Collectors.toSet());
        assertTrue(books.containsAll(Set.of(TutorialBookService.BookType.BASICS,
                TutorialBookService.BookType.RECIPES, TutorialBookService.BookType.PLANT_HEALTH,
                TutorialBookService.BookType.DRYING_CURING,
                TutorialBookService.BookType.FARM_MANAGEMENT)));
    }

    @Test
    void allDefaultSeedsKeepRarityUnlocksAndConfiguredPrices() {
        assertEquals(12, strains.size());
        double multiplier = configurations.get("economy.yml").getDouble("buy-price-multiplier");
        for (Strain strain : strains.all()) {
            HerbalistTier unlock = switch (strain.rarity()) {
                case BASIC -> HerbalistTier.NOVICE;
                case UNCOMMON -> HerbalistTier.JOURNEYMAN;
                case RARE -> HerbalistTier.MASTER;
                case LEGENDARY -> HerbalistTier.VERDANT_EXPERT;
            };
            for (HerbalistTier tier : HerbalistTier.values()) {
                assertEquals(tier.ordinal() >= unlock.ordinal(),
                        catalog.available(tier).stream().anyMatch(offer ->
                                offer.id().equals("seed:" + strain.id())), strain.id() + ": " + tier);
            }
            HerbalistOffer seed = offer(unlock, "seed:" + strain.id());
            assertEquals(unlock, seed.requiredTier());
            assertEquals((int) Math.ceil(strain.seedPrice() * multiplier), seed.emeraldPrice());
        }
    }

    @Test
    void everyOfferedToolMatchesItsConfiguredVanillaFallback() {
        YamlConfiguration items = configurations.get("items.yml");
        for (HerbalistOffer offer : catalog.available(HerbalistTier.VERDANT_EXPERT)) {
            if (offer.kind() != HerbalistOfferKind.TOOL) continue;
            assertEquals(Material.valueOf(items.getString(offer.id() + ".material")),
                    offer.previewMaterial(), offer.id());
        }
    }

    @Test
    void catalogueRemainsMonotonicUniqueAndWithinTheDefaultShopCapacity() {
        Set<String> previous = Set.of();
        for (HerbalistTier tier : HerbalistTier.values()) {
            List<HerbalistOffer> offers = catalog.available(tier);
            Set<String> ids = offers.stream().map(HerbalistOffer::id).collect(Collectors.toSet());
            assertEquals(offers.size(), ids.size(), tier.name());
            assertTrue(ids.containsAll(previous), tier.name());
            assertTrue(offers.size() <= 45, "Shop needs pagination for " + tier);
            assertTrue(offers.stream().allMatch(offer ->
                    offer.requiredTier().ordinal() <= tier.ordinal()));
            previous = ids;
        }
        assertEquals(35, previous.size());
    }

    @Test
    void newSupplyPricesRespectOverridesAndLiveConfigurationReload() throws Exception {
        YamlConfiguration villagers = configurations.get("villagers.yml");
        villagers.set("offer-prices.selection-wand", 11);
        villagers.set("offer-prices.farm-manager-table", 21);
        villagers.set("offer-prices.rolling-paper", 3);
        villagers.set("offer-prices.filter", -1);
        assertEquals(11, offer(HerbalistTier.NOVICE, "selection-wand").emeraldPrice());
        assertEquals(21, offer(HerbalistTier.NOVICE, "farm-manager-table").emeraldPrice());
        assertEquals(3, offer(HerbalistTier.JOURNEYMAN, "rolling-paper").emeraldPrice());
        assertEquals(0, offer(HerbalistTier.JOURNEYMAN, "filter").emeraldPrice());

        YamlConfiguration replacement = resource("villagers.yml");
        replacement.set("offer-prices.selection-wand", 19);
        configurations.put("villagers.yml", replacement);
        assertEquals(19, offer(HerbalistTier.NOVICE, "selection-wand").emeraldPrice());
    }

    private Set<CustomItemType> types(HerbalistTier tier) {
        return catalog.available(tier).stream().map(HerbalistOffer::itemType)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private HerbalistOffer offer(HerbalistTier tier, String id) {
        return catalog.available(tier).stream().filter(value -> value.id().equals(id))
                .findFirst().orElseThrow(() -> new AssertionError("Missing " + id + " at " + tier));
    }

    private static YamlConfiguration resource(String name) throws Exception {
        try (var stream = HerbalistOfferCatalogTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(stream, name);
            YamlConfiguration result = new YamlConfiguration();
            result.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            return result;
        }
    }

    private static StrainRegistry defaultStrains(YamlConfiguration configuration) {
        StrainRegistry registry = new StrainRegistry();
        ConfigurationSection root = configuration.getConfigurationSection("strains");
        assertNotNull(root);
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            assertNotNull(section);
            registry.register(new Strain(id, section.getString("display-name"),
                    StrainRarity.valueOf(section.getString("rarity")),
                    Duration.ofSeconds(section.getLong("growth-duration-seconds")),
                    section.getDouble("difficulty"), section.getDouble("base-yield"),
                    section.getDouble("quality-potential"), section.getDouble("disease-resistance"),
                    section.getDouble("water-consumption"), section.getDouble("light-preference"),
                    section.getDouble("stress-sensitivity"), section.getDouble("smell-intensity"),
                    effects(section, "effect-profile"), effects(section, "negative-effect-profile"),
                    section.getInt("seed-price"), section.getDouble("base-market-price"),
                    section.getString("visual-model"), section.getString("particle-profile")));
        }
        return registry;
    }

    private static EffectProfile effects(ConfigurationSection strain, String key) {
        ConfigurationSection section = strain.getConfigurationSection(key);
        Map<EffectType, Double> weights = new EnumMap<>(EffectType.class);
        if (section != null) {
            section.getKeys(false).forEach(name ->
                    weights.put(EffectType.valueOf(name), section.getDouble(name)));
        }
        return new EffectProfile(weights);
    }
}
