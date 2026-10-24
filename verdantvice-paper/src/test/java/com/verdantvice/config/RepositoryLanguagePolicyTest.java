package com.verdantvice.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps authored project files English while retaining real Minecraft translations.
 * Unicode test inputs use escapes; player-entered Unicode is still supported.
 * This is a regression guard, not a general-purpose natural-language detector.
 */
final class RepositoryLanguagePolicyTest {
    private static final Pattern POLISH_LETTERS = Pattern.compile(
            "[\u0105\u0107\u0119\u0142\u0144\u00f3\u015b\u017a\u017c"
                    + "\u0104\u0106\u0118\u0141\u0143\u00d3\u015a\u0179\u017b]");
    private static final Pattern TRANSLATION_RESOURCE = Pattern.compile(
            "(?:verdantvice-paper/src/main/resources/languages/"
                    + "(?:pl_PL|de_DE|es_ES|cs_CZ|uk_UA)\\.yml"
                    + "|resource-pack/(?:source/lang|(?:1\\.21\\.4|26\\.2)/assets/verdantvice/lang)/"
                    + "(?:pl_pl|de_de|es_es|cs_cz|uk_ua)\\.json)");
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            ".git", ".gradle", ".idea", ".agents", "build", "run", "server",
            "node_modules", "__pycache__");
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "java", "kts", "yml", "yaml", "json", "properties", "ps1", "py",
            "md", "txt", "toml", "bat", "sh", "sql", "mcmeta");

    @Test
    void authoredFilesHaveNoPolishOutsideMinecraftTranslations() throws Exception {
        Path project = projectRoot();
        List<String> violations = new ArrayList<>();
        List<Path> checked = new ArrayList<>();
        Files.walkFileTree(project, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                return !directory.equals(project)
                        && EXCLUDED_DIRECTORIES.contains(directory.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                String relative = relative(project, file);
                if (!isAuthoredText(file) || isTranslation(relative)) {
                    return FileVisitResult.CONTINUE;
                }
                checked.add(file);
                if (containsPolish(relative)) {
                    violations.add(relative + ": file names must be English");
                }
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int index = 0; index < lines.size(); index++) {
                    if (containsPolish(lines.get(index))) {
                        violations.add(relative + ':' + (index + 1));
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        assertTrue(checked.size() > 300, "The audit must inspect the actual repository");
        assertTrue(violations.isEmpty(), "Polish belongs only in Minecraft translation resources: "
                + String.join(", ", violations));
    }

    @Test
    void englishIsTheBundledDefaultAndPolishTranslationsArePreserved() throws Exception {
        Path project = projectRoot();
        Path resources = project.resolve("verdantvice-paper/src/main/resources");
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.loadFromString(Files.readString(resources.resolve("config.yml"), StandardCharsets.UTF_8));
        assertEquals("en_US", configuration.getString("default-language"));
        assertTrue(configuration.getBoolean("auto-detect-client-language"));
        assertTrue(containsPolish(Files.readString(
                resources.resolve("languages/pl_PL.yml"), StandardCharsets.UTF_8)));
        for (String version : List.of("source/lang", "1.21.4/assets/verdantvice/lang",
                "26.2/assets/verdantvice/lang")) {
            assertTrue(containsPolish(Files.readString(
                    project.resolve("resource-pack").resolve(version).resolve("pl_pl.json"),
                    StandardCharsets.UTF_8)), "Polish subtitle resources must remain available");
        }
    }

    @Test
    void onlyRealNonEnglishTranslationPathsAreExempt() {
        assertTrue(containsPolish("\u0141\u00f3\u0105\u0107\u0119\u0144\u015b\u017a\u017c"));
        assertFalse(containsPolish("English comments and en_US defaults"));
        assertTrue(isTranslation("verdantvice-paper/src/main/resources/languages/pl_PL.yml"));
        assertTrue(isTranslation("resource-pack/source/lang/pl_pl.json"));
        assertTrue(isTranslation("resource-pack/26.2/assets/verdantvice/lang/pl_pl.json"));
        assertFalse(isTranslation("verdantvice-paper/src/main/resources/languages/en_US.yml"));
        assertFalse(isTranslation("scripts/pl_PL.yml"));
        assertFalse(isTranslation("resource-pack/source/pl_pl.json"));
        assertFalse(isTranslation("README.md"));
    }

    private static boolean containsPolish(String text) {
        return POLISH_LETTERS.matcher(text).find();
    }

    private static boolean isTranslation(String relative) {
        return TRANSLATION_RESOURCE.matcher(relative).matches();
    }

    private static boolean isAuthoredText(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return TEXT_EXTENSIONS.contains(dot >= 0 ? name.substring(dot + 1) : "")
                || name.equals("gradlew") || name.equals(".gitignore");
    }

    private static Path projectRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("Tests must run inside the repository");
        }
        return candidate;
    }

    private static String relative(Path project, Path file) {
        return project.relativize(file).toString().replace('\\', '/');
    }
}
