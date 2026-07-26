package com.verdantvice.storage;

import com.verdantvice.database.DatabaseManager;
import com.verdantvice.genetics.CustomStrainRecord;
import com.verdantvice.genetics.GeneticLine;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class CustomStrainRepository {
    private final DatabaseManager database;

    public CustomStrainRepository(DatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public CompletableFuture<List<CustomStrainRecord>> loadApproved() {
        return database.query(
                """
                SELECT * FROM custom_strains
                WHERE approved = ?
                ORDER BY generation ASC, created_at ASC
                """,
                this::map,
                true
        );
    }

    public CompletableFuture<Optional<CustomStrainRecord>> find(String strainId) {
        return database.queryOne(
                "SELECT * FROM custom_strains WHERE strain_id = ?",
                this::map,
                strainId
        );
    }

    public CompletableFuture<Void> create(CustomStrainRecord record) {
        Objects.requireNonNull(record, "record");
        Instant now = record.updatedAt();
        return database.transaction(session -> {
            PlayerDataRepository.ensure(
                    session,
                    record.line().creatorUuid(),
                    null,
                    record.line().createdAt()
            );
            session.update(
                    """
                    INSERT INTO custom_strains (
                      strain_id, creator_uuid, strain_name, parent_strains_json,
                      dominant_traits_json, colors_json, effects_json, generation,
                      stability, approved, created_at, updated_at, row_version
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, 0)
                    """,
                    record.strainId(),
                    record.line().creatorUuid(),
                    record.line().name(),
                    DomainJson.stringSet(record.line().parentStrainIds()),
                    DomainJson.composition(record.line().dominantTraits()),
                    DomainJson.stringSet(record.line().colors()),
                    DomainJson.stringSet(record.line().effects()),
                    record.line().generation(),
                    record.line().stability(),
                    record.line().createdAt(),
                    now
            );
            return null;
        });
    }

    private CustomStrainRecord map(ResultSet result) throws SQLException {
        String strainId = result.getString("strain_id");
        Instant createdAt = Instant.ofEpochMilli(result.getLong("created_at"));
        Instant updatedAt = Instant.ofEpochMilli(result.getLong("updated_at"));
        GeneticLine line = new GeneticLine(
                UUID.nameUUIDFromBytes(("verdantvice:line:" + strainId)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                result.getString("strain_name"),
                DomainJson.stringSet(result.getString("parent_strains_json")),
                UUID.fromString(result.getString("creator_uuid")),
                result.getInt("generation"),
                DomainJson.composition(result.getString("dominant_traits_json")),
                DomainJson.stringSet(result.getString("colors_json")),
                DomainJson.stringSet(result.getString("effects_json")),
                result.getDouble("stability"),
                createdAt
        );
        return new CustomStrainRecord(strainId, line, updatedAt, result.getLong("row_version"));
    }
}
