package com.verdantvice.resourcepack;

import com.verdantvice.plant.GrowthStage;
import com.verdantvice.security.CustomItemType;

import java.util.List;
import java.util.Objects;

/** Stable model numbers shared by the item factory, plant displays and pack checks. */
public final class ResourcePackModels {
    private static final List<String> STRAIN_IDS = List.of(
            "berry_fog", "concrete_kush", "lemon_static", "green_alley",
            "neon_haze", "night_widow", "purple_crash", "velvet_smoke",
            "ghost_garden", "royal_static", "black_orchard", "verdant_vice");

    private ResourcePackModels() { }

    public static List<String> strainIds() {
        return STRAIN_IDS;
    }

    public static int item(CustomItemType type) {
        return switch (Objects.requireNonNull(type, "type")) {
            case SEED -> 12001;
            case PLANTING_POT -> 12002;
            case WATERING_CAN -> 12003;
            case PLANT_SCANNER -> 12004;
            case CLEANING_KIT -> 12005;
            case TREATMENT_SPRAY -> 12006;
            case NUTRIENT_PACK -> 12007;
            case REPAIR_KIT -> 12008;
            case DRYING_RACK -> 12009;
            case CURING_CONTAINER -> 12010;
            case GRINDER -> 12011;
            case FARM_MANAGER_TABLE -> 12012;
            case SELECTION_WAND -> 12013;
            case ROLLING_PAPER -> 12014;
            case FILTER -> 12015;
            case PACKAGE -> 12016;
            case JOINT -> 12017;
            case FRESH_BATCH -> 12018;
            case DRY_BATCH -> 12019;
            case CURED_BATCH -> 12020;
            case GROUND_PRODUCT -> 12021;
            case MIXTURE -> 12022;
            case PLANTATION_CONTRACT -> 12023;
            case GUIDE_BOOK -> 12024;
            case ADVANCED_BOOK -> 12025;
        };
    }

    public static int seed(String strainId, int configuredModel) {
        int index = STRAIN_IDS.indexOf(strainId);
        return configuredModel == item(CustomItemType.SEED) && index >= 0
                ? 13001 + index : configuredModel;
    }

    public static int plant(String visualModel, GrowthStage stage) {
        Objects.requireNonNull(stage, "stage");
        int index = STRAIN_IDS.indexOf(visualModel);
        return index >= 0 ? 13101 + index * 10 + stage.ordinal()
                : 12101 + stage.ordinal();
    }
}
