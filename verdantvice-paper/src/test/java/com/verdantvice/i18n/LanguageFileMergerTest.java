package com.verdantvice.i18n;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LanguageFileMergerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void oldFileReceivesMissingKeysWithoutOverwritingAdministratorText() throws Exception {
        Path file = temporaryDirectory.resolve("pl_PL.yml");
        Files.writeString(file,
                "# custom comment\nrolling:\n  errors:\n    missing-paper: '<red>Custom text \u0141</red>'\n",
                StandardCharsets.UTF_8);
        YamlConfiguration bundled = new YamlConfiguration();
        bundled.set("rolling.errors.missing-paper", "bundled paper");
        bundled.set("rolling.errors.missing-filter", "bundled filter");
        bundled.set("processing.errors.wrong-batch-state", "bundled state");

        LanguageFileMerger merger = new LanguageFileMerger(Clock.fixed(
                Instant.parse("2026-07-29T12:00:00Z"), ZoneOffset.UTC));
        LanguageFileMerger.MergeReport report = merger.merge(
                file, bundled, temporaryDirectory.resolve("backups/languages"));

        assertTrue(report.changed());
        assertEquals(2, report.addedKeys().size());
        assertNotNull(report.backup());
        assertTrue(Files.exists(report.backup()));
        YamlConfiguration merged = LanguageFileMerger.loadUtf8(file);
        assertEquals("<red>Custom text \u0141</red>",
                merged.getString("rolling.errors.missing-paper"));
        assertEquals("bundled filter", merged.getString("rolling.errors.missing-filter"));
        assertEquals("bundled state",
                merged.getString("processing.errors.wrong-batch-state"));
    }

    @Test
    void mergeWritesUtf8WithoutBomAndBecomesIdempotent() throws Exception {
        Path file = temporaryDirectory.resolve("en_US.yml");
        Files.writeString(file, "meta:\n  locale: en_US\n", StandardCharsets.UTF_8);
        YamlConfiguration bundled = new YamlConfiguration();
        bundled.set("meta.locale", "en_US");
        bundled.set("herbalist.force-created", "Herbalist ✓");
        LanguageFileMerger merger = new LanguageFileMerger(Clock.systemUTC());

        assertTrue(merger.merge(file, bundled,
                temporaryDirectory.resolve("backups/languages")).changed());
        byte[] bytes = Files.readAllBytes(file);
        assertFalse(bytes.length >= 3
                && bytes[0] == (byte) 0xEF
                && bytes[1] == (byte) 0xBB
                && bytes[2] == (byte) 0xBF);
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("✓"));
        assertFalse(merger.merge(file, bundled,
                temporaryDirectory.resolve("backups/languages")).changed());
    }
}
