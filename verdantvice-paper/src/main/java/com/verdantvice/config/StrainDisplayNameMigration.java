package com.verdantvice.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Only exact former bundled labels are migrated; identifiers and game parameters are untouched. */
public final class StrainDisplayNameMigration {
    private static final List<Rename> RENAMES = List.of(
            new Rename("berry_fog", "Berry Fog", "Blueberry"),
            new Rename("concrete_kush", "Concrete Kush", "Hindu Kush"),
            new Rename("lemon_static", "Lemon Static", "Super Lemon Haze"),
            new Rename("green_alley", "Green Alley", "Skunk #1"),
            new Rename("neon_haze", "Neon Haze", "Super Silver Haze"),
            new Rename("night_widow", "Night Widow", "White Widow"),
            new Rename("purple_crash", "Purple Crash", "Purple #1"),
            new Rename("velvet_smoke", "Velvet Smoke", "Northern Lights"),
            new Rename("ghost_garden", "Ghost Garden", "AK-47"),
            new Rename("royal_static", "Royal Static", "Jack Herer"),
            new Rename("black_orchard", "Black Orchard", "Black Domina"),
            new Rename("verdant_vice", "Verdant Vice", "OG Kush"));

    public Result migrate(Path configurationFile, Path backupDirectory) throws IOException {
        Path target = configurationFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(target)) return new Result(0, Optional.empty());
        String original = Files.readString(target, StandardCharsets.UTF_8);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        try {
            yaml.loadFromString(original);
        } catch (InvalidConfigurationException failure) {
            throw new IOException("Cannot migrate invalid strains.yml; original was not modified", failure);
        }
        int changed = 0;
        for (Rename rename : RENAMES) {
            String path = "strains." + rename.id() + ".display-name";
            if (rename.previous().equals(yaml.get(path))) {
                yaml.set(path, rename.replacement());
                changed++;
            }
        }
        if (changed == 0) return new Result(0, Optional.empty());
        Files.createDirectories(backupDirectory);
        Path backup = backupDirectory.resolve("strains-display-names-" + Instant.now().toEpochMilli()
                + '-' + UUID.randomUUID() + ".yml");
        Files.copy(target, backup);
        Path temporary = Files.createTempFile(target.getParent(), "strains-display-names-", ".tmp");
        try {
            Files.writeString(temporary, yaml.saveToString(), StandardCharsets.UTF_8);
            // Never fall back to truncating the original when atomic replacement is unsupported.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return new Result(changed, Optional.of(backup));
    }

    private record Rename(String id, String previous, String replacement) { }
    public record Result(int renamed, Optional<Path> backup) { }
}
