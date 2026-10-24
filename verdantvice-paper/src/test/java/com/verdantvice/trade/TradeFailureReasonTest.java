package com.verdantvice.trade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TradeFailureReasonTest {
    @Test
    void freshBatchHasDedicatedRequiresDryingMessage() {
        assertEquals("trade.fresh-batch-requires-drying",
                TradeFailureReason.FRESH_BATCH_REQUIRES_DRYING.translationKey());
    }

    @Test
    void authenticityFailuresRemainAuthenticityMessages() {
        assertEquals("trade.authentic-only",
                TradeFailureReason.INVALID_SIGNATURE.translationKey());
        assertEquals("trade.authentic-only",
                TradeFailureReason.SIGNATURE_REGISTRY_MISMATCH.translationKey());
    }

    @Test
    void ownerConsumedReservedAndMissingHaveDistinctMessages() {
        assertEquals("errors.wrong-owner", TradeFailureReason.WRONG_OWNER.translationKey());
        assertEquals("trade.already-used",
                TradeFailureReason.ITEM_ALREADY_CONSUMED.translationKey());
        assertEquals("trade.item-reserved", TradeFailureReason.ITEM_RESERVED.translationKey());
        assertEquals("trade.missing-batch", TradeFailureReason.MISSING_BATCH.translationKey());
    }

    @Test
    void domainValidationExceptionRetainsTypedReason() {
        TradeValidationException exception = new TradeValidationException(
                TradeFailureReason.UNSUPPORTED_SALE_STATE);
        assertEquals(TradeFailureReason.UNSUPPORTED_SALE_STATE, exception.reason());
        assertFalse(exception.getMessage().isBlank());
    }
}
