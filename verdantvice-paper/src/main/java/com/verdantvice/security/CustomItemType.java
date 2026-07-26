package com.verdantvice.security;

public enum CustomItemType {
    SEED(true),
    PLANTING_POT(false),
    WATERING_CAN(false),
    PLANT_SCANNER(false),
    CLEANING_KIT(false),
    TREATMENT_SPRAY(false),
    NUTRIENT_PACK(false),
    REPAIR_KIT(false),
    DRYING_RACK(false),
    CURING_CONTAINER(false),
    GRINDER(false),
    FARM_MANAGER_TABLE(false),
    SELECTION_WAND(false),
    ROLLING_PAPER(false),
    FILTER(false),
    FRESH_BATCH(true),
    DRY_BATCH(true),
    CURED_BATCH(true),
    GROUND_PRODUCT(true),
    MIXTURE(true),
    PACKAGE(true),
    JOINT(true),
    PLANTATION_CONTRACT(true),
    GUIDE_BOOK(false),
    ADVANCED_BOOK(false);

    private final boolean consumableState;

    CustomItemType(boolean consumableState) {
        this.consumableState = consumableState;
    }

    public boolean consumableState() {
        return consumableState;
    }
}
