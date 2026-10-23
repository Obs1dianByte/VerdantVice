package com.verdantvice.i18n;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class TranslationKeyUsageTest {
    private static final Pattern LITERAL = Pattern.compile(
            "\\\"((?:errors|success|commands|processing|rolling|herbalist|farm|worker|book|gui)"
                    + "\\.[a-z0-9_.-]+)\\\"");

    @Test
    void literalTranslationKeysUsedByProductionCodeExistInEnglish() throws Exception {
        YamlConfiguration english = new YamlConfiguration();
        try (Reader reader = Files.newBufferedReader(
                Path.of("src/main/resources/languages/en_US.yml"),
                StandardCharsets.UTF_8)) {
            english.load(reader);
        }
        List<String> missing = new ArrayList<>();
        try (var paths = Files.walk(Path.of("src/main/java"))) {
            for (Path path : paths.filter(value -> value.toString().endsWith(".java")).toList()) {
                Matcher matcher = LITERAL.matcher(Files.readString(path, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    String key = matcher.group(1);
                    if (!english.isString(key) && isLikelyTranslationKey(key)) {
                        missing.add(path + ": " + key);
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), () -> "Missing literal translations: " + missing);
    }

    private static boolean isLikelyTranslationKey(String key) {
        if (key.endsWith(".") || key.endsWith(".yml")) {
            return false;
        }
        return !key.startsWith("processing.drying.")
                && !key.startsWith("processing.curing.")
                && !key.startsWith("processing.grinding.")
                && !key.startsWith("processing.mixing.")
                && !Set.of(
                        "processing.update-seconds",
                        "processing.offline",
                        "herbalist.daily-buy-limit",
                        "herbalist.conversion-chance",
                        "herbalist.village-radius",
                        "herbalist.max-per-village",
                        "herbalist.starting-reputation"
                ).contains(key);
    }
}
