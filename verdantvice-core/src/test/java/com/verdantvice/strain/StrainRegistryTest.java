package com.verdantvice.strain;

import com.verdantvice.test.TestFixtures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StrainRegistryTest {
    @Test
    void registryRejectsDuplicatesButAllowsExplicitReplacement() {
        StrainRegistry registry = new StrainRegistry();
        registry.register(TestFixtures.strain());
        assertThrows(IllegalArgumentException.class, () -> registry.register(TestFixtures.strain()));
        registry.replace(TestFixtures.strain());
        assertEquals(1, registry.size());
        assertEquals("Blueberry", registry.require("BERRY_FOG").displayName());
        assertTrue(registry.find("missing").isEmpty());
    }

    @Test
    void realNameAliasesResolveToHistoricalCanonicalIdentity() {
        StrainRegistry registry = new StrainRegistry();
        registry.register(renamed("lemon_static", "Super Lemon Haze"));
        registry.register(renamed("green_alley", "Skunk #1"));
        registry.register(renamed("ghost_garden", "AK-47"));
        assertEquals("lemon_static", registry.require("super_lemon_haze").id());
        assertEquals("lemon_static", registry.require("SUPER LEMON HAZE").id());
        assertEquals("green_alley", registry.require("skunk_1").id());
        assertEquals("ghost_garden", registry.require("ak-47").id());
        assertEquals(java.util.List.of("ak_47", "skunk_1", "super_lemon_haze"), registry.commandSuggestions());
    }

    @Test
    void replacementRemovesOldCosmeticAliasWithoutChangingCanonicalId() {
        StrainRegistry registry = new StrainRegistry();
        registry.register(TestFixtures.strain());
        registry.replace(renamed("berry_fog", "Custom Blueberry"));
        assertTrue(registry.find("blueberry").isEmpty());
        assertEquals("berry_fog", registry.require("custom_blueberry").id());
        assertEquals("Custom Blueberry", registry.require("berry_fog").displayName());
    }

    @Test
    void ambiguousNamesNeverSelectAnArbitraryPersistentStrain() {
        StrainRegistry registry = new StrainRegistry();
        registry.register(renamed("custom_first", "Duplicate Name"));
        registry.register(renamed("custom_second", "Duplicate Name"));
        assertTrue(registry.find("duplicate_name").isEmpty());
        assertEquals(java.util.List.of("custom_first", "custom_second"), registry.commandSuggestions());
    }

    @Test
    void canonicalIdWinsOverAnotherStrainsCosmeticAlias() {
        StrainRegistry registry = new StrainRegistry();
        registry.register(renamed("blueberry", "Administrator custom variety"));
        registry.register(renamed("berry_fog", "Blueberry"));
        assertEquals("blueberry", registry.require("blueberry").id());
        assertTrue(registry.commandSuggestions().contains("berry_fog"));
    }

    @Test
    void aliasNormalizationIsLocaleIndependentAndHandlesPunctuation() {
        assertEquals("skunk_1", StrainRegistry.commandAlias("Skunk #1"));
        assertEquals("og_kush", StrainRegistry.commandAlias("OG Kush"));
        assertEquals("ak_47", StrainRegistry.commandAlias("AK-47"));
        assertEquals("creme_brulee", StrainRegistry.commandAlias(" Crème Brûlée "));
    }

    private static Strain renamed(String id, String name) {
        Strain original = TestFixtures.strain();
        return new Strain(id, name, original.rarity(), original.growthDuration(), original.difficulty(),
                original.baseYield(), original.qualityPotential(), original.diseaseResistance(),
                original.waterConsumption(), original.lightPreference(), original.stressSensitivity(),
                original.smellIntensity(), original.effectProfile(), original.negativeEffectProfile(),
                original.seedPrice(), original.baseMarketPrice(), original.visualModel(), original.particleProfile());
    }
}
