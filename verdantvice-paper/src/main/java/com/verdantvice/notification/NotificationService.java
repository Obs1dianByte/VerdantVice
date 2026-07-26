package com.verdantvice.notification;

import com.verdantvice.config.ConfigManager;
import com.verdantvice.i18n.TranslationService;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class NotificationService {
    private final JavaPlugin plugin;
    private final ConfigManager configs;
    private final TranslationService translations;
    private final Map<UUID, Set<NotificationType>> disabled = new ConcurrentHashMap<>();

    public NotificationService(
            JavaPlugin plugin,
            ConfigManager configs,
            TranslationService translations
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.translations = Objects.requireNonNull(translations, "translations");
    }

    public void setEnabled(Player player, NotificationType type, boolean enabled) {
        disabled.compute(player.getUniqueId(), (uuid, current) -> {
            EnumSet<NotificationType> copy = current == null
                    ? EnumSet.noneOf(NotificationType.class)
                    : EnumSet.copyOf(current);
            if (enabled) {
                copy.remove(type);
            } else {
                copy.add(type);
            }
            return Set.copyOf(copy);
        });
    }

    public boolean enabled(Player player, NotificationType type) {
        return !disabled.getOrDefault(player.getUniqueId(), Set.of()).contains(type);
    }

    public void send(Player player, NotificationType type, String translationKey) {
        send(player, type, translationKey, Map.of());
    }

    public void send(
            Player player,
            NotificationType type,
            String translationKey,
            Map<String, ?> placeholders
    ) {
        if (!enabled(player, type)) {
            return;
        }
        Component message = translations.component(player, translationKey, placeholders);
        String channel = configs.get("notifications.yml")
                .getString("channels." + type.name(), "CHAT")
                .toUpperCase(Locale.ROOT);
        switch (channel) {
            case "ACTION_BAR" -> player.sendActionBar(message);
            case "TITLE" -> player.showTitle(Title.title(
                    message,
                    Component.empty(),
                    Title.Times.times(
                            Duration.ofMillis(250),
                            Duration.ofSeconds(3),
                            Duration.ofMillis(500)
                    )
            ));
            case "BOSS_BAR" -> showBossBar(player, message);
            default -> player.sendMessage(message);
        }
        playConfiguredSound(player, type);
    }

    public void sendJoinSummary(Player player, int readyPlants, int pendingProcesses) {
        if (!configs.get("notifications.yml").getBoolean("join-summary", true)) {
            return;
        }
        player.sendMessage(translations.component(
                player,
                "notifications.join-summary",
                Map.of(
                        "count", readyPlants + pendingProcesses,
                        "ready", readyPlants,
                        "processes", pendingProcesses
                )
        ));
    }

    private void showBossBar(Player player, Component message) {
        BossBar bar = BossBar.bossBar(
                message,
                1.0f,
                BossBar.Color.GREEN,
                BossBar.Overlay.PROGRESS
        );
        player.showBossBar(bar);
        plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> player.hideBossBar(bar),
                100L
        );
    }

    private void playConfiguredSound(Player player, NotificationType type) {
        YamlConfiguration yaml = configs.get("notifications.yml");
        if (!yaml.getBoolean("sounds.enabled", true)) {
            return;
        }
        String key = switch (type) {
            case DISEASE, LOW_WATER, UNPAID_WORKER, BLOCKED_WORKER -> "warning";
            default -> "success";
        };
        String configured = yaml.getString("sounds." + key);
        if (configured == null) {
            return;
        }
        try {
            player.playSound(
                    player.getLocation(),
                    Sound.valueOf(configured.toUpperCase(Locale.ROOT)),
                    0.8f,
                    1.0f
            );
        } catch (IllegalArgumentException ignored) {
            plugin.getLogger().warning("Unknown notification sound: " + configured);
        }
    }
}
