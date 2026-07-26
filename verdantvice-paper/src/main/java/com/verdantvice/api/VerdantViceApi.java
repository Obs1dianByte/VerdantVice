package com.verdantvice.api;

import com.verdantvice.contract.StoredContract;
import com.verdantvice.farm.Farm;
import com.verdantvice.market.MarketQuote;
import com.verdantvice.plant.Plant;
import com.verdantvice.profile.PlayerProfile;
import com.verdantvice.strain.Strain;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Stable public entry point exposed through Bukkit's ServicesManager.
 *
 * <p>Database-backed operations are asynchronous. Snapshot lookups return
 * immutable domain objects and never expose mutable plugin internals.</p>
 */
public interface VerdantViceApi {
    int API_VERSION = 1;

    int apiVersion();

    Plugin plugin();

    CompletableFuture<PlayerProfile> profile(UUID playerUuid);

    Optional<Farm> farm(UUID farmUuid);

    Optional<Farm> playerFarm(UUID playerUuid);

    Optional<Plant> plant(UUID plantUuid);

    Optional<Strain> strain(String strainId);

    CompletableFuture<StoredContract> createContract(
            UUID playerUuid,
            UUID herbalistUuid
    );

    CompletableFuture<MarketQuote> marketQuote(
            String strainId,
            double quality,
            double freshness,
            double reputation
    );

    CompletableFuture<Double> currentPrice(String strainId);

    CompletableFuture<Void> registerStrain(Strain strain);
}
