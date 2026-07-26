package com.verdantvice.herbalist;

import java.util.Optional;

public record HerbalistEvaluation(Status status, Optional<HerbalistRecord> herbalist) {
    public HerbalistEvaluation {
        herbalist = java.util.Objects.requireNonNull(herbalist, "herbalist");
    }

    public enum Status {
        CREATED,
        EXISTING,
        NORMAL,
        VILLAGE_LIMIT,
        NOT_FARMER,
        INVALID_ENTITY
    }
}
