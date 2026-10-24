package com.verdantvice.i18n;

import com.verdantvice.config.ConfigManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.logging.Level;

/**
 * UTF-8 MiniMessage translations with per-player locale selection and a
 * deterministic fallback chain: chosen locale, configured default, en_US, key.
 */
public final class TranslationService {
    private static final String ENGLISH = "en_US";
    private static final Set<String> SUPPORTED = Set.of(
            "pl_PL", "en_US", "de_DE", "es_ES", "cs_CZ", "uk_UA"
    );

    private final JavaPlugin plugin;
    private final ConfigManager configs;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<String, YamlConfiguration> languages = new ConcurrentHashMap<>();
    private final Map<UUID, String> overrides = new ConcurrentHashMap<>();
    private volatile BiFunction<UUID, String, CompletableFuture<Void>> preferenceWriter =
            (uuid, locale) -> CompletableFuture.completedFuture(null);

    public TranslationService(JavaPlugin plugin, ConfigManager configs) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    public void load() {
        Map<String, YamlConfiguration> loaded = new LinkedHashMap<>();
        LanguageFileMerger merger = new LanguageFileMerger();
        for (String resource : ConfigManager.LANGUAGE_FILES) {
            String locale = resource.substring(0, resource.length() - 4);
            File file = configs.dataPath("languages/" + resource).toFile();
            YamlConfiguration bundled = bundledLanguage(resource);
            LanguageFileMerger.MergeReport report = merger.merge(
                    file.toPath(),
                    bundled,
                    configs.dataPath("backups/languages")
            );
            if (report.changed()) {
                plugin.getLogger().log(
                        Level.INFO,
                        "Added {0} missing translation key(s) to {1}; backup: {2}",
                        new Object[] {
                                report.addedKeys().size(),
                                resource,
                                report.backup().getFileName()
                        }
                );
            }
            try {
                YamlConfiguration local = LanguageFileMerger.loadUtf8(file.toPath());
                mergeMissingTranslations(local, bundled);
                loaded.put(locale, local);
            } catch (java.io.IOException
                     | org.bukkit.configuration.InvalidConfigurationException exception) {
                throw new IllegalStateException("Could not reload language " + resource, exception);
            }
        }
        languages.clear();
        languages.putAll(loaded);
        validateAgainstEnglish();
    }

    public void setPreferenceWriter(BiFunction<UUID, String, CompletableFuture<Void>> writer) {
        this.preferenceWriter = Objects.requireNonNull(writer, "writer");
    }

    public void preloadOverride(UUID playerId, String locale) {
        if (locale != null && SUPPORTED.contains(normalize(locale))) {
            overrides.put(playerId, normalize(locale));
        }
    }

    public CompletableFuture<Void> setLocale(Player player, String locale) {
        String normalized = normalize(locale);
        if (!SUPPORTED.contains(normalized)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Unsupported locale: " + locale)
            );
        }
        overrides.put(player.getUniqueId(), normalized);
        return preferenceWriter.apply(player.getUniqueId(), normalized);
    }

    public String locale(CommandSender sender) {
        if (sender instanceof Player player) {
            String selected = overrides.get(player.getUniqueId());
            if (selected != null) {
                return selected;
            }
            if (configs.main().getBoolean("auto-detect-client-language", true)) {
                String detected = normalize(player.locale().toString());
                if (SUPPORTED.contains(detected)) {
                    return detected;
                }
            }
        }
        return configuredDefault();
    }

    public Component component(CommandSender sender, String key) {
        return component(sender, key, Map.of());
    }

    public Component component(CommandSender sender, String key, Map<String, ?> placeholders) {
        return component(locale(sender), key, placeholders);
    }

    public Component component(String locale, String key, Map<String, ?> placeholders) {
        String template = raw(locale, key);
        List<TagResolver> resolvers = new ArrayList<>(placeholders.size());
        placeholders.forEach((name, value) -> {
            if (value instanceof Component component) {
                resolvers.add(Placeholder.component(name, component));
            } else {
                resolvers.add(Placeholder.unparsed(name, Objects.toString(value, "")));
            }
        });
        return miniMessage.deserialize(template, TagResolver.resolver(resolvers));
    }

    public String plain(CommandSender sender, String key) {
        return PlainTextComponentSerializer.plainText().serialize(component(sender, key));
    }

    public String plain(String locale, String key) {
        return PlainTextComponentSerializer.plainText()
                .serialize(component(locale, key, Map.of()));
    }

    public String raw(String locale, String key) {
        String normalized = normalize(locale);
        String value = get(languages.get(normalized), key);
        if (value == null) {
            value = get(languages.get(configuredDefault()), key);
        }
        if (value == null) {
            value = get(languages.get(ENGLISH), key);
        }
        if (value == null) {
            plugin.getLogger().log(Level.WARNING, "Missing translation key: {0}", key);
            return "<red>[" + key + "]</red>";
        }
        return value;
    }

    public Set<String> supportedLocales() {
        return Collections.unmodifiableSet(SUPPORTED);
    }

    public int languageCount() {
        return languages.size();
    }

    /** Returns the locale used for global server-owned entity names. */
    public String serverLocale() {
        return configuredDefault();
    }

    private String configuredDefault() {
        return normalize(configs.main().getString("default-language", ENGLISH));
    }

    private String get(YamlConfiguration language, String key) {
        return language == null ? null : language.getString(key);
    }

    private String normalize(String locale) {
        if (locale == null || locale.isBlank()) {
            return configuredDefaultWithoutRecursion();
        }
        String value = locale.replace('-', '_');
        String[] parts = value.split("_", 3);
        if (parts.length < 2) {
            return value.toLowerCase(Locale.ROOT);
        }
        return parts[0].toLowerCase(Locale.ROOT) + "_"
                + parts[1].toUpperCase(Locale.ROOT);
    }

    private String configuredDefaultWithoutRecursion() {
        String value = configs.main().getString("default-language");
        return value == null || value.isBlank() ? ENGLISH : value.replace('-', '_');
    }

    private YamlConfiguration bundledLanguage(String resource) {
        String path = "languages/" + resource;
        try (InputStream input = plugin.getResource(path)) {
            if (input == null) {
                throw new IllegalStateException("Bundled language resource is missing: " + path);
            }
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Could not read bundled language resource: " + path,
                    exception);
        }
    }

    static void mergeMissingTranslations(
            YamlConfiguration local,
            YamlConfiguration bundled
    ) {
        Objects.requireNonNull(local, "local");
        Objects.requireNonNull(bundled, "bundled");
        for (String key : bundled.getKeys(true)) {
            if (bundled.isString(key) && !local.contains(key)) {
                local.set(key, bundled.getString(key));
            }
        }
    }

    private void validateAgainstEnglish() {
        YamlConfiguration english = languages.get(ENGLISH);
        if (english == null) {
            throw new IllegalStateException("en_US language file is required");
        }
        Set<String> required = english.getKeys(true);
        for (Map.Entry<String, YamlConfiguration> entry : languages.entrySet()) {
            List<String> missing = required.stream()
                    .filter(key -> english.isString(key))
                    .filter(key -> !entry.getValue().isString(key))
                    .toList();
            if (!missing.isEmpty()) {
                throw new IllegalStateException(
                        "Language " + entry.getKey() + " is missing keys: " + missing
                );
            }
        }
    }
}
