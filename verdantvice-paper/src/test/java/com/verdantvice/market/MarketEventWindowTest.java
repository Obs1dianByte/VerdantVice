package com.verdantvice.market;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketEventWindowTest {
    @Test
    void eventIsActiveOnlyStrictlyBeforeItsDeadline() {
        Instant deadline = Instant.parse("2030-01-01T12:00:00Z");
        MarketEventWindow event = new MarketEventWindow(
                MarketEventType.HIGH_DEMAND, 1.2, deadline);

        assertTrue(event.activeAt(deadline.minusMillis(1)));
        assertFalse(event.activeAt(deadline));
        assertFalse(event.activeAt(deadline.plusMillis(1)));
    }

    @Test
    void noneWindowNeverBecomesActive() {
        Instant now = Instant.parse("2030-01-01T12:00:00Z");
        assertFalse(MarketEventWindow.none(now).activeAt(now.minusSeconds(1)));
    }

    @Test
    void rejectsUnsafeEventMultipliers() {
        Instant now = Instant.parse("2030-01-01T12:00:00Z");
        assertThrows(IllegalArgumentException.class, () ->
                new MarketEventWindow(MarketEventType.HIGH_DEMAND, 0.09, now));
        assertThrows(IllegalArgumentException.class, () ->
                new MarketEventWindow(MarketEventType.HIGH_DEMAND, 5.01, now));
    }
}
