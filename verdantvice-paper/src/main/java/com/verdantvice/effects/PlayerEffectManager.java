package com.verdantvice.effects;

import com.verdantvice.api.event.VerdantProductUseEvent;
import com.verdantvice.config.ConfigManager;
import com.verdantvice.i18n.TranslationService;
import com.verdantvice.processing.BatchState;
import com.verdantvice.processing.ProcessingManager;
import com.verdantvice.processing.ProductBatch;
import com.verdantvice.profile.PlayerProfile;
import com.verdantvice.profile.PlayerProfileManager;
import com.verdantvice.security.CustomItemType;
import com.verdantvice.security.ItemSigner;
import com.verdantvice.security.SecureItemService;
import com.verdantvice.security.SignedItemData;
import com.verdantvice.strain.Strain;
import com.verdantvice.strain.StrainRegistry;
import com.verdantvice.tolerance.TolerancePolicy;
import com.verdantvice.tolerance.ToleranceService;
import com.verdantvice.tolerance.ToleranceState;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

public final class PlayerEffectManager {
    private final JavaPlugin plugin;
    private final ConfigManager configs;
    private final TranslationService translations;
    private final PlayerProfileManager profiles;
    private final ProcessingManager processing;
    private final SecureItemService secureItems;
    private final ItemSigner signer;
    private final StrainRegistry strains;
    private final ToleranceService toleranceService = new ToleranceService();
    private final EffectService effectService = new EffectService(toleranceService);
    private final Map<UUID, Instant> strongEffectUntil = new ConcurrentHashMap<>();

    public PlayerEffectManager(
            JavaPlugin plugin,
            ConfigManager configs,
            TranslationService translations,
            PlayerProfileManager profiles,
            ProcessingManager processing,
            SecureItemService secureItems,
            ItemSigner signer,
            StrainRegistry strains
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.translations = Objects.requireNonNull(translations, "translations");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.processing = Objects.requireNonNull(processing, "processing");
        this.secureItems = Objects.requireNonNull(secureItems, "secureItems");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.strains = Objects.requireNonNull(strains, "strains");
    }

    public void use(Player player, ItemStack item) {
        if (!configs.get("effects.yml").getBoolean("enabled", true)) {
            player.sendMessage(translations.component(player, "common.disabled"));
            return;
        }
        ItemSigner.Verification verification = signer.verify(item);
        if (!verification.valid() || verification.data().itemType() != CustomItemType.JOINT) {
            player.sendMessage(translations.component(player, "errors.invalid-item"));
            return;
        }
        SignedItemData data = verification.data();
        ProductBatch batch = processing.batch(data.batchId()).orElse(null);
        if (batch == null || batch.state() != BatchState.ROLLED) {
            player.sendMessage(translations.component(player, "errors.wrong-item-state"));
            return;
        }
        Strain strain = strains.find(dominantStrain(batch)).orElse(null);
        if (strain == null) {
            player.sendMessage(translations.component(player, "errors.wrong-item-state"));
            return;
        }
        PlayerProfile profile;
        try {
            profile = profiles.require(player.getUniqueId());
        } catch (IllegalStateException notLoaded) {
            player.sendMessage(translations.component(player, "errors.not-ready"));
            return;
        }
        Instant now = Instant.now();
        TolerancePolicy tolerancePolicy = tolerancePolicy();
        ToleranceState decayed = toleranceService.decay(
                profile.tolerance(),
                now,
                tolerancePolicy
        );
        boolean activeStrong = strongEffectUntil.getOrDefault(
                player.getUniqueId(),
                Instant.EPOCH
        ).isAfter(now);
        PlayerCondition condition = new PlayerCondition(
                Math.max(0.0, Math.min(100.0, player.getHealth() / 20.0 * 100.0)),
                player.getFoodLevel() / 20.0 * 100.0,
                0.0,
                activeStrong
        );
        EffectOutcome outcome = effectService.calculate(
                strain.effectProfile(),
                strain.negativeEffectProfile(),
                batch.qualityScore(),
                Math.max(0.01, Math.min(3.0, 0.65 + batch.qualityScore() / 100.0)),
                Math.max(0.0, Math.min(1.0, batch.freshness() / 100.0)),
                decayed,
                tolerancePolicy,
                condition,
                effectPolicy(),
                ThreadLocalRandom.current()
        );
        if (!outcome.accepted()) {
            player.sendMessage(translations.component(player, "errors.strong-effect-active"));
            return;
        }
        VerdantProductUseEvent event = new VerdantProductUseEvent(
                player,
                data.uniqueInstanceId(),
                Objects.requireNonNull(data.batchId())
        );
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }
        secureItems.consume(
                item.clone(),
                Set.of(CustomItemType.JOINT),
                player.getUniqueId(),
                "use:" + data.uniqueInstanceId()
        ).whenComplete((consumed, failure) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (failure != null) {
                plugin.getLogger().log(Level.WARNING, "Product use failed", unwrap(failure));
                player.sendMessage(translations.component(player, "errors.invalid-signature"));
                return;
            }
            removeInstance(player, data.uniqueInstanceId());
            apply(player, outcome);
            ToleranceState updated = toleranceService.recordUse(
                    decayed,
                    outcome.effectivePotency(),
                    Instant.now(),
                    tolerancePolicy
            );
            profiles.setTolerance(player.getUniqueId(), updated).exceptionally(saveFailure -> {
                plugin.getLogger().log(
                        Level.WARNING,
                        "Could not persist tolerance for " + player.getUniqueId(),
                        saveFailure
                );
                return null;
            });
            if (outcome.strong()) {
                long seconds = configs.get("effects.yml").getLong(
                        "strong-effect-lock-seconds",
                        90L
                );
                strongEffectUntil.put(player.getUniqueId(), Instant.now().plusSeconds(seconds));
            }
            player.sendMessage(translations.component(
                    player,
                    "effects.started",
                    Map.of(
                            "product", strain.displayName(),
                            "duration", duration(outcome)
                    )
            ));
            player.sendMessage(translations.component(
                    player,
                    "effects.tolerance-up",
                    Map.of("tolerance", String.format(java.util.Locale.ROOT, "%.1f", updated.level()))
            ));
        }));
    }

    public double tolerance(UUID playerId) {
        return profiles.get(playerId).map(PlayerProfile::tolerance)
                .map(ToleranceState::level)
                .orElse(0.0);
    }

    private void apply(Player player, EffectOutcome outcome) {
        for (AppliedEffect effect : outcome.effects()) {
            PotionEffectType potion = potion(effect.type());
            if (potion != null) {
                int maximum = configs.get("effects.yml").getInt(
                        "safe-limits.maximum-amplifier",
                        2
                );
                player.addPotionEffect(new PotionEffect(
                        potion,
                        Math.max(1, Math.toIntExact(effect.duration().toMillis() / 50L)),
                        Math.min(maximum, effect.amplifier()),
                        true,
                        true,
                        true
                ));
            } else {
                applyCosmetic(player, effect);
            }
        }
        if (outcome.greenOut()) {
            player.sendMessage(translations.component(player, "effects.green-out"));
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.SLOWNESS,
                    80,
                    1,
                    true,
                    true,
                    true
            ));
        }
    }

    private void applyCosmetic(Player player, AppliedEffect effect) {
        switch (effect.type()) {
            case PARTICLES -> player.getWorld().spawnParticle(
                    Particle.HAPPY_VILLAGER,
                    player.getLocation().add(0, 1.0, 0),
                    18,
                    0.5,
                    0.5,
                    0.5,
                    0.0
            );
            case ALTERED_SOUNDS -> player.playSound(
                    player.getLocation(),
                    Sound.BLOCK_AMETHYST_BLOCK_RESONATE,
                    0.5f,
                    0.7f
            );
            case COUGH -> {
                player.playSound(player.getLocation(), Sound.ENTITY_PANDA_SNEEZE, 0.7f, 0.8f);
                player.sendActionBar(translations.component(player, "effects.negative.cough"));
            }
            case FALSE_FOOTSTEPS -> player.playSound(
                    player.getLocation().add(2.0, 0.0, 2.0),
                    Sound.BLOCK_GRASS_STEP,
                    0.7f,
                    0.6f
            );
            case DISORIENTATION -> player.teleportAsync(
                    player.getLocation().setDirection(
                            player.getLocation().getDirection().rotateAroundY(Math.PI / 5.0)
                    )
            );
            case STRESS_RELIEF -> player.sendActionBar(
                    translations.component(player, "effects.positive.stress-relief")
            );
            default -> {
                // The remaining entries are represented by Bukkit potion effects.
            }
        }
    }

    private PotionEffectType potion(EffectType type) {
        return switch (type) {
            case SPEED -> PotionEffectType.SPEED;
            case HASTE -> PotionEffectType.HASTE;
            case NIGHT_VISION -> PotionEffectType.NIGHT_VISION;
            case RESISTANCE -> PotionEffectType.RESISTANCE;
            case REGENERATION -> PotionEffectType.REGENERATION;
            case HUNGER -> PotionEffectType.HUNGER;
            case SLOWNESS -> PotionEffectType.SLOWNESS;
            case WEAKNESS -> PotionEffectType.WEAKNESS;
            case NAUSEA -> PotionEffectType.NAUSEA;
            case DARKNESS -> PotionEffectType.DARKNESS;
            default -> null;
        };
    }

    private TolerancePolicy tolerancePolicy() {
        YamlConfiguration yaml = configs.get("effects.yml");
        double gain = yaml.getDouble("tolerance-gain-per-use", 3.5);
        return new TolerancePolicy(
                yaml.getBoolean("tolerance.enabled", true),
                yaml.getDouble("tolerance-decay-per-real-hour", 0.75) * 24.0,
                gain,
                yaml.getDouble("tolerance.maximum-gain-per-use", 8.0),
                yaml.getDouble("tolerance.potency-reduction-at-maximum", 0.65),
                yaml.getDouble("tolerance.duration-reduction-at-maximum", 0.50),
                yaml.getDouble("tolerance.negative-risk-reduction-at-maximum", 0.45)
        );
    }

    private EffectPolicy effectPolicy() {
        YamlConfiguration yaml = configs.get("effects.yml");
        return new EffectPolicy(
                Duration.ofSeconds(yaml.getLong("base-duration-seconds", 90L)),
                Duration.ofSeconds(yaml.getLong("maximum-duration-seconds", 300L)),
                1.0,
                yaml.getDouble("green-out-threshold", 1.65)
        );
    }

    private String duration(EffectOutcome outcome) {
        long seconds = outcome.effects().stream()
                .map(AppliedEffect::duration)
                .mapToLong(Duration::toSeconds)
                .max()
                .orElse(0L);
        return seconds + "s";
    }

    private String dominantStrain(ProductBatch batch) {
        return batch.strainComposition().entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");
    }

    private void removeInstance(Player player, UUID instanceId) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack candidate = player.getInventory().getItem(slot);
            Optional<SignedItemData> data = signer.read(candidate);
            if (data.isPresent() && data.get().uniqueInstanceId().equals(instanceId)) {
                player.getInventory().setItem(slot, null);
                return;
            }
        }
    }

    private Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
