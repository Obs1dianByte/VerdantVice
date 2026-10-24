package com.verdantvice.market;

import com.verdantvice.config.ConfigManager;
import com.verdantvice.strain.Strain;
import com.verdantvice.strain.StrainRegistry;
import org.bukkit.configuration.file.YamlConfiguration;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

public final class PaperMarketService {
    private final MarketRepository repository;
    private final StrainRegistry strains;
    private final Function<String, YamlConfiguration> configuration;
    private final MarketPricingService pricing = new MarketPricingService();
    private final Map<String, Double> currentPrices = new ConcurrentHashMap<>();

    public PaperMarketService(MarketRepository repository, StrainRegistry strains, ConfigManager configs) {
        this(repository, strains, Objects.requireNonNull(configs, "configs")::get);
    }

    PaperMarketService(
            MarketRepository repository,
            StrainRegistry strains,
            Function<String, YamlConfiguration> configuration
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.strains = Objects.requireNonNull(strains, "strains");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    public CompletableFuture<Void> initialize() {
        return repository.initialize(strains.all())
                .thenCompose(ignored -> repository.all())
                .thenAccept(records -> records.forEach(record ->
                        currentPrices.put(record.strainId(), record.currentPrice())));
    }

    public CompletableFuture<MarketQuote> quote(
            String strainId,
            double quality,
            double freshness,
            double reputation
    ) {
        Strain strain = strains.require(strainId);
        return repository.findOrInitialize(strain).thenApply(record -> {
            currentPrices.put(record.strainId(), record.currentPrice());
            return pricing.quote(strain, record.state(), quality, freshness, reputation);
        });
    }

    public CompletableFuture<List<PaperMarketRecord>> snapshot() {
        return repository.all();
    }

    public CompletableFuture<List<MarketUpdateProposal>> proposeRefresh(Instant now) {
        return repository.all().thenApply(records -> proposals(records, now));
    }

    public CompletableFuture<Boolean> apply(MarketUpdateProposal proposal) {
        return repository.save(proposal.previous(), proposal.replacement())
                .thenApply(applied -> {
                    if (applied) {
                        PaperMarketRecord replacement = proposal.replacement();
                        currentPrices.put(
                                replacement.strainId(),
                                replacement.currentPrice()
                        );
                    }
                    return applied;
                });
    }

    public double cachedPrice(String strainId) {
        String normalized = Objects.requireNonNull(strainId, "strainId")
                .toLowerCase(Locale.ROOT);
        return currentPrices.getOrDefault(
                normalized,
                strains.find(normalized)
                        .map(Strain::baseMarketPrice)
                        .orElse(0.0)
        );
    }

    public String eventTranslationKey(MarketEventType type, String strainId) {
        return switch (type) {
            case HIGH_DEMAND -> "market.events.high-demand-night-widow";
            case OVERSUPPLY -> "market.events.berry-fog-oversupply";
            case RARE_SEED_SHORTAGE -> "market.events.rare-seed-shortage";
            case PREMIUM_PRODUCT_WEEK -> "market.events.premium-product-week";
            case MARKET_INSPECTION -> "market.events.market-inspection";
            case NONE -> "common.none";
        };
    }

    private List<MarketUpdateProposal> proposals(List<PaperMarketRecord> records, Instant now) {
        double reversion = clamp(configuration.apply("market.yml").getDouble("demand-reversion", 0.08), 0.0, 1.0);
        double eventChance = clamp(configuration.apply("market.yml").getDouble("events.chance-per-update", 0.15),
                0.0, 1.0);
        long eventMinutes = Math.max(1L,
                configuration.apply("market.yml").getLong("events.duration-minutes", 120L));
        double eventMin = configuration.apply("market.yml").getDouble("events.multiplier-min", 0.75);
        double eventMax = configuration.apply("market.yml").getDouble("events.multiplier-max", 1.40);
        boolean startEvent = !records.isEmpty() && ThreadLocalRandom.current().nextDouble() < eventChance;
        MarketEventType selectedEvent = startEvent ? randomEvent() : MarketEventType.NONE;
        int selected = startEvent ? eventTarget(records, selectedEvent) : -1;
        List<MarketUpdateProposal> result = new ArrayList<>(records.size());
        for (int index = 0; index < records.size(); index++) {
            PaperMarketRecord record = records.get(index);
            double supply = 1.0 + (record.state().supplyIndex() - 1.0) * (1.0 - reversion);
            double demand = 1.0 + (record.state().demandIndex() - 1.0) * (1.0 - reversion);
            double sales = record.state().serverSalesWeight() * 0.9;
            MarketEventWindow event = record.event().activeAt(now)
                    ? record.event() : MarketEventWindow.none(now);
            if (index == selected && event.type() == MarketEventType.NONE) {
                double low = Math.min(eventMin, eventMax);
                double high = Math.max(eventMin, eventMax);
                double multiplier = ThreadLocalRandom.current().nextDouble(low, Math.nextUp(high));
                event = new MarketEventWindow(
                        selectedEvent, multiplier, now.plus(Duration.ofMinutes(eventMinutes)));
            }
            MarketState state = new MarketState(
                    record.strainId(), supply, demand, Math.max(0.0, sales),
                    event.type(), event.type() == MarketEventType.NONE ? 1.0 : event.multiplier(),
                    record.state().minimumPrice(), record.state().maximumPrice(), now);
            Optional<Strain> strain = strains.find(record.strainId());
            if (strain.isEmpty()) continue;
            double current = pricing.quote(strain.get(), state, 65.0, 100.0, 0.0).unitPrice();
            PaperMarketRecord replacement = new PaperMarketRecord(
                    record.strainId(), record.basePrice(), current, state, event, record.version() + 1);
            if (Math.abs(current - record.currentPrice()) > 0.0001
                    || event.type() != record.event().type()) {
                result.add(new MarketUpdateProposal(record, replacement));
            }
        }
        return List.copyOf(result);
    }

    private int eventTarget(List<PaperMarketRecord> records, MarketEventType event) {
        String preferred = switch (event) {
            case HIGH_DEMAND -> "night-widow";
            case OVERSUPPLY -> "berry-fog";
            default -> "";
        };
        if (!preferred.isEmpty()) {
            for (int index = 0; index < records.size(); index++) {
                if (records.get(index).strainId().equals(preferred)) return index;
            }
        }
        if (event == MarketEventType.RARE_SEED_SHORTAGE) {
            List<Integer> rare = new ArrayList<>();
            for (int index = 0; index < records.size(); index++) {
                int candidate = index;
                strains.find(records.get(index).strainId())
                        .filter(strain -> strain.rarity()
                                .ordinal() >= com.verdantvice.strain.StrainRarity.RARE.ordinal())
                        .ifPresent(ignored -> rare.add(candidate));
            }
            if (!rare.isEmpty()) {
                return rare.get(ThreadLocalRandom.current().nextInt(rare.size()));
            }
        }
        return ThreadLocalRandom.current().nextInt(records.size());
    }

    private MarketEventType randomEvent() {
        List<String> configured = configuration.apply("market.yml").getStringList("events.types");
        if (configured.isEmpty()) {
            return MarketEventType.HIGH_DEMAND;
        }
        String selected = configured.get(ThreadLocalRandom.current().nextInt(configured.size()));
        try {
            return MarketEventType.valueOf(selected.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return MarketEventType.HIGH_DEMAND;
        }
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
