package com.verdantvice.processing;

import java.util.Optional;

public final class RollingIngredientPolicy {
    private RollingIngredientPolicy() {
    }

    public static Optional<ProcessingFailureReason> missing(
            boolean hasPaper,
            boolean hasFilter,
            boolean hasProduct
    ) {
        if (!hasPaper) {
            return Optional.of(ProcessingFailureReason.MISSING_ROLLING_PAPER);
        }
        if (!hasFilter) {
            return Optional.of(ProcessingFailureReason.MISSING_ROLLING_FILTER);
        }
        if (!hasProduct) {
            return Optional.of(ProcessingFailureReason.MISSING_ROLLING_PRODUCT);
        }
        return Optional.empty();
    }
}
