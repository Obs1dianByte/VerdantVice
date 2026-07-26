package com.verdantvice.storage;

import com.verdantvice.database.DatabaseManager;
import com.verdantvice.profile.PlayerProfile;
import com.verdantvice.profile.SkillProgress;
import com.verdantvice.profile.SkillTree;
import com.verdantvice.tolerance.ToleranceState;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class PlayerProfileRepository {
    private static final String GLOBAL_TOLERANCE = "GLOBAL";

    private final DatabaseManager database;

    public PlayerProfileRepository(DatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public CompletableFuture<PlayerProfile> load(UUID playerUuid, String fallbackLocale) {
        Instant now = Instant.now();
        return database.transaction(session -> {
            PlayerDataRepository.ensure(session, playerUuid, fallbackLocale, now);
            PlayerRow player = session.queryOne(
                    "SELECT locale, reputation, created_at, last_seen_at "
                            + "FROM players WHERE player_uuid=?",
                    PlayerProfileRepository::player,
                    playerUuid
            ).orElseThrow(() -> new SQLException("Player row disappeared"));
            EnumMap<SkillTree, SkillProgress> skills = new EnumMap<>(SkillTree.class);
            session.query(
                    "SELECT skill_type, skill_level, experience "
                            + "FROM player_skills WHERE player_uuid=?",
                    result -> {
                        SkillRow row = skill(result);
                        skills.put(row.tree(), row.progress());
                        return row;
                    },
                    playerUuid
            );
            ToleranceState tolerance = session.queryOne(
                    "SELECT tolerance, last_used_at, decay_checkpoint_at "
                            + "FROM player_tolerance WHERE player_uuid=? AND effect_id=?",
                    result -> tolerance(result, playerUuid),
                    playerUuid,
                    GLOBAL_TOLERANCE
            ).orElseGet(() -> ToleranceState.initial(playerUuid, now));
            return new PlayerProfile(
                    playerUuid,
                    Optional.ofNullable(player.locale()).orElse(fallbackLocale),
                    Math.max(0.0, Math.min(100.0, player.reputation())),
                    skills,
                    tolerance,
                    player.createdAt(),
                    player.lastSeenAt()
            );
        });
    }

    public CompletableFuture<Void> save(PlayerProfile profile) {
        Objects.requireNonNull(profile, "profile");
        Instant now = Instant.now();
        return database.transaction(session -> {
            PlayerDataRepository.ensure(session, profile.playerUuid(), profile.locale(), now);
            session.update(
                    "UPDATE players SET locale=?, reputation=?, last_seen_at=?, updated_at=?, "
                            + "row_version=row_version+1 WHERE player_uuid=?",
                    profile.locale(),
                    (int) Math.round(profile.reputation()),
                    profile.lastSeenAt(),
                    now,
                    profile.playerUuid()
            );
            for (Map.Entry<SkillTree, SkillProgress> entry : profile.skills().entrySet()) {
                int changed = session.update(
                        "UPDATE player_skills SET skill_level=?, experience=?, updated_at=?, "
                                + "row_version=row_version+1 WHERE player_uuid=? AND skill_type=?",
                        entry.getValue().level(),
                        entry.getValue().experience(),
                        now,
                        profile.playerUuid(),
                        entry.getKey()
                );
                if (changed == 0) {
                    session.update(
                            "INSERT INTO player_skills "
                                    + "(player_uuid, skill_type, skill_level, experience, "
                                    + "updated_at, row_version) VALUES (?, ?, ?, ?, ?, 0)",
                            profile.playerUuid(),
                            entry.getKey(),
                            entry.getValue().level(),
                            entry.getValue().experience(),
                            now
                    );
                }
            }
            ToleranceState tolerance = profile.tolerance();
            int changed = session.update(
                    "UPDATE player_tolerance SET tolerance=?, last_used_at=?, "
                            + "decay_checkpoint_at=?, row_version=row_version+1 "
                            + "WHERE player_uuid=? AND effect_id=?",
                    tolerance.level(),
                    tolerance.lastUsedAt().orElse(null),
                    tolerance.lastUpdatedAt(),
                    profile.playerUuid(),
                    GLOBAL_TOLERANCE
            );
            if (changed == 0) {
                session.update(
                        "INSERT INTO player_tolerance "
                                + "(player_uuid, effect_id, tolerance, last_used_at, "
                                + "decay_checkpoint_at, metadata_json, row_version) "
                                + "VALUES (?, ?, ?, ?, ?, NULL, 0)",
                        profile.playerUuid(),
                        GLOBAL_TOLERANCE,
                        tolerance.level(),
                        tolerance.lastUsedAt().orElse(null),
                        tolerance.lastUpdatedAt()
                );
            }
            return null;
        });
    }

    public CompletableFuture<Void> delete(UUID playerUuid) {
        return database.transaction(session -> {
            session.update("DELETE FROM players WHERE player_uuid=?", playerUuid);
            return null;
        });
    }

    private static PlayerRow player(ResultSet result) throws SQLException {
        return new PlayerRow(
                result.getString("locale"),
                result.getInt("reputation"),
                Instant.ofEpochMilli(result.getLong("created_at")),
                Instant.ofEpochMilli(result.getLong("last_seen_at"))
        );
    }

    private static SkillRow skill(ResultSet result) throws SQLException {
        return new SkillRow(
                SkillTree.valueOf(result.getString("skill_type")),
                new SkillProgress(
                        result.getInt("skill_level"),
                        result.getLong("experience")
                )
        );
    }

    private static ToleranceState tolerance(ResultSet result, UUID playerUuid)
            throws SQLException {
        long lastUsed = result.getLong("last_used_at");
        Optional<Instant> used = result.wasNull()
                ? Optional.empty()
                : Optional.of(Instant.ofEpochMilli(lastUsed));
        return new ToleranceState(
                playerUuid,
                result.getDouble("tolerance"),
                used,
                Instant.ofEpochMilli(result.getLong("decay_checkpoint_at"))
        );
    }

    private record PlayerRow(
            String locale,
            int reputation,
            Instant createdAt,
            Instant lastSeenAt
    ) {
    }

    private record SkillRow(SkillTree tree, SkillProgress progress) {
    }
}
