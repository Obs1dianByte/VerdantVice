package com.verdantvice.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeItemTradeResourcesTest {
    private static final List<String> LOCALES = List.of(
            "pl_PL", "en_US", "de_DE", "es_ES", "cs_CZ", "uk_UA");
    private static final Set<String> DISPLAY_PLACEHOLDERS = Set.of(
            "strain", "strain_id", "strain_name", "weight", "fresh_weight",
            "dry_weight", "quality", "quality_grade", "state", "batch_id",
            "components", "duration");
    private static final Pattern TOKEN = Pattern.compile("<([a-zA-Z0-9_-]+)>");
    private static final Set<String> FORMAT_TAGS = Set.of(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red",
            "dark_purple", "gold", "gray", "dark_gray", "blue", "green",
            "aqua", "red", "light_purple", "yellow", "white", "bold",
            "italic", "underlined", "strikethrough", "obfuscated", "reset",
            "newline", "br");

    @Test
    void defaultAcceptedSaleTypesExcludeFreshBatch() throws Exception {
        YamlConfiguration market = load("market.yml");
        assertEquals(List.of("DRY_BATCH", "CURED_BATCH", "PACKAGE"),
                market.getStringList("trade.accepted-item-types"));
        assertFalse(market.getStringList("trade.accepted-item-types").contains("FRESH_BATCH"));
    }

    @Test
    void everyLanguageContainsTypedTradeFailures() throws Exception {
        for (String locale : LOCALES) {
            YamlConfiguration language = load("languages/" + locale + ".yml");
            for (String key : List.of(
                    "trade.authentic-only", "trade.fresh-batch-requires-drying",
                    "trade.unsupported-batch-state", "trade.unregistered-item",
                    "trade.already-used", "trade.item-reserved", "trade.missing-batch",
                    "trade.batch-state-mismatch", "errors.wrong-owner")) {
                assertFalse(language.getString(key, "").isBlank(), locale + ": " + key);
            }
        }
    }

    @Test
    void everyProductTemplateUsesOnlySupportedDynamicPlaceholders() throws Exception {
        for (String locale : LOCALES) {
            YamlConfiguration language = load("languages/" + locale + ".yml");
            for (String item : List.of(
                    "fresh_batch", "dry_batch", "cured_batch", "ground_product",
                    "mixture", "package", "joint")) {
                for (String field : List.of("name", "description")) {
                    String template = language.getString("item." + item + "." + field, "");
                    Matcher matcher = TOKEN.matcher(template);
                    while (matcher.find()) {
                        String token = matcher.group(1);
                        assertTrue(DISPLAY_PLACEHOLDERS.contains(token) || FORMAT_TAGS.contains(token),
                                locale + " item." + item + "." + field + " has " + token);
                    }
                }
            }
        }
    }

    @Test
    void globalHerbalistTitlesAreLocalizedInAllLanguages() throws Exception {
        java.util.HashSet<String> titles = new java.util.HashSet<>();
        for (String locale : LOCALES) {
            String title = load("languages/" + locale + ".yml")
                    .getString("herbalist.title", "");
            assertFalse(title.isBlank(), locale);
            titles.add(title);
        }
        assertEquals(LOCALES.size(), titles.size());
    }

    private YamlConfiguration load(String resource) throws Exception {
        var stream = getClass().getClassLoader().getResourceAsStream(resource);
        assertNotNull(stream, resource);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(reader);
            return yaml;
        }
    }
}
