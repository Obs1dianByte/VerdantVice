package com.verdantvice.diagnostic;

import com.verdantvice.config.ConfigManager;
import com.verdantvice.database.DatabaseDiagnostics;
import com.verdantvice.database.DatabaseManager;
import com.verdantvice.farm.FarmManager;
import com.verdantvice.plant.PlantManager;
import com.verdantvice.processing.ProcessingManager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Produces a redacted support snapshot. Database credentials and the signing
 * secret are deliberately never read by this service.
 */
public final class DiagnosticService {
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final JavaPlugin plugin;
    private final ConfigManager configs;
    private final DatabaseManager database;
    private final FarmManager farms;
    private final PlantManager plants;
    private final ProcessingManager processing;

    public DiagnosticService(
            JavaPlugin plugin,
            ConfigManager configs,
            DatabaseManager database,
            FarmManager farms,
            PlantManager plants,
            ProcessingManager processing
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.database = Objects.requireNonNull(database, "database");
        this.farms = Objects.requireNonNull(farms, "farms");
        this.plants = Objects.requireNonNull(plants, "plants");
        this.processing = Objects.requireNonNull(processing, "processing");
    }

    public CompletableFuture<Path> create() {
        Instant now = Instant.now();
        DatabaseDiagnostics db = database.diagnostics();
        int farmCount = farms.all().size();
        int plantCount = plants.all().size();
        int online = Bukkit.getOnlinePlayers().size();
        String pluginName = plugin.getName();
        String pluginVersion = plugin.getPluginMeta().getVersion();
        String serverName = Bukkit.getName();
        String serverVersion = Bukkit.getVersion();
        boolean debug = configs.main().getBoolean("debug", false);
        String defaultLanguage = configs.main().getString("default-language", "en_US");
        java.util.Map<String, Object> modules =
                configs.main().getConfigurationSection("modules") == null
                        ? java.util.Map.of()
                        : java.util.Map.copyOf(
                                configs.main().getConfigurationSection("modules").getValues(false)
                        );
        return CompletableFuture.supplyAsync(() -> {
            YamlConfiguration report = new YamlConfiguration();
            report.set("generated-at", now.toString());
            report.set("plugin.name", pluginName);
            report.set("plugin.version", pluginVersion);
            report.set("server.name", serverName);
            report.set("server.version", serverVersion);
            report.set("java.version", System.getProperty("java.version"));
            report.set("database.type", db.type().name());
            report.set("database.state", db.lifecycleState());
            report.set("database.healthy", db.acceptingWork());
            report.set("database.active-operations", db.activeExecutorThreads());
            report.set("database.queued-operations", db.queuedOperations());
            report.set("database.completed-operations", db.completedOperations());
            report.set("counts.farms", farmCount);
            report.set("counts.plants", plantCount);
            report.set("counts.online-players", online);
            report.set("configuration.debug", debug);
            report.set("configuration.default-language", defaultLanguage);
            report.set("configuration.modules", modules);
            Path destination = configs.dataPath(
                    "logs/diagnostic-" + FILE_TIME.format(now) + ".yml"
            );
            try {
                report.save(destination.toFile());
                return destination;
            } catch (IOException exception) {
                throw new IllegalStateException("Could not write diagnostic report", exception);
            }
        });
    }
}
