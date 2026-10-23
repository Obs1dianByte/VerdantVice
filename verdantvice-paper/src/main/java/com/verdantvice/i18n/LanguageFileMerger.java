package com.verdantvice.i18n;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Adds only missing bundled translation values to an existing UTF-8 YAML file.
 * Existing administrator values remain untouched and every mutation is backed up.
 */
public final class LanguageFileMerger {
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter
            .ofPattern("uuuuMMdd-HHmmss-SSS")
            .withZone(ZoneOffset.UTC);

    private final Clock clock;

    public LanguageFileMerger() {
        this(Clock.systemUTC());
    }

    LanguageFileMerger(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public MergeReport merge(
            Path localFile,
            YamlConfiguration bundled,
            Path backupDirectory
    ) {
        Objects.requireNonNull(localFile, "localFile");
        Objects.requireNonNull(bundled, "bundled");
        Objects.requireNonNull(backupDirectory, "backupDirectory");
        try {
            YamlConfiguration local = loadUtf8(localFile);
            List<String> missing = bundled.getKeys(true).stream()
                    .filter(bundled::isString)
                    .filter(key -> !local.contains(key))
                    .sorted()
                    .toList();
            if (missing.isEmpty()) {
                return new MergeReport(false, List.of(), null);
            }
            Files.createDirectories(backupDirectory);
            Path backup = uniqueBackup(backupDirectory, localFile.getFileName().toString());
            Files.copy(localFile, backup, StandardCopyOption.COPY_ATTRIBUTES);
            for (String key : missing) {
                local.set(key, bundled.getString(key));
            }
            writeUtf8Atomically(localFile, local.saveToString());
            return new MergeReport(true, missing, backup);
        } catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalStateException(
                    "Could not merge language file " + localFile.getFileName(), exception);
        }
    }

    static YamlConfiguration loadUtf8(Path path)
            throws IOException, InvalidConfigurationException {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.options().parseComments(true);
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            configuration.load(reader);
        }
        return configuration;
    }

    private Path uniqueBackup(Path directory, String sourceName) throws IOException {
        String base = sourceName.replaceFirst("\\.yml$", "")
                + '-' + BACKUP_TIME.format(Instant.now(clock));
        Path candidate = directory.resolve(base + ".yml");
        int suffix = 1;
        while (Files.exists(candidate)) {
            candidate = directory.resolve(base + '-' + suffix++ + ".yml");
        }
        return candidate;
    }

    private void writeUtf8Atomically(Path target, String value) throws IOException {
        Path temporary = Files.createTempFile(
                target.getParent(), target.getFileName().toString(), ".merge.tmp");
        try {
            Files.writeString(temporary, value, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public record MergeReport(boolean changed, List<String> addedKeys, Path backup) {
        public MergeReport {
            addedKeys = List.copyOf(new ArrayList<>(addedKeys));
        }
    }
}
