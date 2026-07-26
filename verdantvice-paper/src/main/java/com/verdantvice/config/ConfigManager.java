package com.verdantvice.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;

/**
 * Owns all YAML files shipped with VerdantVice. Runtime reload deliberately
 * excludes connection settings because replacing a live pool is not safe.
 */
public final class ConfigManager {
    public static final List<String> CONFIG_FILES = List.of(
            "config.yml",
            "database.yml",
            "plants.yml",
            "strains.yml",
            "genetics.yml",
            "quality.yml",
            "effects.yml",
            "villagers.yml",
            "workers.yml",
            "economy.yml",
            "market.yml",
            "contracts.yml",
            "gui.yml",
            "items.yml",
            "integrations.yml",
            "resourcepack.yml",
            "notifications.yml"
    );

    public static final List<String> LANGUAGE_FILES = List.of(
            "pl_PL.yml", "en_US.yml", "de_DE.yml",
            "es_ES.yml", "cs_CZ.yml", "uk_UA.yml"
    );

    private static final Set<String> RESTART_REQUIRED = Set.of("database.yml");

    private final JavaPlugin plugin;
    private final Map<String, YamlConfiguration> configurations = new LinkedHashMap<>();

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public void bootstrap() {
        Path dataFolder = plugin.getDataFolder().toPath();
        try {
            Files.createDirectories(dataFolder);
            Files.createDirectories(dataFolder.resolve("languages"));
            Files.createDirectories(dataFolder.resolve("data"));
            Files.createDirectories(dataFolder.resolve("backups"));
            Files.createDirectories(dataFolder.resolve("logs"));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create VerdantVice data directories", exception);
        }

        CONFIG_FILES.forEach(this::copyIfMissing);
        LANGUAGE_FILES.forEach(name -> copyIfMissing("languages/" + name));
        loadAll();
    }

    public synchronized ReloadReport reloadSafe() {
        List<String> reloaded = new ArrayList<>();
        List<String> restartRequired = new ArrayList<>();
        Map<String, YamlConfiguration> candidate = new LinkedHashMap<>(configurations);
        for (String name : CONFIG_FILES) {
            if (RESTART_REQUIRED.contains(name)) {
                restartRequired.add(name);
                continue;
            }
            candidate.put(name, load(name));
            reloaded.add(name);
        }
        validate(candidate);
        configurations.clear();
        configurations.putAll(candidate);
        return new ReloadReport(List.copyOf(reloaded), List.copyOf(restartRequired));
    }

    public synchronized void loadAll() {
        configurations.clear();
        for (String name : CONFIG_FILES) {
            configurations.put(name, load(name));
        }
        validate(configurations);
    }

    public synchronized YamlConfiguration get(String fileName) {
        YamlConfiguration configuration = configurations.get(fileName);
        if (configuration == null) {
            throw new IllegalArgumentException("Unknown configuration file: " + fileName);
        }
        return configuration;
    }

    public YamlConfiguration main() {
        return get("config.yml");
    }

    public Path dataPath(String relative) {
        Path root = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Path escapes plugin data directory: " + relative);
        }
        return resolved;
    }

    public Map<String, YamlConfiguration> snapshot() {
        synchronized (this) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(configurations));
        }
    }

    private YamlConfiguration load(String name) {
        File file = dataPath(name).toFile();
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        for (String key : configuration.getKeys(true)) {
            Object value = configuration.get(key);
            if (value instanceof String string && string.indexOf('\0') >= 0) {
                throw new IllegalStateException(name + " contains a NUL character at " + key);
            }
        }
        return configuration;
    }

    private void copyIfMissing(String resourceName) {
        Path target = dataPath(resourceName);
        if (Files.exists(target)) {
            return;
        }
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            plugin.saveResource(resourceName, false);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Bundled resource is missing: " + resourceName, exception);
        }
    }

    private void validate(Map<String, YamlConfiguration> values) {
        YamlConfiguration main = require(values, "config.yml");
        int plantsPerCycle = main.getInt("performance.plants-per-cycle", 100);
        int maxPlantsPerChunk = main.getInt("performance.max-plants-per-chunk", 32);
        long saveSeconds = main.getLong("performance.database-save-seconds", 60);
        if (plantsPerCycle < 1 || plantsPerCycle > 10_000) {
            fail("performance.plants-per-cycle must be between 1 and 10000");
        }
        if (maxPlantsPerChunk < 1 || maxPlantsPerChunk > 512) {
            fail("performance.max-plants-per-chunk must be between 1 and 512");
        }
        if (saveSeconds < 5 || saveSeconds > 86_400) {
            fail("performance.database-save-seconds must be between 5 and 86400");
        }

        String locale = main.getString("default-language", "pl_PL");
        String normalized = locale == null ? "" : locale.replace('-', '_');
        boolean supported = LANGUAGE_FILES.stream()
                .map(name -> name.substring(0, name.length() - 4))
                .anyMatch(normalized::equalsIgnoreCase);
        if (!supported) {
            fail("default-language must be one of " + LANGUAGE_FILES);
        }

        String databaseType = require(values, "database.yml").getString("type", "SQLITE");
        try {
            DatabaseType.valueOf(Objects.requireNonNull(databaseType).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            fail("database.yml type must be SQLITE, MYSQL, or MARIADB");
        }
    }

    private YamlConfiguration require(
            Map<String, YamlConfiguration> values,
            String file
    ) {
        YamlConfiguration value = values.get(file);
        if (value == null) {
            throw new IllegalStateException("Missing loaded configuration: " + file);
        }
        return value;
    }

    private void fail(String message) {
        plugin.getLogger().log(Level.SEVERE, "Invalid configuration: {0}", message);
        throw new IllegalStateException(message);
    }

    public enum DatabaseType {
        SQLITE,
        MYSQL,
        MARIADB
    }

    public record ReloadReport(List<String> reloaded, List<String> restartRequired) {
        public ReloadReport {
            reloaded = List.copyOf(reloaded);
            restartRequired = List.copyOf(restartRequired);
        }
    }
}
