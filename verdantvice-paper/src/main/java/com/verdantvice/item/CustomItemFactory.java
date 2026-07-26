package com.verdantvice.item;

import com.verdantvice.config.ConfigManager;
import com.verdantvice.i18n.TranslationService;
import com.verdantvice.security.CustomItemType;
import com.verdantvice.security.ItemSigner;
import com.verdantvice.security.SignedItemData;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class CustomItemFactory {
    private final ConfigManager configs;
    private final TranslationService translations;
    private final ItemSigner signer;

    public CustomItemFactory(
            ConfigManager configs,
            TranslationService translations,
            ItemSigner signer
    ) {
        this.configs = Objects.requireNonNull(configs, "configs");
        this.translations = Objects.requireNonNull(translations, "translations");
        this.signer = Objects.requireNonNull(signer, "signer");
    }

    public PreparedItem create(
            CommandSender viewer,
            CustomItemType type,
            UUID owner,
            String strain
    ) {
        return create(viewer, type, owner, null, strain, 0.0);
    }

    public PreparedItem create(
            CommandSender viewer,
            CustomItemType type,
            UUID owner,
            UUID batchId,
            String strain,
            double quality
    ) {
        String configKey = configKey(type);
        YamlConfiguration items = configs.get("items.yml");
        ConfigurationSection section = items.getConfigurationSection(configKey);
        Material material = material(section, type);
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        String translationBase = "item." + configKey.replace('-', '_');
        meta.displayName(translations.component(viewer, translationBase + ".name"));
        List<Component> lore = new ArrayList<>();
        lore.add(translations.component(viewer, translationBase + ".description"));
        if (strain != null && !strain.isBlank()) {
            lore.add(translations.component(
                    viewer,
                    "item.lore.strain",
                    Map.of("strain", strain)
            ));
        }
        if (quality > 0.0) {
            lore.add(translations.component(
                    viewer,
                    "item.lore.quality",
                    Map.of("quality", String.format(Locale.ROOT, "%.1f", quality))
            ));
        }
        meta.lore(lore);
        if (section != null && section.contains("custom-model-data")) {
            meta.setCustomModelData(section.getInt("custom-model-data"));
        }
        stack.setItemMeta(meta);

        SignedItemData data = new SignedItemData(
                type,
                configKey,
                UUID.randomUUID(),
                batchId,
                owner,
                quality,
                strain == null ? "" : strain,
                Instant.now(),
                1
        );
        signer.sign(stack, data);
        if (section != null && section.contains("durability")) {
            signer.setUsesRemaining(stack, Math.max(1, section.getInt("durability")));
        }
        return new PreparedItem(stack, data, signer.payloadHash(data));
    }

    public boolean damageTool(ItemStack item, int amount) {
        if (amount < 1) {
            throw new IllegalArgumentException("amount must be positive");
        }
        int remaining = signer.usesRemaining(item);
        if (remaining <= 0) {
            return false;
        }
        signer.setUsesRemaining(item, Math.max(0, remaining - amount));
        return remaining - amount > 0;
    }

    private Material material(ConfigurationSection section, CustomItemType type) {
        String configured = section == null ? null : section.getString("material");
        Material material = configured == null
                ? null
                : Material.matchMaterial(configured);
        if (material != null && material.isItem()) {
            return material;
        }
        return switch (type) {
            case SEED -> Material.WHEAT_SEEDS;
            case PLANTING_POT -> Material.DECORATED_POT;
            case WATERING_CAN -> Material.GLASS_BOTTLE;
            case PLANT_SCANNER -> Material.CLOCK;
            case CLEANING_KIT -> Material.BRUSH;
            case TREATMENT_SPRAY -> Material.HONEY_BOTTLE;
            case NUTRIENT_PACK -> Material.BONE_MEAL;
            case REPAIR_KIT -> Material.IRON_NUGGET;
            case DRYING_RACK -> Material.BARREL;
            case CURING_CONTAINER -> Material.CHISELED_BOOKSHELF;
            case GRINDER -> Material.STONECUTTER;
            case FARM_MANAGER_TABLE -> Material.LECTERN;
            case SELECTION_WAND -> Material.BLAZE_ROD;
            case ROLLING_PAPER -> Material.PAPER;
            case FILTER, JOINT -> Material.STICK;
            case FRESH_BATCH -> Material.KELP;
            case DRY_BATCH, CURED_BATCH, GROUND_PRODUCT, MIXTURE -> Material.DRIED_KELP;
            case PACKAGE -> Material.BUNDLE;
            case PLANTATION_CONTRACT -> Material.WRITTEN_BOOK;
            case GUIDE_BOOK, ADVANCED_BOOK -> Material.WRITTEN_BOOK;
        };
    }

    private String configKey(CustomItemType type) {
        return switch (type) {
            case SEED -> "seed";
            case PLANTING_POT -> "planting-pot";
            case WATERING_CAN -> "watering-can";
            case PLANT_SCANNER -> "plant-scanner";
            case CLEANING_KIT -> "cleaning-kit";
            case TREATMENT_SPRAY -> "treatment-spray";
            case NUTRIENT_PACK -> "nutrient-pack";
            case REPAIR_KIT -> "repair-kit";
            case DRYING_RACK -> "drying-rack";
            case CURING_CONTAINER -> "curing-container";
            case GRINDER -> "grinder";
            case FARM_MANAGER_TABLE -> "farm-manager-table";
            case SELECTION_WAND -> "selection-wand";
            case ROLLING_PAPER -> "rolling-paper";
            case FILTER -> "filter";
            case PACKAGE -> "package";
            case JOINT -> "joint";
            case FRESH_BATCH -> "fresh-batch";
            case DRY_BATCH -> "dry-batch";
            case CURED_BATCH -> "cured-batch";
            case GROUND_PRODUCT -> "ground-product";
            case MIXTURE -> "mixture";
            case PLANTATION_CONTRACT -> "plantation-contract";
            case GUIDE_BOOK -> "guide-book";
            case ADVANCED_BOOK -> "advanced-book";
        };
    }

    public record PreparedItem(ItemStack stack, SignedItemData data, String payloadHash) {
        public PreparedItem {
            stack = stack.clone();
            Objects.requireNonNull(data, "data");
            Objects.requireNonNull(payloadHash, "payloadHash");
        }

        @Override
        public ItemStack stack() {
            return stack.clone();
        }
    }
}
