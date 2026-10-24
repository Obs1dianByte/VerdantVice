package com.verdantvice.storage;

import com.verdantvice.database.DatabaseManager;
import com.verdantvice.database.SqlSession;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class PlayerDataRepository {
    private final DatabaseManager database;

    public PlayerDataRepository(DatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public CompletableFuture<Void> ensure(UUID playerUuid, String detectedLocale) {
        Instant now = Instant.now();
        return database.transaction(session -> {
            ensure(session, playerUuid, detectedLocale, now);
            return null;
        });
    }

    public CompletableFuture<Void> setLocale(UUID playerUuid, String locale) {
        Instant now = Instant.now();
        return database.transaction(session -> {
            ensure(session, playerUuid, locale, now);
            session.update(
                    "UPDATE players SET locale=?, updated_at=?, row_version=row_version+1 "
                            + "WHERE player_uuid=?",
                    locale,
                    now,
                    playerUuid
            );
            return null;
        });
    }

    public CompletableFuture<Optional<String>> locale(UUID playerUuid) {
        return database.queryOne(
                "SELECT locale FROM players WHERE player_uuid=?",
                result -> new NullableLocale(result.getString("locale")),
                playerUuid
        ).thenApply(row -> row.flatMap(value -> Optional.ofNullable(value.value())));
    }

    public CompletableFuture<PlayerSummary> summary(UUID playerUuid) {
        return database.queryOne(
                "SELECT reputation, locale, created_at, last_seen_at "
                        + "FROM players WHERE player_uuid=?",
                result -> new PlayerSummary(
                        result.getInt("reputation"),
                        result.getString("locale"),
                        Instant.ofEpochMilli(result.getLong("created_at")),
                        Instant.ofEpochMilli(result.getLong("last_seen_at"))
                ),
                playerUuid
        ).thenApply(optional -> optional.orElseGet(() ->
                new PlayerSummary(0, null, Instant.now(), Instant.now())));
    }

    public CompletableFuture<Void> markSeen(UUID playerUuid, String detectedLocale) {
        Instant now = Instant.now();
        return database.transaction(session -> {
            ensure(session, playerUuid, detectedLocale, now);
            session.update(
                    "UPDATE players SET last_seen_at=?, updated_at=?, row_version=row_version+1 "
                            + "WHERE player_uuid=?",
                    now,
                    now,
                    playerUuid
            );
            return null;
        });
    }

    public static void ensure(
            SqlSession session,
            UUID playerUuid,
            String locale,
            Instant now
    ) throws SQLException {
        if (session.exists(
                "SELECT 1 FROM players WHERE player_uuid=?",
                playerUuid
        )) {
            return;
        }
        session.update(
                "INSERT INTO players (player_uuid, locale, reputation, emerald_balance, "
                        + "settings_json, created_at, updated_at, last_seen_at, row_version) "
                        + "VALUES (?, ?, 0, 0, NULL, ?, ?, ?, 0)",
                playerUuid,
                locale,
                now,
                now,
                now
        );
    }

    public record PlayerSummary(
            int reputation,
            String locale,
            Instant createdAt,
            Instant lastSeenAt
    ) {
    }

    private record NullableLocale(String value) {
    }
}
