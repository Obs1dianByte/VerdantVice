package com.verdantvice.database.migration;

import java.util.List;

public record MigrationReport(int currentVersion, List<Integer> appliedVersions) {
    public MigrationReport {
        appliedVersions = List.copyOf(appliedVersions);
    }
}
