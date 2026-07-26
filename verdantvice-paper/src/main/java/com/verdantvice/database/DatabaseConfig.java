package com.verdantvice.database;

import org.bukkit.configuration.ConfigurationSection;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record DatabaseConfig(
        DatabaseType type,
        Path dataDirectory,
        Path sqliteFile,
        Path backupDirectory,
        boolean backupOnStart,
        Duration backupInterval,
        int backupRetention,
        Duration sqliteBusyTimeout,
        NetworkSettings mysql,
        NetworkSettings mariadb,
        PoolSettings pool,
        RetrySettings retry,
        int executorThreads,
        int executorQueueCapacity,
        Duration shutdownTimeout
) {
    public DatabaseConfig {
        type = Objects.requireNonNull(type, "type");
        dataDirectory = normalize(Objects.requireNonNull(dataDirectory, "dataDirectory"));
        sqliteFile = normalize(Objects.requireNonNull(sqliteFile, "sqliteFile"));
        backupDirectory = normalize(Objects.requireNonNull(backupDirectory, "backupDirectory"));
        backupInterval = positiveOrZero(backupInterval, "backupInterval");
        sqliteBusyTimeout = positive(sqliteBusyTimeout, "sqliteBusyTimeout");
        mysql = Objects.requireNonNull(mysql, "mysql");
        mariadb = Objects.requireNonNull(mariadb, "mariadb");
        pool = Objects.requireNonNull(pool, "pool");
        retry = Objects.requireNonNull(retry, "retry");
        shutdownTimeout = positive(shutdownTimeout, "shutdownTimeout");
        if (!sqliteFile.startsWith(dataDirectory)) {
            throw new IllegalArgumentException("SQLite file must be inside the plugin data directory");
        }
        if (!backupDirectory.startsWith(dataDirectory)) {
            throw new IllegalArgumentException("Backup directory must be inside the plugin data directory");
        }
        if (backupRetention < 1 || backupRetention > 10_000) {
            throw new IllegalArgumentException("backupRetention must be between 1 and 10000");
        }
        if (executorThreads < 1 || executorThreads > 128) {
            throw new IllegalArgumentException("executorThreads must be between 1 and 128");
        }
        if (executorQueueCapacity < 16 || executorQueueCapacity > 1_000_000) {
            throw new IllegalArgumentException("executorQueueCapacity must be between 16 and 1000000");
        }
    }

    public static DatabaseConfig fromBukkit(ConfigurationSection section, Path dataDirectory) {
        Objects.requireNonNull(section, "section");
        Path root = normalize(Objects.requireNonNull(dataDirectory, "dataDirectory"));
        DatabaseType type = DatabaseType.parse(section.getString("type", "SQLITE"));
        Path sqliteFile = resolveInside(root, section.getString("sqlite.file", "data/verdantvice.db"));
        Path backups = resolveInside(root, section.getString("sqlite.backup-directory", "backups"));

        PoolSettings pool = new PoolSettings(
                section.getInt("pool.minimum-idle", 1),
                section.getInt("pool.maximum-size", 8),
                Duration.ofMillis(section.getLong("pool.connection-timeout-millis", 10_000L)),
                Duration.ofMillis(section.getLong("pool.max-lifetime-millis", 1_800_000L)),
                Duration.ofMillis(section.getLong("pool.validation-timeout-millis", 5_000L)),
                Duration.ofMillis(section.getLong("pool.idle-timeout-millis", 600_000L))
        );
        RetrySettings retry = new RetrySettings(
                section.getInt("retry.attempts", 3),
                Duration.ofMillis(section.getLong("retry.initial-delay-millis", 200L)),
                Duration.ofMillis(section.getLong("retry.maximum-delay-millis", 2_000L))
        );
        NetworkSettings mysql = network(section, "mysql", 3306);
        NetworkSettings mariadb = network(section, "mariadb", 3306);
        int defaultThreads = type == DatabaseType.SQLITE ? 1 : pool.maximumSize();

        return new DatabaseConfig(
                type,
                root,
                sqliteFile,
                backups,
                section.getBoolean("sqlite.backup-on-start", true),
                Duration.ofHours(section.getLong("sqlite.backup-interval-hours", 12L)),
                section.getInt("sqlite.max-backups", 14),
                Duration.ofMillis(section.getLong("sqlite.busy-timeout-millis", 10_000L)),
                mysql,
                mariadb,
                pool,
                retry,
                section.getInt("executor.threads", defaultThreads),
                section.getInt("executor.queue-capacity", 2_048),
                Duration.ofMillis(section.getLong("shutdown-timeout-millis", 15_000L))
        );
    }

    public NetworkSettings activeNetworkSettings() {
        return switch (type) {
            case MYSQL -> mysql;
            case MARIADB -> mariadb;
            case SQLITE -> throw new IllegalStateException("SQLite does not use network settings");
        };
    }

    private static NetworkSettings network(ConfigurationSection root, String path, int defaultPort) {
        return new NetworkSettings(
                root.getString(path + ".host", "localhost"),
                root.getInt(path + ".port", defaultPort),
                root.getString(path + ".database", "verdantvice"),
                root.getString(path + ".username", "verdantvice"),
                root.getString(path + ".password", ""),
                parseParameters(root.getString(path + ".parameters", ""))
        );
    }

    private static Map<String, String> parseParameters(String source) {
        if (source == null || source.isBlank()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String pair : source.split("&")) {
            int separator = pair.indexOf('=');
            String key = separator < 0 ? pair.strip() : pair.substring(0, separator).strip();
            String value = separator < 0 ? "true" : pair.substring(separator + 1).strip();
            if (!key.matches("[A-Za-z][A-Za-z0-9_.-]{0,127}")) {
                throw new IllegalArgumentException("Invalid JDBC parameter name: " + key);
            }
            if (value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("JDBC parameter contains a NUL character: " + key);
            }
            result.put(key, value);
        }
        return Map.copyOf(result);
    }

    private static Path resolveInside(Path root, String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            throw new IllegalArgumentException("Configured path must not be blank");
        }
        Path candidate = Path.of(configuredPath);
        Path resolved = normalize(candidate.isAbsolute() ? candidate : root.resolve(candidate));
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Configured path escapes the plugin data directory: " + configuredPath);
        }
        return resolved;
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static Duration positive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private static Duration positiveOrZero(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return duration;
    }

    public record NetworkSettings(
            String host,
            int port,
            String database,
            String username,
            String password,
            Map<String, String> parameters
    ) {
        public NetworkSettings {
            host = requireJdbcSegment(host, "host", true);
            database = requireJdbcSegment(database, "database", false);
            username = Objects.requireNonNull(username, "username");
            password = Objects.requireNonNull(password, "password");
            parameters = Map.copyOf(Objects.requireNonNull(parameters, "parameters"));
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("port must be between 1 and 65535");
            }
        }

        private static String requireJdbcSegment(String value, String name, boolean allowColon) {
            Objects.requireNonNull(value, name);
            String normalized = value.strip();
            if (normalized.isEmpty()
                    || normalized.indexOf('/') >= 0
                    || normalized.indexOf('?') >= 0
                    || normalized.indexOf('#') >= 0
                    || normalized.indexOf('&') >= 0
                    || (!allowColon && normalized.indexOf(':') >= 0)) {
                throw new IllegalArgumentException("Invalid JDBC " + name);
            }
            return normalized;
        }
    }

    public record PoolSettings(
            int minimumIdle,
            int maximumSize,
            Duration connectionTimeout,
            Duration maxLifetime,
            Duration validationTimeout,
            Duration idleTimeout
    ) {
        public PoolSettings {
            connectionTimeout = positive(connectionTimeout, "connectionTimeout");
            maxLifetime = positive(maxLifetime, "maxLifetime");
            validationTimeout = positive(validationTimeout, "validationTimeout");
            idleTimeout = positive(idleTimeout, "idleTimeout");
            if (minimumIdle < 0 || maximumSize < 1 || minimumIdle > maximumSize) {
                throw new IllegalArgumentException("Pool sizes must satisfy 0 <= minimumIdle <= maximumSize");
            }
        }
    }

    public record RetrySettings(int attempts, Duration initialDelay, Duration maximumDelay) {
        public RetrySettings {
            initialDelay = positiveOrZero(initialDelay, "initialDelay");
            maximumDelay = positive(maximumDelay, "maximumDelay");
            if (attempts < 1 || attempts > 20) {
                throw new IllegalArgumentException("retry attempts must be between 1 and 20");
            }
            if (maximumDelay.compareTo(initialDelay) < 0) {
                throw new IllegalArgumentException("maximumDelay must not be shorter than initialDelay");
            }
        }
    }
}
