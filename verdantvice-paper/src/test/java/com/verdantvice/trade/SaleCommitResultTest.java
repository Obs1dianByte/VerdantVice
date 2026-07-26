package com.verdantvice.trade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaleCommitResultTest {
    @Test
    void onlyDurablyCommittedStatusesAreSuccessful() {
        assertTrue(new SaleCommitResult(
                SaleCommitResult.Status.COMMITTED, 12, "").committed());
        assertTrue(new SaleCommitResult(
                SaleCommitResult.Status.ALREADY_COMMITTED, 12, "").committed());
        assertFalse(new SaleCommitResult(
                SaleCommitResult.Status.PRICE_CHANGED, 10, "price-changed").committed());
        assertFalse(new SaleCommitResult(
                SaleCommitResult.Status.REJECTED, 0, "rejected").committed());
    }

    @Test
    void rejectsInvalidResultState() {
        assertThrows(NullPointerException.class, () ->
                new SaleCommitResult(null, 0, ""));
        assertThrows(IllegalArgumentException.class, () ->
                new SaleCommitResult(SaleCommitResult.Status.REJECTED, -1, ""));
    }
}
