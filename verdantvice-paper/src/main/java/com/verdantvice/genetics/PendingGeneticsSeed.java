package com.verdantvice.genetics;

import com.verdantvice.security.SignedItemData;

import java.util.Objects;
import java.util.UUID;

/** Durable receipt: an undelivered child always retains its original signed identity. */
public record PendingGeneticsSeed(SignedItemData data, UUID firstParentId, UUID secondParentId) {
    public PendingGeneticsSeed {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(firstParentId, "firstParentId");
        Objects.requireNonNull(secondParentId, "secondParentId");
    }
}
