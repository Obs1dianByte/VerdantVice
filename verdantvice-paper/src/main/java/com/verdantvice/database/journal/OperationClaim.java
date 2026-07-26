package com.verdantvice.database.journal;

import java.util.Optional;
import java.util.UUID;

public record OperationClaim(
        ClaimStatus status,
        UUID claimToken,
        String resultJson,
        long version
) {
    public Optional<UUID> token() {
        return Optional.ofNullable(claimToken);
    }

    public Optional<String> result() {
        return Optional.ofNullable(resultJson);
    }
}
