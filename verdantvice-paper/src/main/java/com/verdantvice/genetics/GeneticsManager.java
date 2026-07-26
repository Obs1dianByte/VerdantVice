package com.verdantvice.genetics;

import com.verdantvice.common.DomainChecks;
import com.verdantvice.config.ConfigManager;
import com.verdantvice.effects.EffectProfile;
import com.verdantvice.effects.EffectType;
import com.verdantvice.security.CustomItemType;
import com.verdantvice.security.SecureItemService;
import com.verdantvice.security.SignedItemData;
import com.verdantvice.storage.CustomStrainRepository;
import com.verdantvice.strain.Strain;
import com.verdantvice.strain.StrainRarity;
import com.verdantvice.strain.StrainRegistry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToDoubleFunction;
import java.util.logging.Level;

public final class GeneticsManager implements SeedGeneticsResolver {
    private static final Set<CustomItemType> SEEDS = Set.of(CustomItemType.SEED);

    private final JavaPlugin plugin;
    private final ConfigManager configs;
    private final CustomStrainRepository repository;
    private final StrainRegistry strains;
    private final SecureItemService secureItems;
    private final GeneticsService geneticsService = new GeneticsService();
    private final Map<String, CustomStrainRecord> customStrains = new ConcurrentHashMap<>();

    public GeneticsManager(
            JavaPlugin plugin,
            ConfigManager configs,
            CustomStrainRepository repository,
            StrainRegistry strains,
            SecureItemService secureItems
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.strains = Objects.requireNonNull(strains, "strains");
        this.secureItems = Objects.requireNonNull(secureItems, "secureItems");
    }

    public CompletableFuture<Void> load() {
        return repository.loadApproved().thenAccept(records -> {
            for (CustomStrainRecord record : records) {
                try {
                    register(record);
                } catch (RuntimeException failure) {
                    plugin.getLogger().log(
                            Level.WARNING,
                            "Custom strain " + record.strainId()
                                    + " could not be restored",
                            failure
                    );
                }
            }
        });
    }

    public Collection<CustomStrainRecord> lines() {
        return customStrains.values().stream()
                .sorted(Comparator
                        .comparing((CustomStrainRecord record) -> record.line().createdAt())
                        .thenComparing(CustomStrainRecord::strainId))
                .toList();
    }

    public Optional<CustomStrainRecord> line(String strainId) {
        String normalized = DomainChecks.identifier(strainId, "strainId");
        return Optional.ofNullable(customStrains.get(normalized));
    }

    public CompletableFuture<CrossingResult> cross(
            Player player,
            String requestedName,
            ItemStack firstSeed,
            ItemStack secondSeed
    ) {
        Objects.requireNonNull(player, "player");
        String name;
        try {
            name = validateName(requestedName);
        } catch (IllegalArgumentException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        SignedItemData firstData = verifiedSeed(firstSeed, player.getUniqueId());
        SignedItemData secondData = verifiedSeed(secondSeed, player.getUniqueId());
        if (firstData.uniqueInstanceId().equals(secondData.uniqueInstanceId())
                || firstData.strain().equals(secondData.strain())) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("same_parent_strain")
            );
        }

        Strain firstStrain = strains.require(firstData.strain());
        Strain secondStrain = strains.require(secondData.strain());
        GeneticProfile firstProfile = profileForSeed(firstData);
        GeneticProfile secondProfile = profileForSeed(secondData);
        Instant now = Instant.now();
        UUID identity = UUID.randomUUID();
        String strainId = customId(name, identity);
        UUID lineId = lineId(strainId);
        GeneticProfile child = geneticsService.cross(
                lineId,
                firstProfile,
                secondProfile,
                new java.util.SplittableRandom(
                        identity.getMostSignificantBits()
                                ^ identity.getLeastSignificantBits()
                ),
                now
        );
        GeneticLine line = nameService().create(
                lineId,
                name,
                orderedParents(firstStrain.id(), secondStrain.id()),
                player.getUniqueId(),
                generation(firstStrain.id(), secondStrain.id()),
                SeedGenetics.dominantTraits(child),
                orderedParents(firstStrain.particleProfile(), secondStrain.particleProfile()),
                effects(firstStrain, secondStrain),
                lineStability(child),
                now
        );
        CustomStrainRecord record =
                new CustomStrainRecord(strainId, line, now, 0L);
        Strain strain = toStrain(record).orElseThrow(() ->
                new IllegalStateException("custom_strain_parent_missing"));

        String operationId = "genetics:" + record.strainId();
        return secureItems.consume(
                        firstSeed,
                        SEEDS,
                        player.getUniqueId(),
                        operationId + ":first"
                )
                .thenCompose(ignored -> secureItems.consume(
                        secondSeed,
                        SEEDS,
                        player.getUniqueId(),
                        operationId + ":second"
                ))
                .thenCompose(ignored -> repository.create(record))
                .thenCompose(ignored -> onMainFuture(() -> {
                    register(record, strain);
                    return secureItems.issue(
                            player,
                            CustomItemType.SEED,
                            player.getUniqueId(),
                            strain.id()
                    );
                }))
                .thenCompose(future -> future)
                .thenApply(seed -> new CrossingResult(
                        record,
                        strain,
                        firstStrain.displayName(),
                        secondStrain.displayName(),
                        firstData.uniqueInstanceId(),
                        secondData.uniqueInstanceId(),
                        seed
                ));
    }

    @Override
    public GeneticProfile profileForSeed(SignedItemData seed) {
        Objects.requireNonNull(seed, "seed");
        CustomStrainRecord custom = customStrains.get(seed.strain());
        if (custom == null) {
            return SeedGenetics.baseProfile(geneticsService, seed);
        }
        return customProfile(seed, custom);
    }

    private SignedItemData verifiedSeed(ItemStack item, UUID actor) {
        var verification = secureItems.verify(item);
        if (!verification.valid()
                || verification.data().itemType()
                != CustomItemType.SEED) {
            throw new IllegalArgumentException("invalid_item");
        }
        SignedItemData data = verification.data();
        if (!Objects.equals(data.ownerUuid(), actor)) {
            throw new IllegalArgumentException("wrong_owner");
        }
        if (strains.find(data.strain()).isEmpty()) {
            throw new IllegalArgumentException("invalid_item");
        }
        return data;
    }

    private GeneticProfile customProfile(
            SignedItemData seed,
            CustomStrainRecord record
    ) {
        Map<String, Double> traits = record.line().dominantTraits();
        double jitter = Math.max(0.0, 1.0 - record.line().stability()) * 0.12;
        java.util.SplittableRandom random = new java.util.SplittableRandom(
                seed.uniqueInstanceId().getMostSignificantBits()
                        ^ seed.uniqueInstanceId().getLeastSignificantBits()
                        ^ record.line().id().getMostSignificantBits()
        );
        return geneticsService.create(
                SeedGenetics.profileId(seed),
                trait(traits, SeedGenetics.YIELD_MODIFIER, jitter, random),
                trait(traits, SeedGenetics.GROWTH_SPEED_MODIFIER, jitter, random),
                trait(traits, SeedGenetics.RESISTANCE_MODIFIER, jitter, random),
                trait(traits, SeedGenetics.QUALITY_MODIFIER, jitter, random),
                trait(traits, SeedGenetics.SMELL_MODIFIER, jitter, random),
                trait(traits, SeedGenetics.STABILITY_MODIFIER, jitter, random),
                trait(traits, SeedGenetics.NEGATIVE_EFFECT_MODIFIER, jitter, random),
                seed.createdAt()
        );
    }

    private double trait(
            Map<String, Double> traits,
            String key,
            double jitter,
            java.util.SplittableRandom random
    ) {
        double base = traits.getOrDefault(key, 1.0);
        double delta = jitter <= 0.0 ? 0.0 : random.nextDouble(-jitter, jitter);
        return clamp(base + delta, 0.25, 2.0);
    }

    private Optional<Strain> toStrain(CustomStrainRecord record) {
        List<Strain> parents = record.line().parentStrainIds().stream()
                .map(strains::find)
                .flatMap(Optional::stream)
                .toList();
        if (parents.isEmpty()) {
            return Optional.empty();
        }
        Strain primary = parents.get(0);
        Map<String, Double> traits = record.line().dominantTraits();
        double average = beneficialAverage(traits);
        StrainRarity rarity = rarity(average);
        double price = average(parents, Strain::baseMarketPrice)
                * rarity.marketMultiplier()
                * (0.9 + record.line().stability() * 0.2);
        return Optional.of(new Strain(
                record.strainId(),
                record.line().name(),
                rarity,
                Duration.ofMillis(Math.max(1000L, Math.round(
                        parents.stream()
                                .mapToLong(parent -> parent.growthDuration().toMillis())
                                .average()
                                .orElse(primary.growthDuration().toMillis())
                ))),
                clamp(average(parents, Strain::difficulty)
                        + Math.max(0, record.line().generation() - 1) * 2.0,
                        1.0,
                        100.0),
                clamp(average(parents, Strain::baseYield), 1.0, 100.0),
                clamp(average(parents, Strain::qualityPotential), 1.0, 100.0),
                clamp(average(parents, Strain::diseaseResistance), 1.0, 100.0),
                clamp(average(parents, Strain::waterConsumption), 0.1, 10.0),
                clamp(average(parents, Strain::lightPreference), 0.0, 15.0),
                clamp(average(parents, Strain::stressSensitivity), 0.0, 2.0),
                clamp(average(parents, Strain::smellIntensity), 0.0, 100.0),
                mergedEffects(parents, false),
                mergedEffects(parents, true),
                Math.max(1, (int) Math.round(price / 2.0)),
                Math.max(1.0, price),
                primary.visualModel(),
                primary.particleProfile()
        ));
    }

    private void register(CustomStrainRecord record) {
        toStrain(record).ifPresent(strain -> register(record, strain));
    }

    private void register(CustomStrainRecord record, Strain strain) {
        if (strains.find(strain.id()).isPresent()) {
            strains.replace(strain);
        } else {
            strains.register(strain);
        }
        customStrains.put(record.strainId(), record);
    }

    private GeneticLineService nameService() {
        var genetics = configs.get("genetics.yml");
        int max = genetics.getInt("name.maximum-length", 24);
        return new GeneticLineService(
                max,
                new LinkedHashSet<>(genetics.getStringList("name.blocked-words"))
        );
    }

    private String validateName(String requestedName) {
        int minimum = configs.get("genetics.yml")
                .getInt("name.minimum-length", 3);
        try {
            String name = nameService().validateName(requestedName);
            if (name.codePointCount(0, name.length()) < minimum) {
                throw new IllegalArgumentException("too short");
            }
            return name;
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("invalid_name", failure);
        }
    }

    private Set<String> orderedParents(String first, String second) {
        LinkedHashSet<String> parents = new LinkedHashSet<>();
        parents.add(first);
        parents.add(second);
        return Set.copyOf(parents);
    }

    private int generation(String first, String second) {
        return 1 + Math.max(parentGeneration(first), parentGeneration(second));
    }

    private int parentGeneration(String strainId) {
        CustomStrainRecord record = customStrains.get(strainId);
        return record == null ? 0 : record.line().generation();
    }

    private Set<String> effects(Strain first, Strain second) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        addEffects(names, first.effectProfile());
        addEffects(names, second.effectProfile());
        addEffects(names, first.negativeEffectProfile());
        addEffects(names, second.negativeEffectProfile());
        return Set.copyOf(names);
    }

    private void addEffects(Set<String> names, EffectProfile profile) {
        profile.weights().entrySet().stream()
                .filter(entry -> entry.getValue() > 0.0)
                .map(entry -> entry.getKey().name())
                .sorted()
                .forEach(names::add);
    }

    private EffectProfile mergedEffects(List<Strain> parents, boolean negative) {
        EnumMap<EffectType, Double> weights = new EnumMap<>(EffectType.class);
        for (EffectType type : EffectType.values()) {
            double average = parents.stream()
                    .map(strain -> negative
                            ? strain.negativeEffectProfile()
                            : strain.effectProfile())
                    .mapToDouble(profile -> profile.weight(type))
                    .average()
                    .orElse(0.0);
            if (average > 0.0) {
                weights.put(type, clamp(average, 0.0, 1.0));
            }
        }
        return new EffectProfile(weights);
    }

    private double lineStability(GeneticProfile profile) {
        double penalty = configs.get("genetics.yml")
                .getDouble("cross-stability-penalty", 0.07);
        return clamp((profile.stabilityModifier() / 2.0) - penalty, 0.0, 1.0);
    }

    private StrainRarity rarity(double beneficialAverage) {
        if (beneficialAverage >= 1.42) {
            return StrainRarity.LEGENDARY;
        }
        if (beneficialAverage >= 1.16) {
            return StrainRarity.RARE;
        }
        if (beneficialAverage >= 1.03) {
            return StrainRarity.UNCOMMON;
        }
        return StrainRarity.BASIC;
    }

    private double beneficialAverage(Map<String, Double> traits) {
        return (
                traits.getOrDefault(SeedGenetics.YIELD_MODIFIER, 1.0)
                        + traits.getOrDefault(SeedGenetics.GROWTH_SPEED_MODIFIER, 1.0)
                        + traits.getOrDefault(SeedGenetics.RESISTANCE_MODIFIER, 1.0)
                        + traits.getOrDefault(SeedGenetics.QUALITY_MODIFIER, 1.0)
                        + traits.getOrDefault(SeedGenetics.SMELL_MODIFIER, 1.0)
                        + traits.getOrDefault(SeedGenetics.STABILITY_MODIFIER, 1.0)
                        + (2.0 - traits.getOrDefault(
                        SeedGenetics.NEGATIVE_EFFECT_MODIFIER,
                        1.0
                ))
        ) / 7.0;
    }

    private double average(List<Strain> parents, ToDoubleFunction<Strain> value) {
        return parents.stream().mapToDouble(value).average().orElse(1.0);
    }

    private String customId(String name, UUID identity) {
        String slug = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", "_")
                .replaceAll("^_+|_+$", "");
        if (slug.isBlank()) {
            slug = "line";
        }
        slug = java.text.Normalizer.normalize(slug, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^a-z0-9_.-]+", "_")
                .replaceAll("^_+|_+$", "");
        if (slug.isBlank()) {
            slug = "line";
        }
        String suffix = identity.toString().substring(0, 8);
        int maximumSlugLength = Math.max(1, 64 - "custom_".length() - 1 - suffix.length());
        if (slug.length() > maximumSlugLength) {
            slug = slug.substring(0, maximumSlugLength).replaceAll("_+$", "");
            if (slug.isBlank()) {
                slug = "line";
            }
        }
        return "custom_" + slug + "_" + suffix;
    }

    private UUID lineId(String strainId) {
        return UUID.nameUUIDFromBytes(
                ("verdantvice:line:" + strainId).getBytes(StandardCharsets.UTF_8)
        );
    }

    private <T> CompletableFuture<T> onMainFuture(java.util.concurrent.Callable<T> action) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Runnable run = () -> {
            try {
                future.complete(action.call());
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        };
        if (Bukkit.isPrimaryThread()) {
            run.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, run);
        }
        return future;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public record CrossingResult(
            CustomStrainRecord record,
            Strain strain,
            String parentA,
            String parentB,
            UUID firstSeedId,
            UUID secondSeedId,
            ItemStack seed
    ) {
        public CrossingResult {
            Objects.requireNonNull(record, "record");
            Objects.requireNonNull(strain, "strain");
            parentA = Objects.requireNonNull(parentA, "parentA");
            parentB = Objects.requireNonNull(parentB, "parentB");
            Objects.requireNonNull(firstSeedId, "firstSeedId");
            Objects.requireNonNull(secondSeedId, "secondSeedId");
            seed = Objects.requireNonNull(seed, "seed").clone();
        }

        @Override
        public ItemStack seed() {
            return seed.clone();
        }
    }
}
