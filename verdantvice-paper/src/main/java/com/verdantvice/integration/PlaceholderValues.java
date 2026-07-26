package com.verdantvice.integration;

import org.bukkit.OfflinePlayer;

public interface PlaceholderValues {
    String farmName(OfflinePlayer player);

    int farmLevel(OfflinePlayer player);

    int plants(OfflinePlayer player);

    int readyPlants(OfflinePlayer player);

    int workers(OfflinePlayer player);

    int reputation(OfflinePlayer player);

    double tolerance(OfflinePlayer player);

    double marketPrice(String strainId);
}
