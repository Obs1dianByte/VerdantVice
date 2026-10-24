package com.verdantvice.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StrainDisplayNameMigrationTest {
    @TempDir Path root;

    @Test
    void exactLegacyNamesAreMigratedWithByteExactBackupAndCustomValuesPreserved() throws Exception {
        String original = """
                # Administrator comments and Unicode remain readable.
                strains:
                  berry_fog:
                    display-name: Berry Fog
                    seed-price: 789
                    effect-profile: { SPEED: 0.17 }
                  lemon_static:
                    display-name: Lemon Static
                    growth-duration-seconds: 12345
                  custom_strain:
                    display-name: 'Custom variety \u0141'
                    custom-setting: true
                """;
        Path file = write(original);
        var result = new StrainDisplayNameMigration().migrate(file, root.resolve("backups"));
        assertEquals(2, result.renamed());
        assertEquals(original, Files.readString(result.backup().orElseThrow(), StandardCharsets.UTF_8));
        YamlConfiguration updated = yaml(file);
        assertEquals("Blueberry", updated.getString("strains.berry_fog.display-name"));
        assertEquals("Super Lemon Haze", updated.getString("strains.lemon_static.display-name"));
        assertEquals(789, updated.getInt("strains.berry_fog.seed-price"));
        assertEquals(0.17, updated.getDouble("strains.berry_fog.effect-profile.SPEED"));
        assertEquals(12345, updated.getLong("strains.lemon_static.growth-duration-seconds"));
        assertEquals("Custom variety \u0141", updated.getString("strains.custom_strain.display-name"));
        assertTrue(updated.getBoolean("strains.custom_strain.custom-setting"));
        assertTrue(Files.readString(file).contains("Administrator comments"));
        try (var files = Files.list(root)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void customLabelsAreNeverOverwrittenIncludingCaseAndWhitespaceDifferences() throws Exception {
        String original = """
                strains:
                  berry_fog: { display-name: 'berry fog', seed-price: 99 }
                  concrete_kush: { display-name: 'Concrete Kush ', base-yield: 7 }
                  green_alley: { display-name: 'Administrator label' }
                """;
        Path file = write(original);
        var result = new StrainDisplayNameMigration().migrate(file, root.resolve("backups"));
        assertEquals(0, result.renamed());
        assertTrue(result.backup().isEmpty());
        assertEquals(original, Files.readString(file));
        assertFalse(Files.exists(root.resolve("backups")));
    }

    @Test
    void restartIsIdempotentAndDoesNotCreateAdditionalBackupsOrRewriteFile() throws Exception {
        Path file = write("strains:\n  green_alley:\n    display-name: Green Alley\n");
        Path backups = root.resolve("backups");
        new StrainDisplayNameMigration().migrate(file, backups);
        String first = Files.readString(file);
        var modified = Files.getLastModifiedTime(file);
        var second = new StrainDisplayNameMigration().migrate(file, backups);
        assertEquals(0, second.renamed());
        assertEquals(first, Files.readString(file));
        assertEquals(modified, Files.getLastModifiedTime(file));
        try (var files = Files.list(backups)) {
            assertEquals(1, files.count());
        }
        assertEquals("Skunk #1", yaml(file).getString("strains.green_alley.display-name"));
    }

    @Test
    void invalidYamlIsNotReplacedOrBackedUpAsAValidConfiguration() throws Exception {
        String invalid = "strains: [unterminated\n";
        Path file = write(invalid);
        assertThrows(IOException.class, () -> new StrainDisplayNameMigration().migrate(file, root.resolve("backups")));
        assertEquals(invalid, Files.readString(file));
        assertFalse(Files.exists(root.resolve("backups")));
    }

    @Test
    void backupFailureDoesNotChangeOriginal() throws Exception {
        String original = "strains:\n  berry_fog:\n    display-name: Berry Fog\n";
        Path file = write(original);
        Path occupied = root.resolve("not-a-directory");
        Files.writeString(occupied, "occupied", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> new StrainDisplayNameMigration().migrate(file, occupied));
        assertEquals(original, Files.readString(file));
    }

    @Test
    void bundledTwelveRealLabelsKeepHistoricalIdsAndSimulationParameters() throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream("strains.yml")) {
            assertTrue(stream != null);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            Map<String, String> names = Map.ofEntries(
                    Map.entry("berry_fog", "Blueberry"), Map.entry("concrete_kush", "Hindu Kush"),
                    Map.entry("lemon_static", "Super Lemon Haze"), Map.entry("green_alley", "Skunk #1"),
                    Map.entry("neon_haze", "Super Silver Haze"), Map.entry("night_widow", "White Widow"),
                    Map.entry("purple_crash", "Purple #1"), Map.entry("velvet_smoke", "Northern Lights"),
                    Map.entry("ghost_garden", "AK-47"), Map.entry("royal_static", "Jack Herer"),
                    Map.entry("black_orchard", "Black Domina"), Map.entry("verdant_vice", "OG Kush"));
            assertEquals(12, yaml.getConfigurationSection("strains").getKeys(false).size());
            names.forEach((id, name) -> {
                assertEquals(name, yaml.getString("strains." + id + ".display-name"));
                assertEquals(id, yaml.getString("strains." + id + ".visual-model"));
            });
            assertEquals(5400, yaml.getLong("strains.berry_fog.growth-duration-seconds"));
            assertEquals(64, yaml.getInt("strains.verdant_vice.seed-price"));
        }
    }

    private Path write(String content) throws IOException {
        Path file = root.resolve("strains.yml");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private YamlConfiguration yaml(Path file) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        return yaml;
    }
}
