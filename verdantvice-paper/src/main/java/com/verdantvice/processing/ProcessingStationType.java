package com.verdantvice.processing;

import com.verdantvice.security.CustomItemType;
import org.bukkit.Material;

public enum ProcessingStationType {
    DRYING_RACK(CustomItemType.DRYING_RACK, Material.BARREL),
    CURING_CONTAINER(CustomItemType.CURING_CONTAINER, Material.CHISELED_BOOKSHELF),
    GRINDER(CustomItemType.GRINDER, Material.STONECUTTER);

    private final CustomItemType itemType;
    private final Material material;

    ProcessingStationType(CustomItemType itemType, Material material) {
        this.itemType = itemType;
        this.material = material;
    }

    public CustomItemType itemType() {
        return itemType;
    }

    public Material material() {
        return material;
    }
}
