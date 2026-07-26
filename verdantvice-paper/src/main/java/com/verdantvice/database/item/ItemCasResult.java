package com.verdantvice.database.item;

import java.util.Optional;

public record ItemCasResult(CasStatus status, ItemRegistryRecord current) {
    public Optional<ItemRegistryRecord> record() {
        return Optional.ofNullable(current);
    }
}
