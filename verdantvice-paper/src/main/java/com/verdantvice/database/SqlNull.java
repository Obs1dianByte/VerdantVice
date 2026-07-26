package com.verdantvice.database;

import java.sql.JDBCType;
import java.util.Objects;

public record SqlNull(JDBCType type) {
    public SqlNull {
        Objects.requireNonNull(type, "type");
    }

    public static SqlNull of(JDBCType type) {
        return new SqlNull(type);
    }
}
