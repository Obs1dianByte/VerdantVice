package com.verdantvice.integration;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;

public final class VerdantPlaceholderExpansion extends PlaceholderExpansion {
    private final JavaPlugin plugin;
    private final PlaceholderValues values;

    public VerdantPlaceholderExpansion(JavaPlugin plugin, PlaceholderValues values) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.values = Objects.requireNonNull(values, "values");
    }

    @Override
    public @NotNull String getIdentifier() {
        return "verdantvice";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getPluginMeta().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String parameters) {
        if (player == null) {
            return "";
        }
        String normalized = parameters.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "farm_name" -> values.farmName(player);
            case "farm_level" -> Integer.toString(values.farmLevel(player));
            case "plants" -> Integer.toString(values.plants(player));
            case "ready_plants" -> Integer.toString(values.readyPlants(player));
            case "workers" -> Integer.toString(values.workers(player));
            case "reputation" -> Integer.toString(values.reputation(player));
            case "tolerance" -> String.format(Locale.ROOT, "%.1f", values.tolerance(player));
            default -> normalized.startsWith("market_")
                    ? String.format(
                            Locale.ROOT,
                            "%.2f",
                            values.marketPrice(normalized.substring("market_".length()))
                    )
                    : null;
        };
    }
}
