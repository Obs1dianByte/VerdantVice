package com.verdantvice.database;

import java.util.Locale;

public enum DatabaseType {
    SQLITE,
    MYSQL,
    MARIADB;

    public static DatabaseType parse(String value) {
        if (value == null || value.isBlank()) {
            return SQLITE;
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Unsupported database type '" + value + "'. Expected SQLITE, MYSQL, or MARIADB",
                    exception
            );
        }
    }

    public boolean isNetworkDatabase() {
        return this != SQLITE;
    }
}
