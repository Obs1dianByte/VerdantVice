package com.verdantvice.database.migration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public record SchemaMigration(int version, String description, List<String> statements) {
    public SchemaMigration {
        if (version < 1) {
            throw new IllegalArgumentException("Migration version must be positive");
        }
        description = Objects.requireNonNull(description, "description").strip();
        if (description.isEmpty() || description.length() > 200) {
            throw new IllegalArgumentException("Migration description must contain 1 to 200 characters");
        }
        statements = List.copyOf(Objects.requireNonNull(statements, "statements"));
        if (statements.isEmpty() || statements.stream().anyMatch(sql -> sql == null || sql.isBlank())) {
            throw new IllegalArgumentException("Migration must contain non-blank statements");
        }
    }

    public String checksum() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Integer.toString(version).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(description.getBytes(StandardCharsets.UTF_8));
            for (String statement : statements) {
                digest.update((byte) '\n');
                digest.update(statement.replace("\r\n", "\n").strip().getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
