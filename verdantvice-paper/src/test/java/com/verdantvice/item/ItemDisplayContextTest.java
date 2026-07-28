package com.verdantvice.item;

import com.verdantvice.processing.ProductBatch;
import com.verdantvice.security.CustomItemType;
import com.verdantvice.strain.StrainRegistry;
import com.verdantvice.test.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemDisplayContextTest {
    private final StrainRegistry strains = registry();

    @Test
    void freshBatchUsesRealFreshWeight() {
        ItemDisplayContext context = ItemDisplayContext.batch(TestFixtures.freshBatch(), strains);
        Map<String, ?> values = context.placeholders(CustomItemType.FRESH_BATCH);
        assertEquals("40.00", values.get("fresh_weight"));
        assertEquals("40.00", values.get("weight"));
    }

    @Test
    void driedBatchUsesRealDryWeight() {
        ProductBatch batch = TestFixtures.driedBatch();
        ItemDisplayContext context = ItemDisplayContext.batch(batch, strains);
        Map<String, ?> values = context.placeholders(CustomItemType.DRY_BATCH);
        assertEquals(String.format(java.util.Locale.ROOT, "%.2f", batch.dryWeight()),
                values.get("dry_weight"));
        assertEquals(values.get("dry_weight"), values.get("weight"));
    }

    @Test
    void qualityComesFromProductBatch() {
        ItemDisplayContext context = ItemDisplayContext.batch(TestFixtures.freshBatch(), strains);
        assertEquals("80.00", context.placeholders(CustomItemType.FRESH_BATCH).get("quality"));
    }

    @Test
    void strainNameIsResolvedFromRegistry() {
        ItemDisplayContext context = ItemDisplayContext.batch(TestFixtures.freshBatch(), strains);
        assertEquals("berry_fog", context.strainId());
        assertEquals("Berry Fog", context.strainName());
    }

    @Test
    void everyRequiredPlaceholderIsAlwaysPresent() {
        Map<String, ?> values = ItemDisplayContext.batch(TestFixtures.curedBatch(), strains)
                .placeholders(CustomItemType.CURED_BATCH);
        for (String key : List.of(
                "strain", "strain_id", "strain_name", "weight", "fresh_weight",
                "dry_weight", "quality", "quality_grade", "state", "batch_id")) {
            assertTrue(values.containsKey(key), key);
            assertFalse(String.valueOf(values.get(key)).contains("<"), key);
        }
    }

    @Test
    void allProductItemTypesReceiveARealWeight() {
        ProductBatch batch = TestFixtures.driedBatch();
        ItemDisplayContext context = ItemDisplayContext.batch(batch, strains);
        for (CustomItemType type : List.of(
                CustomItemType.DRY_BATCH, CustomItemType.CURED_BATCH,
                CustomItemType.GROUND_PRODUCT, CustomItemType.MIXTURE,
                CustomItemType.PACKAGE, CustomItemType.JOINT)) {
            assertFalse("0.00".equals(context.placeholders(type).get("weight")), type.name());
        }
    }

    @Test
    void displayContextDoesNotChangeSignedSchemaVersion() {
        assertEquals(1, new com.verdantvice.security.SignedItemData(
                CustomItemType.FRESH_BATCH, "fresh-batch", java.util.UUID.randomUUID(),
                TestFixtures.freshBatch().batchId(), TestFixtures.OWNER, 80.0,
                "berry_fog", TestFixtures.NOW, 1).schemaVersion());
    }

    private static StrainRegistry registry() {
        StrainRegistry registry = new StrainRegistry();
        registry.register(TestFixtures.strain());
        return registry;
    }
}
