package com.verdantvice.common;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Shared fail-fast validation for the domain module.
 */
public final class DomainChecks {
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9][a-z0-9_.-]{1,63}");

    private DomainChecks() {
    }

    public static <T> T required(T value, String name) {
        return Objects.requireNonNull(value, name + " must not be null");
    }

    public static String nonBlank(String value, String name) {
        required(value, name);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return trimmed;
    }

    public static String identifier(String value, String name) {
        String normalized = nonBlank(value, name).toLowerCase(Locale.ROOT);
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must match " + IDENTIFIER.pattern());
        }
        return normalized;
    }

    public static double finite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        return value;
    }

    public static double range(double value, double minimum, double maximum, String name) {
        finite(value, name);
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    public static int range(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    public static double positive(double value, String name) {
        finite(value, name);
        if (value <= 0.0) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
        return value;
    }

    public static Duration positive(Duration value, String name) {
        required(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    public static void ordered(Instant start, Instant end, String description) {
        required(start, description + " start");
        required(end, description + " end");
        if (end.isBefore(start)) {
            throw new IllegalArgumentException(description + " end must not precede start");
        }
    }

    public static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
