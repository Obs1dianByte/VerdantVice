package com.verdantvice.herbalist;

import com.verdantvice.config.ConfigManager;
import com.verdantvice.security.CustomItemType;
import com.verdantvice.strain.Strain;
import com.verdantvice.strain.StrainRarity;
import com.verdantvice.strain.StrainRegistry;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class HerbalistOfferCatalog {
    private final StrainRegistry strains;
    private final ConfigManager configs;

    public HerbalistOfferCatalog(StrainRegistry strains, ConfigManager configs) {
        this.strains = Objects.requireNonNull(strains, "strains");
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    public List<HerbalistOffer> available(HerbalistTier tier) {
        List<HerbalistOffer> offers = new ArrayList<>();
        double buyMultiplier = configs.get("economy.yml").getDouble("buy-price-multiplier", 1.15);
        strains.all().stream()
                .sorted(Comparator.comparing(Strain::id))
                .filter(strain -> tier.ordinal() >= seedTier(strain.rarity()).ordinal())
                .forEach(strain -> offers.add(new HerbalistOffer(
                        "seed:" + strain.id(),
                        HerbalistOfferKind.SEED,
                        Material.WHEAT_SEEDS,
                        CustomItemType.SEED,
                        Optional.of(strain.id()),
                        Optional.empty(),
                        price(strain.seedPrice(), buyMultiplier),
                        seedTier(strain.rarity()))));
        offers.add(tool("watering-can", CustomItemType.WATERING_CAN, Material.GLASS_BOTTLE,
                6, HerbalistTier.NOVICE));
        offers.add(tool("planting-pot", CustomItemType.PLANTING_POT, Material.DECORATED_POT,
                8, HerbalistTier.NOVICE));
        offers.add(tool("cleaning-kit", CustomItemType.CLEANING_KIT, Material.BRUSH,
                9, HerbalistTier.APPRENTICE));
        offers.add(tool("nutrient-pack", CustomItemType.NUTRIENT_PACK, Material.BONE_MEAL,
                7, HerbalistTier.APPRENTICE));
        offers.add(tool("treatment-spray", CustomItemType.TREATMENT_SPRAY, Material.HONEY_BOTTLE,
                12, HerbalistTier.JOURNEYMAN));
        offers.add(tool("plant-scanner", CustomItemType.PLANT_SCANNER, Material.CLOCK,
                18, HerbalistTier.JOURNEYMAN));
        offers.add(tool("repair-kit", CustomItemType.REPAIR_KIT, Material.IRON_NUGGET,
                12, HerbalistTier.EXPERT));
        offers.add(tool("drying-rack", CustomItemType.DRYING_RACK, Material.BARREL,
                24, HerbalistTier.EXPERT));
        offers.add(tool("curing-container", CustomItemType.CURING_CONTAINER,
                Material.CHISELED_BOOKSHELF, 28, HerbalistTier.MASTER));
        offers.add(tool("grinder", CustomItemType.GRINDER, Material.STONECUTTER,
                24, HerbalistTier.MASTER));
        offers.add(book(TutorialBookService.BookType.BASICS, 3, HerbalistTier.NOVICE));
        offers.add(book(TutorialBookService.BookType.PLANT_HEALTH, 8, HerbalistTier.APPRENTICE));
        offers.add(book(TutorialBookService.BookType.STRAIN_GUIDE, 10, HerbalistTier.JOURNEYMAN));
        offers.add(book(TutorialBookService.BookType.DRYING_CURING, 12, HerbalistTier.EXPERT));
        offers.add(book(TutorialBookService.BookType.ADVANCED_GENETICS, 16, HerbalistTier.MASTER));
        offers.add(book(TutorialBookService.BookType.FARM_MANAGEMENT, 16, HerbalistTier.MASTER));
        offers.add(book(TutorialBookService.BookType.PERFECT_BATCH, 24, HerbalistTier.VERDANT_EXPERT));
        int contractPrice = Math.max(0,
                configs.get("villagers.yml").getInt("hiring-contract.emeralds", 32));
        offers.add(new HerbalistOffer(
                "plantation-contract", HerbalistOfferKind.PLANTATION_CONTRACT,
                Material.WRITTEN_BOOK, CustomItemType.PLANTATION_CONTRACT,
                Optional.empty(), Optional.empty(), contractPrice, HerbalistTier.VERDANT_EXPERT));
        return offers.stream()
                .filter(offer -> tier.ordinal() >= offer.requiredTier().ordinal())
                .sorted(Comparator.comparing((HerbalistOffer offer) -> offer.kind().ordinal())
                        .thenComparing(HerbalistOffer::id))
                .toList();
    }

    private HerbalistOffer tool(
            String id,
            CustomItemType type,
            Material material,
            int fallbackPrice,
            HerbalistTier tier
    ) {
        int configured = configs.get("villagers.yml")
                .getInt("offer-prices." + id, fallbackPrice);
        return new HerbalistOffer(
                id, HerbalistOfferKind.TOOL, material, type, Optional.empty(),
                Optional.empty(), Math.max(0, configured), tier);
    }

    private HerbalistOffer book(
            TutorialBookService.BookType type,
            int fallbackPrice,
            HerbalistTier tier
    ) {
        int configured = configs.get("villagers.yml")
                .getInt("offer-prices.book-" + type.slug().replace('_', '-'), fallbackPrice);
        CustomItemType itemType = type == TutorialBookService.BookType.BASICS
                ? CustomItemType.GUIDE_BOOK : CustomItemType.ADVANCED_BOOK;
        return new HerbalistOffer(
                "book:" + type.slug(), HerbalistOfferKind.BOOK, Material.WRITTEN_BOOK,
                itemType, Optional.empty(), Optional.of(type), Math.max(0, configured), tier);
    }

    private static HerbalistTier seedTier(StrainRarity rarity) {
        return switch (rarity) {
            case BASIC -> HerbalistTier.NOVICE;
            case UNCOMMON -> HerbalistTier.JOURNEYMAN;
            case RARE -> HerbalistTier.MASTER;
            case LEGENDARY -> HerbalistTier.VERDANT_EXPERT;
        };
    }

    private static int price(int base, double multiplier) {
        return Math.max(1, (int) Math.ceil(base * Math.max(0.0, multiplier)));
    }
}
