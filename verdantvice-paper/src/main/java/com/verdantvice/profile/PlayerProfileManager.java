package com.verdantvice.profile;

import com.verdantvice.i18n.TranslationService;
import com.verdantvice.storage.PlayerProfileRepository;
import com.verdantvice.tolerance.ToleranceState;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class PlayerProfileManager {
    private final JavaPlugin plugin;
    private final PlayerProfileRepository repository;
    private final TranslationService translations;
    private final PlayerProfileService service = new PlayerProfileService();
    private final Map<UUID, PlayerProfile> profiles = new ConcurrentHashMap<>();

    public PlayerProfileManager(
            JavaPlugin plugin,
            PlayerProfileRepository repository,
            TranslationService translations
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.translations = Objects.requireNonNull(translations, "translations");
    }

    public CompletableFuture<PlayerProfile> load(Player player) {
        String detected = normalize(player.locale().toString());
        return load(player.getUniqueId(), detected);
    }

    /**
     * Loads an offline or online profile without requiring a Bukkit Player
     * instance. This entry point is intended for the public API and is safe to
     * invoke from any thread; database work remains asynchronous.
     */
    public CompletableFuture<PlayerProfile> load(UUID playerId, String fallbackLocale) {
        Objects.requireNonNull(playerId, "playerId");
        String locale = normalize(fallbackLocale);
        PlayerProfile cached = profiles.get(playerId);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return repository.load(playerId, locale).thenApply(profile -> {
            profiles.put(profile.playerUuid(), profile);
            translations.preloadOverride(profile.playerUuid(), profile.locale());
            return profile;
        });
    }

    public CompletableFuture<Void> save(UUID playerId) {
        PlayerProfile profile = profiles.get(playerId);
        return profile == null
                ? CompletableFuture.completedFuture(null)
                : repository.save(profile);
    }

    public CompletableFuture<Void> saveAll() {
        CompletableFuture<?>[] futures = profiles.values().stream()
                .map(repository::save)
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures);
    }

    public void unload(UUID playerId) {
        PlayerProfile profile = profiles.remove(playerId);
        if (profile != null) {
            repository.save(profile).exceptionally(failure -> {
                plugin.getLogger().log(
                        Level.WARNING,
                        "Could not save player profile " + playerId,
                        failure
                );
                return null;
            });
        }
    }

    public Optional<PlayerProfile> get(UUID playerId) {
        return Optional.ofNullable(profiles.get(playerId));
    }

    public PlayerProfile require(UUID playerId) {
        PlayerProfile profile = profiles.get(playerId);
        if (profile == null) {
            throw new IllegalStateException("profile_not_loaded");
        }
        return profile;
    }

    public CompletableFuture<PlayerProfile> setLocale(Player player, String locale) {
        PlayerProfile updated = service.changeLocale(
                require(player.getUniqueId()),
                locale,
                Instant.now()
        );
        profiles.put(player.getUniqueId(), updated);
        translations.preloadOverride(player.getUniqueId(), locale);
        return repository.save(updated).thenApply(ignored -> updated);
    }

    public CompletableFuture<PlayerProfile> grantExperience(
            UUID playerId,
            SkillTree tree,
            long amount
    ) {
        PlayerProfile updated = service.grantExperience(
                require(playerId),
                tree,
                amount,
                Instant.now()
        );
        profiles.put(playerId, updated);
        return repository.save(updated).thenApply(ignored -> updated);
    }

    public CompletableFuture<PlayerProfile> addReputation(UUID playerId, double amount) {
        PlayerProfile updated = service.addReputation(
                require(playerId),
                amount,
                Instant.now()
        );
        profiles.put(playerId, updated);
        return repository.save(updated).thenApply(ignored -> updated);
    }

    public CompletableFuture<PlayerProfile> setTolerance(
            UUID playerId,
            ToleranceState tolerance
    ) {
        PlayerProfile updated = service.withTolerance(
                require(playerId),
                tolerance,
                Instant.now()
        );
        profiles.put(playerId, updated);
        return repository.save(updated).thenApply(ignored -> updated);
    }

    public Collection<PlayerProfile> loadedProfiles() {
        return java.util.List.copyOf(profiles.values());
    }

    public CompletableFuture<Void> reset(UUID playerId) {
        profiles.remove(playerId);
        return repository.delete(playerId);
    }

    private String normalize(String locale) {
        if (locale == null || locale.isBlank()) {
            return "pl_PL";
        }
        String value = locale.replace('-', '_');
        String[] parts = value.split("_", 3);
        if (parts.length < 2) {
            return "pl_PL";
        }
        String candidate = parts[0].toLowerCase(java.util.Locale.ROOT)
                + "_" + parts[1].toUpperCase(java.util.Locale.ROOT);
        return translations.supportedLocales().contains(candidate) ? candidate : "pl_PL";
    }

    private CompletableFuture<Void> onMain(Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                action.run();
                future.complete(null);
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        });
        return future;
    }
}
