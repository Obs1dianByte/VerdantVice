package com.verdantvice.processing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RollingIngredientPolicyTest {
    @Test
    void reportsEachMissingIngredientSeparately() {
        assertEquals(
                ProcessingFailureReason.MISSING_ROLLING_PAPER,
                RollingIngredientPolicy.missing(false, true, true).orElseThrow()
        );
        assertEquals(
                ProcessingFailureReason.MISSING_ROLLING_FILTER,
                RollingIngredientPolicy.missing(true, false, true).orElseThrow()
        );
        assertEquals(
                ProcessingFailureReason.MISSING_ROLLING_PRODUCT,
                RollingIngredientPolicy.missing(true, true, false).orElseThrow()
        );
    }

    @Test
    void completeInputsPassPreflight() {
        assertTrue(RollingIngredientPolicy.missing(true, true, true).isEmpty());
    }
}
