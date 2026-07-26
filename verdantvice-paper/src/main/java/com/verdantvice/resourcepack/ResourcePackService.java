package com.verdantvice.resourcepack;

import com.verdantvice.config.ConfigManager;
import com.verdantvice.i18n.TranslationService;
import org.bukkit.entity.Player;

import java.util.HexFormat;
import java.util.Objects;

public final class ResourcePackService {
    private final ConfigManager configs;
    private final TranslationService translations;

    public ResourcePackService(ConfigManager configs, TranslationService translations) {
        this.configs = Objects.requireNonNull(configs, "configs");
        this.translations = Objects.requireNonNull(translations, "translations");
    }

    public boolean enabled() {
        return configs.get("resourcepack.yml").getBoolean("enabled", false);
    }

    public boolean sendOnJoin() {
        return enabled()
                && configs.get("resourcepack.yml").getBoolean("send-on-join", false);
    }

    public SendResult send(Player player) {
        if (!enabled()) {
            return SendResult.DISABLED;
        }
        String url = configs.get("resourcepack.yml").getString("url", "").trim();
        String sha1 = configs.get("resourcepack.yml").getString("sha1", "").trim();
        if (url.isBlank() || sha1.length() != 40) {
            return SendResult.INVALID_CONFIGURATION;
        }
        try {
            byte[] hash = HexFormat.of().parseHex(sha1);
            boolean required = configs.get("resourcepack.yml").getBoolean("required", false);
            String promptKey = configs.get("resourcepack.yml")
                    .getString("prompt-key", "resourcepack.prompt");
            player.setResourcePack(
                    url,
                    hash,
                    translations.component(player, promptKey),
                    required
            );
            return SendResult.SENT;
        } catch (IllegalArgumentException exception) {
            return SendResult.INVALID_CONFIGURATION;
        }
    }

    public enum SendResult {
        SENT,
        DISABLED,
        INVALID_CONFIGURATION
    }
}
