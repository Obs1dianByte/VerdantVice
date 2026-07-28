package com.verdantvice.item;

import com.verdantvice.config.ConfigManager;
import com.verdantvice.i18n.TranslationService;
import com.verdantvice.processing.ProductBatch;
import com.verdantvice.security.CustomItemType;
import com.verdantvice.security.ItemSigner;
import com.verdantvice.security.SignedItemData;
import com.verdantvice.strain.StrainRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class CustomItemFactory {
    private static final Pattern UNRESOLVED = Pattern.compile("<[a-zA-Z0-9_-]+>");

    private final ConfigManager configs;
    private final TranslationService translations;
    private final ItemSigner signer;
    private final StrainRegistry strains;

    public CustomItemFactory(
            ConfigManager configs,
            TranslationService translations,
            ItemSigner signer,
            StrainRegistry strains
    ) {
        this.configs = Objects.requireNonNull(configs, "configs");
        this.translations = Objects.requireNonNull(translations, "translations");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.strains = Objects.requireNonNull(strains, "strains");
    }

    public PreparedItem create(
            CommandSender viewer,
            CustomItemType type,
            UUID owner,
            String strain
    ) {
        if (type.batchBound()) {
            throw new IllegalArgumentException("missing_batch_context");
        }
        return create(viewer, type, owner, null,
                ItemDisplayContext.simple(strain, 0.0, strains));
    }

    public PreparedItem create(
            CommandSender viewer,
            CustomItemType type,
            UUID owner,
            UUID batchId,
            String strain,
            double quality
    ) {
        if (type.batchBound()) {
            throw new IllegalArgumentException("missing_batch_context");
        }
        return create(viewer, type, owner, batchId,
                ItemDisplayContext.simple(strain, quality, strains));
    }

    public PreparedItem create(
            CommandSender viewer,
            CustomItemType type,
            UUID owner,
            ProductBatch batch
    ) {
        Objects.requireNonNull(batch, "batch");
        return create(viewer, type, owner, batch.batchId(),
                ItemDisplayContext.batch(batch, strains));
    }

    private PreparedItem create(
            CommandSender viewer,
            CustomItemType type,
            UUID owner,
            UUID batchId,
            ItemDisplayContext context
    ) {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(context, "context");
        String configKey = configKey(type);
        YamlConfiguration items = configs.get("items.yml");
        ConfigurationSection section = items.getConfigurationSection(configKey);
        Material material = material(section, type);
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        String translationBase = "item." + configKey.replace('-', '_');
        Map<String, Object> placeholders = new java.util.LinkedHashMap<>(context.placeholders(type));
        if (!context.state().isBlank()) {
            placeholders.put("state", translations.plain(
                    viewer, "item.states." + context.state().toLowerCase(java.util.Locale.ROOT)));
        }
        if (!context.qualityGrade().isBlank()) {
            placeholders.put("quality_grade", translations.plain(
                    viewer, "item.quality_grades."
                            + context.qualityGrade().toLowerCase(java.util.Locale.ROOT)
                            .replace('_', '-')));
        }
        placeholders.put("duration", PlainTextComponentSerializer.plainText().serialize(
                translations.component(viewer, "common.minutes",
                        Map.of("minutes", Math.max(0L, context.duration().toMinutes())))));
        Component displayName = translations.component(
                viewer, translationBase + ".name", placeholders);
        Component description = translations.component(
                viewer, translationBase + ".description", placeholders);
        rejectUnresolved(displayName, translationBase + ".name");
        rejectUnresolved(description, translationBase + ".description");
        meta.displayName(displayName);
        List<Component> lore = new ArrayList<>();
        lore.add(description);
        if (!context.strainId().isBlank()) {
            lore.add(translations.component(viewer, "item.lore.strain", placeholders));
        }
        if (context.quality() > 0.0) {
            lore.add(translations.component(viewer, "item.lore.quality", placeholders));
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
                context.quality(),
                context.strainId(),
                Instant.now(),
                1
        );
        signer.sign(stack, data);
        if (section != null && section.contains("durability")) {
            signer.setUsesRemaining(stack, Math.max(1, section.getInt("durability")));
        }
        return new PreparedItem(stack, data, signer.payloadHash(data));
    }

    private static void rejectUnresolved(Component rendered, String translationKey) {
        String plain = PlainTextComponentSerializer.plainText().serialize(rendered);
        java.util.regex.Matcher matcher = UNRESOLVED.matcher(plain);
        if (matcher.find()) {
            throw new IllegalStateException(
                    "Unresolved item placeholder " + matcher.group() + " in " + translationKey);
        }
    }

    public boolean damageTool(ItemStack item, int amount) {
        if (amount < 1) throw new IllegalArgumentException("amount must be positive");
        int remaining = signer.usesRemaining(item);
        if (remaining <= 0) return false;
        signer.setUsesRemaining(item, Math.max(0, remaining - amount));
        return remaining - amount > 0;
    }

    private Material material(ConfigurationSection section, CustomItemType type) {
        String configured = section == null ? null : section.getString("material");
        Material material = configured == null ? null : Material.matchMaterial(configured);
        if (material != null && material.isItem()) return material;
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
            case PLANTATION_CONTRACT, GUIDE_BOOK, ADVANCED_BOOK -> Material.WRITTEN_BOOK;
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
