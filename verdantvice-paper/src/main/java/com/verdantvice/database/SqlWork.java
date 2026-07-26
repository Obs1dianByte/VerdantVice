package com.verdantvice.database;

import java.sql.SQLException;

@FunctionalInterface
public interface SqlWork<T> {
    T execute(SqlSession session) throws SQLException;
}
