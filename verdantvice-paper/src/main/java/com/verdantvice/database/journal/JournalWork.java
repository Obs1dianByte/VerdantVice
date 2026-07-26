package com.verdantvice.database.journal;

import com.verdantvice.database.SqlSession;

import java.sql.SQLException;

@FunctionalInterface
public interface JournalWork {
    String execute(SqlSession transaction) throws SQLException;
}
