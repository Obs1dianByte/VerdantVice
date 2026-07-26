package com.verdantvice.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.util.Map;

final class DataSourceFactory {
    private DataSourceFactory() {
    }

    static DataSourceHandle create(DatabaseConfig config) {
        return switch (config.type()) {
            case SQLITE -> sqlite(config);
            case MYSQL, MARIADB -> network(config);
        };
    }

    private static DataSourceHandle sqlite(DatabaseConfig config) {
        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.enforceForeignKeys(true);
        sqlite.setBusyTimeout(Math.toIntExact(config.sqliteBusyTimeout().toMillis()));
        sqlite.setJournalMode(SQLiteConfig.JournalMode.WAL);
        sqlite.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        SQLiteDataSource dataSource = new SQLiteDataSource(sqlite);
        dataSource.setUrl("jdbc:sqlite:" + config.sqliteFile());
        return new DataSourceHandle(dataSource, null);
    }

    private static DataSourceHandle network(DatabaseConfig config) {
        DatabaseConfig.NetworkSettings network = config.activeNetworkSettings();
        DatabaseConfig.PoolSettings pool = config.pool();
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("VerdantVice-" + config.type().name());
        hikari.setDriverClassName(config.type() == DatabaseType.MYSQL
                ? "com.mysql.cj.jdbc.Driver"
                : "org.mariadb.jdbc.Driver");
        String scheme = config.type() == DatabaseType.MYSQL ? "mysql" : "mariadb";
        String host = network.host();
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        hikari.setJdbcUrl("jdbc:" + scheme + "://" + host + ":" + network.port() + "/" + network.database());
        hikari.setUsername(network.username());
        hikari.setPassword(network.password());
        hikari.setMinimumIdle(pool.minimumIdle());
        hikari.setMaximumPoolSize(pool.maximumSize());
        hikari.setConnectionTimeout(pool.connectionTimeout().toMillis());
        hikari.setMaxLifetime(pool.maxLifetime().toMillis());
        hikari.setValidationTimeout(pool.validationTimeout().toMillis());
        hikari.setIdleTimeout(pool.idleTimeout().toMillis());
        hikari.setAutoCommit(true);
        hikari.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        for (Map.Entry<String, String> parameter : network.parameters().entrySet()) {
            hikari.addDataSourceProperty(parameter.getKey(), parameter.getValue());
        }
        HikariDataSource dataSource = new HikariDataSource(hikari);
        return new DataSourceHandle(dataSource, dataSource);
    }

    record DataSourceHandle(DataSource dataSource, AutoCloseable closeable) implements AutoCloseable {
        @Override
        public void close() {
            if (closeable == null) {
                return;
            }
            try {
                closeable.close();
            } catch (Exception exception) {
                throw new DatabaseException("Unable to close database connection pool", exception);
            }
        }
    }
}
