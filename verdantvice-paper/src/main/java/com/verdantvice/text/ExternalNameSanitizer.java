package com.verdantvice.text;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.text.Normalizer;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Sanitizes untrusted display names before they enter persistence or MiniMessage placeholders.
 */
public final class ExternalNameSanitizer {
    private static final Pattern LEGACY = Pattern.compile("(?i)(?:§|&)[0-9A-FK-ORX]");
    private static final Pattern MINI_MESSAGE_TAG = Pattern.compile("<[^>]{1,128}>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private ExternalNameSanitizer() {
    }

    public static String name(String source, String fallback, int maximumCodePoints) {
        if (maximumCodePoints < 1) {
            throw new IllegalArgumentException("maximumCodePoints must be positive");
        }
        String cleaned = clean(Objects.toString(source, ""));
        if (cleaned.isBlank()) {
            cleaned = clean(Objects.toString(fallback, "Worker"));
        }
        if (cleaned.isBlank()) {
            cleaned = "Worker";
        }
        int count = cleaned.codePointCount(0, cleaned.length());
        if (count > maximumCodePoints) {
            int end = cleaned.offsetByCodePoints(0, maximumCodePoints);
            cleaned = cleaned.substring(0, end).strip();
        }
        return cleaned;
    }

    public static boolean requiresSanitization(String source) {
        String value = Objects.toString(source, "");
        return !value.equals(name(value, "Worker", 64));
    }

    private static String clean(String source) {
        String value = repairMojibake(source);
        value = plainJson(value);
        value = LEGACY.matcher(value).replaceAll("");
        value = MINI_MESSAGE_TAG.matcher(value).replaceAll("");
        StringBuilder safe = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            if (!Character.isISOControl(codePoint)) {
                safe.appendCodePoint(codePoint);
            } else if (Character.isWhitespace(codePoint)) {
                safe.append(' ');
            }
        });
        String normalized = Normalizer.normalize(safe, Normalizer.Form.NFC);
        return WHITESPACE.matcher(normalized).replaceAll(" ").strip();
    }

    private static String repairMojibake(String source) {
        return source
                .replace("Ã‚Â§", "§")
                .replace("Â§", "§")
                .replace("┬º", "§")
                .replace("\u00b4\u2510\u017b", "§");
    }

    private static String plainJson(String source) {
        String stripped = source.strip();
        if (!(stripped.startsWith("{") || stripped.startsWith("["))) {
            return source;
        }
        try {
            StringBuilder output = new StringBuilder();
            appendJsonText(JsonParser.parseString(stripped), output);
            return output.isEmpty() ? source : output.toString();
        } catch (RuntimeException ignored) {
            return source;
        }
    }

    private static void appendJsonText(JsonElement element, StringBuilder output) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            output.append(element.getAsString());
            return;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement child : array) {
                appendJsonText(child, output);
            }
            return;
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("text")) {
                appendJsonText(object.get("text"), output);
            }
            if (object.has("extra")) {
                appendJsonText(object.get("extra"), output);
            }
        }
    }
}
