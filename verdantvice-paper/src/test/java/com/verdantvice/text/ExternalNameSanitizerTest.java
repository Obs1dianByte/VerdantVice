package com.verdantvice.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExternalNameSanitizerTest {
    @Test
    void legacySectionAndAmpersandFormattingAreRemoved() {
        assertEquals("Herbalist", ExternalNameSanitizer.name("§2Herbalist", "Worker", 64));
        assertEquals("Herbalist", ExternalNameSanitizer.name("&2Herbalist", "Worker", 64));
        assertEquals("Herbalist", ExternalNameSanitizer.name("\u00b4\u2510\u017b2Herbalist", "Worker", 64));
    }

    @Test
    void validPolishCzechAndUkrainianUnicodeIsPreserved() {
        String value = "Herbalist \u017dlu\u0165ou\u010dk\u00fd \u0422\u0440\u0430\u0432\u043d\u0438\u043a \u0141ukasz";
        assertEquals(value, ExternalNameSanitizer.name(value, "Worker", 64));
    }

    @Test
    void tagsJsonControlsAndLengthAreSanitizedWithoutLegacyOutput() {
        String source = "{\"text\":\"<green>§2Herbalist</green>\",\"extra\":[{\"text\":\"\\u0000 Name\"}]}";
        String result = ExternalNameSanitizer.name(source, "Worker", 12);
        assertEquals("Herbalist Na", result);
        assertFalse(result.contains("§"));
        assertFalse(result.contains("<"));
        assertTrue(result.codePointCount(0, result.length()) <= 12);
    }
}
