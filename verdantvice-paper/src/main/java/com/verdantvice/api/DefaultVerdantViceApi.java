package com.verdantvice.api;

import com.verdantvice.VerdantVicePlugin;
import com.verdantvice.contract.StoredContract;
import com.verdantvice.farm.Farm;
import com.verdantvice.market.MarketQuote;
import com.verdantvice.plant.Plant;
import com.verdantvice.profile.PlayerProfile;
import com.verdantvice.strain.Strain;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class DefaultVerdantViceApi implements VerdantViceApi {
    private static final double REFERENCE_QUALITY = 50.0;
    private static final double REFERENCE_FRESHNESS = 100.0;

    private final VerdantVicePlugin plugin;

    public DefaultVerdantViceApi(VerdantVicePlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public int apiVersion() {
        return API_VERSION;
    }

    @Override
    public Plugin plugin() {
        return plugin;
    }

    @Override
    public CompletableFuture<PlayerProfile> profile(UUID playerUuid) {
        requireReady();
        return plugin.profiles().load(playerUuid, plugin.translations().serverLocale());
    }

    @Override
    public Optional<Farm> farm(UUID farmUuid) {
        requireReady();
        return plugin.farms().get(Objects.requireNonNull(farmUuid, "farmUuid"));
    }

    @Override
    public Optional<Farm> playerFarm(UUID playerUuid) {
        requireReady();
        return plugin.farms().owned(Objects.requireNonNull(playerUuid, "playerUuid"));
    }

    @Override
    public Optional<Plant> plant(UUID plantUuid) {
        requireReady();
        return plugin.plants().plant(Objects.requireNonNull(plantUuid, "plantUuid"));
    }

    @Override
    public Optional<Strain> strain(String strainId) {
        requireReady();
        return plugin.strains().find(strainId);
    }

    @Override
    public CompletableFuture<StoredContract> createContract(
            UUID playerUuid,
            UUID herbalistUuid
    ) {
        requireReady();
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(herbalistUuid, "herbalistUuid");
        return plugin.herbalists().herbalistRepository().find(herbalistUuid)
                .thenCompose(record -> record
                        .map(value -> plugin.herbalists().contractService()
                                .generate(playerUuid, value, Instant.now()))
                        .orElseGet(() -> CompletableFuture.failedFuture(
                                new IllegalArgumentException(
                                        "Unknown herbalist: " + herbalistUuid))));
    }

    @Override
    public CompletableFuture<MarketQuote> marketQuote(
            String strainId,
            double quality,
            double freshness,
            double reputation
    ) {
        requireReady();
        return plugin.herbalists().marketService().quote(
                strainId,
                quality,
                freshness,
                reputation
        );
    }

    @Override
    public CompletableFuture<Double> currentPrice(String strainId) {
        return marketQuote(
                strainId,
                REFERENCE_QUALITY,
                REFERENCE_FRESHNESS,
                0.0
        ).thenApply(MarketQuote::unitPrice);
    }

    @Override
    public CompletableFuture<Void> registerStrain(Strain strain) {
        requireReady();
        plugin.strains().register(Objects.requireNonNull(strain, "strain"));
        return plugin.herbalists().marketService().initialize();
    }

    private void requireReady() {
        if (!plugin.ready()) {
            throw new IllegalStateException("VerdantVice is not ready");
        }
    }
}
