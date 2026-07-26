package com.verdantvice.herbalist;

import com.verdantvice.config.ConfigManager;
import com.verdantvice.i18n.TranslationService;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

public final class HerbalistConversionService {
    private final JavaPlugin plugin;
    private final ConfigManager configs;
    private final TranslationService translations;
    private final HerbalistRepository repository;
    private final NamespacedKey evaluatedKey;
    private final NamespacedKey herbalistIdKey;
    private final Object conversionLock = new Object();
    private CompletableFuture<Void> conversionTail = CompletableFuture.completedFuture(null);

    public HerbalistConversionService(
            JavaPlugin plugin,
            ConfigManager configs,
            TranslationService translations,
            HerbalistRepository repository
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.translations = Objects.requireNonNull(translations, "translations");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.evaluatedKey = new NamespacedKey(plugin, "herbalist_evaluated");
        this.herbalistIdKey = new NamespacedKey(plugin, "herbalist_id");
    }

    public boolean isMarkedHerbalist(Villager villager) {
        return villager.getPersistentDataContainer().has(herbalistIdKey, PersistentDataType.STRING);
    }

    public boolean isEvaluated(Villager villager) {
        return villager.getPersistentDataContainer().has(evaluatedKey, PersistentDataType.BYTE);
    }

    public Optional<UUID> markedId(Villager villager) {
        String value = villager.getPersistentDataContainer()
                .get(herbalistIdKey, PersistentDataType.STRING);
        if (value == null) return Optional.empty();
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public CompletableFuture<HerbalistEvaluation> evaluate(Villager villager) {
        return evaluate(villager, false);
    }

    /**
     * Administrative conversion that bypasses only the random draw. Entity
     * validity, profession, village cap and database uniqueness remain
     * enforced.
     */
    public CompletableFuture<HerbalistEvaluation> force(Villager villager) {
        return evaluate(villager, true);
    }

    private CompletableFuture<HerbalistEvaluation> evaluate(
            Villager villager,
            boolean forced
    ) {
        requireMainThread();
        if (!villager.isValid() || villager.isDead()) {
            return CompletableFuture.completedFuture(new HerbalistEvaluation(
                    HerbalistEvaluation.Status.INVALID_ENTITY, Optional.empty()));
        }
        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        Optional<UUID> marked = markedId(villager);
        if (marked.isPresent()) {
            return repository.find(marked.get()).thenApply(record -> new HerbalistEvaluation(
                    record.isPresent()
                            ? HerbalistEvaluation.Status.EXISTING
                            : HerbalistEvaluation.Status.INVALID_ENTITY,
                    record));
        }
        if (villager.getProfession() != Villager.Profession.FARMER) {
            return CompletableFuture.completedFuture(new HerbalistEvaluation(
                    HerbalistEvaluation.Status.NOT_FARMER, Optional.empty()));
        }
        if (!forced && pdc.has(evaluatedKey, PersistentDataType.BYTE)) {
            return CompletableFuture.completedFuture(new HerbalistEvaluation(
                    HerbalistEvaluation.Status.NORMAL, Optional.empty()));
        }

        Location location = villager.getLocation();
        UUID entityUuid = villager.getUniqueId();
        UUID worldUuid = villager.getWorld().getUID();
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        double chance = clamp(configs.get("villagers.yml")
                .getDouble("herbalist.conversion-chance", 0.40), 0.0, 1.0);
        int radius = Math.max(1,
                configs.get("villagers.yml").getInt("herbalist.village-radius", 64));
        int maximum = Math.max(0,
                configs.get("villagers.yml").getInt("herbalist.max-per-village", 3));
        int reputation = Math.max(0,
                configs.get("villagers.yml").getInt("herbalist.starting-reputation", 0));

        CompletableFuture<HerbalistEvaluation> evaluation = serialize(() ->
                repository.findByEntity(entityUuid).thenCompose(existing -> {
                    if (existing.isPresent()) {
                        return CompletableFuture.completedFuture(new HerbalistEvaluation(
                                HerbalistEvaluation.Status.EXISTING, existing));
                    }
                    if (!forced && ThreadLocalRandom.current().nextDouble() >= chance) {
                        return CompletableFuture.completedFuture(new HerbalistEvaluation(
                                HerbalistEvaluation.Status.NORMAL, Optional.empty()));
                    }
                    UUID herbalistId = UUID.randomUUID();
                    return repository.createIfBelowLimit(
                            herbalistId, entityUuid, worldUuid, x, y, z, radius,
                            maximum, reputation, Instant.now()).thenApply(created -> switch (created.status()) {
                        case CREATED -> new HerbalistEvaluation(
                                HerbalistEvaluation.Status.CREATED, created.optionalRecord());
                        case ALREADY_EXISTS -> new HerbalistEvaluation(
                                HerbalistEvaluation.Status.EXISTING, created.optionalRecord());
                        case VILLAGE_LIMIT -> new HerbalistEvaluation(
                                HerbalistEvaluation.Status.VILLAGE_LIMIT, Optional.empty());
                    });
                }));
        return evaluation.thenCompose(result -> onMain(() -> {
            if (villager.isValid() && !villager.isDead()) {
                applyMarker(villager, result);
            }
            return result;
        }));
    }

    public CompletableFuture<Optional<HerbalistRecord>> resolve(Villager villager) {
        Optional<UUID> id = markedId(villager);
        return id.isPresent()
                ? repository.find(id.get())
                : repository.findByEntity(villager.getUniqueId());
    }

    private void applyMarker(Villager villager, HerbalistEvaluation result) {
        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        pdc.set(evaluatedKey, PersistentDataType.BYTE, (byte) 1);
        result.herbalist().ifPresent(record -> {
            pdc.set(herbalistIdKey, PersistentDataType.STRING, record.herbalistUuid().toString());
            String locale = configs.main().getString("default-language", "pl_PL");
            Component name = translations.component(locale, "herbalist.title", java.util.Map.of());
            villager.customName(name);
            villager.setCustomNameVisible(true);
            villager.setRemoveWhenFarAway(false);
        });
    }

    private <T> CompletableFuture<T> serialize(Supplier<CompletableFuture<T>> operation) {
        synchronized (conversionLock) {
            CompletableFuture<T> next = conversionTail
                    .handle((ignored, failure) -> null)
                    .thenCompose(ignored -> operation.get());
            conversionTail = next.handle((ignored, failure) -> null);
            return next;
        }
    }

    private <T> CompletableFuture<T> onMain(Supplier<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                result.complete(work.get());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        };
        if (plugin.getServer().isPrimaryThread()) task.run();
        else plugin.getServer().getScheduler().runTask(plugin, task);
        return result;
    }

    private void requireMainThread() {
        if (!plugin.getServer().isPrimaryThread()) {
            throw new IllegalStateException("Villager evaluation must start on the server thread");
        }
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
