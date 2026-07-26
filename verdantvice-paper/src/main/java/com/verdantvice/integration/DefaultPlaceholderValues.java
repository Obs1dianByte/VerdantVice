package com.verdantvice.integration;

import com.verdantvice.farm.Farm;
import com.verdantvice.farm.FarmManager;
import com.verdantvice.plant.GrowthStage;
import com.verdantvice.plant.PlantManager;
import com.verdantvice.profile.PlayerProfileManager;
import com.verdantvice.market.PaperMarketService;
import com.verdantvice.strain.StrainRegistry;
import com.verdantvice.worker.WorkerManager;
import org.bukkit.OfflinePlayer;

import java.util.Objects;

public final class DefaultPlaceholderValues implements PlaceholderValues {
    private final FarmManager farms;
    private final PlantManager plants;
    private final PlayerProfileManager profiles;
    private final StrainRegistry strains;
    private final WorkerManager workers;
    private final PaperMarketService markets;

    public DefaultPlaceholderValues(
            FarmManager farms,
            PlantManager plants,
            PlayerProfileManager profiles,
            StrainRegistry strains,
            WorkerManager workers,
            PaperMarketService markets
    ) {
        this.farms = Objects.requireNonNull(farms, "farms");
        this.plants = Objects.requireNonNull(plants, "plants");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.strains = Objects.requireNonNull(strains, "strains");
        this.workers = Objects.requireNonNull(workers, "workers");
        this.markets = Objects.requireNonNull(markets, "markets");
    }

    @Override
    public String farmName(OfflinePlayer player) {
        return farms.owned(player.getUniqueId()).map(Farm::name).orElse("");
    }

    @Override
    public int farmLevel(OfflinePlayer player) {
        return farms.owned(player.getUniqueId()).map(Farm::level).orElse(0);
    }

    @Override
    public int plants(OfflinePlayer player) {
        return (int) this.plants.all().stream()
                .filter(plant -> plant.ownerUuid().equals(player.getUniqueId()))
                .count();
    }

    @Override
    public int readyPlants(OfflinePlayer player) {
        return (int) plants.all().stream()
                .filter(plant -> plant.ownerUuid().equals(player.getUniqueId()))
                .filter(plant -> plant.growthStage() == GrowthStage.READY)
                .count();
    }

    @Override
    public int workers(OfflinePlayer player) {
        return farms.owned(player.getUniqueId())
                .map(farm -> workers.workers(farm.farmId()).size())
                .orElse(0);
    }

    @Override
    public int reputation(OfflinePlayer player) {
        return profiles.get(player.getUniqueId())
                .map(profile -> (int) Math.round(profile.reputation()))
                .orElse(0);
    }

    @Override
    public double tolerance(OfflinePlayer player) {
        return profiles.get(player.getUniqueId())
                .map(profile -> profile.tolerance().level())
                .orElse(0.0);
    }

    @Override
    public double marketPrice(String strainId) {
        return markets.cachedPrice(strainId);
    }
}
