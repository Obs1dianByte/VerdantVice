package com.verdantvice.market;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.verdantvice.config.ConfigManager;
import com.verdantvice.database.DatabaseManager;
import com.verdantvice.database.DatabaseType;
import com.verdantvice.database.SqlNull;
import com.verdantvice.database.SqlSession;
import com.verdantvice.strain.Strain;
import org.bukkit.configuration.file.YamlConfiguration;

import java.sql.JDBCType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public final class MarketRepository {
    private static final String COLUMNS = """
            strain_id, base_price, current_price, supply, demand, sales_volume,
            active_event_json, updated_at, row_version
            """.replace('\n', ' ').strip();

    private final DatabaseManager database;
    private final Function<String, YamlConfiguration> configuration;

    public MarketRepository(DatabaseManager database, ConfigManager configs) {
        this(database, Objects.requireNonNull(configs, "configs")::get);
    }

    MarketRepository(DatabaseManager database, Function<String, YamlConfiguration> configuration) {
        this.database = Objects.requireNonNull(database, "database");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    public CompletableFuture<Void> initialize(Collection<Strain> strains) {
        List<Strain> snapshot = List.copyOf(strains);
        return database.transaction(session -> {
            Instant now = Instant.now();
            String insert = session.databaseType() == DatabaseType.SQLITE
                    ? "INSERT OR IGNORE"
                    : "INSERT IGNORE";
            for (Strain strain : snapshot) {
                session.update(insert + " " + """
                                INTO market_state (
                                  strain_id, base_price, current_price, supply, demand,
                                  sales_volume, active_event_json, updated_at, row_version
                                ) VALUES (?, ?, ?, 1, 1, 0, ?, ?, 0)
                                """,
                        strain.id(), strain.baseMarketPrice(), strain.baseMarketPrice(),
                        SqlNull.of(JDBCType.LONGVARCHAR), now);
            }
            return null;
        });
    }

    public CompletableFuture<Optional<PaperMarketRecord>> find(String strainId) {
        return database.queryOne(
                "SELECT " + COLUMNS + " FROM market_state WHERE strain_id=?",
                this::map,
                strainId);
    }

    /** Adds a newly registered variety without resetting an existing market. */
    public CompletableFuture<PaperMarketRecord> findOrInitialize(Strain strain) {
        Objects.requireNonNull(strain, "strain");
        return find(strain.id()).thenCompose(existing -> {
            if (existing.isPresent()) {
                return CompletableFuture.completedFuture(existing.orElseThrow());
            }
            return initialize(List.of(strain))
                    .thenCompose(ignored -> find(strain.id()))
                    .thenApply(created -> created.orElseThrow(() ->
                            new IllegalStateException("Missing market row for " + strain.id())));
        });
    }

    public CompletableFuture<List<PaperMarketRecord>> all() {
        return database.query(
                "SELECT " + COLUMNS + " FROM market_state ORDER BY strain_id",
                this::map);
    }

    public CompletableFuture<Boolean> save(PaperMarketRecord expected, PaperMarketRecord replacement) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(replacement, "replacement");
        return database.transaction(session -> save(session, expected, replacement));
    }

    public Optional<PaperMarketRecord> find(SqlSession session, String strainId, boolean forUpdate)
            throws SQLException {
        String suffix = forUpdate && session.databaseType().isNetworkDatabase() ? " FOR UPDATE" : "";
        return session.queryOne(
                "SELECT " + COLUMNS + " FROM market_state WHERE strain_id=?" + suffix,
                this::map,
                strainId);
    }

    public boolean save(SqlSession session, PaperMarketRecord expected, PaperMarketRecord replacement)
            throws SQLException {
        int changed = session.update("""
                        UPDATE market_state
                        SET current_price=?, supply=?, demand=?, sales_volume=?,
                            active_event_json=?, updated_at=?, row_version=row_version+1
                        WHERE strain_id=? AND row_version=?
                        """,
                replacement.currentPrice(),
                replacement.state().supplyIndex(),
                replacement.state().demandIndex(),
                replacement.state().serverSalesWeight(),
                eventJson(replacement.event()),
                replacement.state().lastUpdatedAt(),
                replacement.strainId(),
                expected.version());
        return changed == 1;
    }

    private PaperMarketRecord map(ResultSet result) throws SQLException {
        String strain = result.getString("strain_id");
        double basePrice = result.getDouble("base_price");
        double currentPrice = result.getDouble("current_price");
        Instant updated = Instant.ofEpochMilli(result.getLong("updated_at"));
        MarketEventWindow event = parseEvent(result.getString("active_event_json"), updated);
        double minimum = Math.max(0.01, basePrice * minimumMultiplier());
        double maximum = Math.max(minimum, basePrice * maximumMultiplier());
        MarketState state = new MarketState(
                strain,
                Math.max(0.05, result.getDouble("supply")),
                Math.max(0.05, result.getDouble("demand")),
                Math.max(0.0, result.getDouble("sales_volume")),
                event.activeAt(Instant.now()) ? event.type() : MarketEventType.NONE,
                event.activeAt(Instant.now()) ? event.multiplier() : 1.0,
                minimum,
                maximum,
                updated);
        return new PaperMarketRecord(strain, basePrice, currentPrice, state, event,
                result.getLong("row_version"));
    }

    private MarketEventWindow parseEvent(String source, Instant fallback) throws SQLException {
        if (source == null || source.isBlank()) return MarketEventWindow.none(fallback);
        try {
            JsonObject json = JsonParser.parseString(source).getAsJsonObject();
            return new MarketEventWindow(
                    MarketEventType.valueOf(json.get("type").getAsString()),
                    json.get("multiplier").getAsDouble(),
                    Instant.ofEpochMilli(json.get("expiresAt").getAsLong()));
        } catch (RuntimeException exception) {
            throw new SQLException("Malformed active_event_json", exception);
        }
    }

    private static Object eventJson(MarketEventWindow event) {
        if (event.type() == MarketEventType.NONE) {
            return SqlNull.of(JDBCType.LONGVARCHAR);
        }
        JsonObject json = new JsonObject();
        json.addProperty("type", event.type().name());
        json.addProperty("multiplier", event.multiplier());
        json.addProperty("expiresAt", event.expiresAt().toEpochMilli());
        return json.toString();
    }

    public double minimumMultiplier() {
        return configuration.apply("market.yml").getDouble("minimum-multiplier", 0.45);
    }

    public double maximumMultiplier() {
        return configuration.apply("market.yml").getDouble("maximum-multiplier", 2.25);
    }
}
