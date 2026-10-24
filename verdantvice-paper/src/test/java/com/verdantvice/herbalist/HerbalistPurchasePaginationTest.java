package com.verdantvice.herbalist;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HerbalistPurchasePaginationTest {
    @Test
    void everyOfferRemainsReachableWithHundredsOfCustomVarieties() {
        int count = 235;
        List<Integer> visited = new ArrayList<>();
        int pageIndex = 0;
        while (true) {
            var page = HerbalistMenuService.buyPage(count, pageIndex);
            assertTrue(page.toExclusive() - page.fromInclusive() <= 45);
            assertEquals(pageIndex > 0, page.hasPrevious());
            for (int index = page.fromInclusive(); index < page.toExclusive(); index++) {
                visited.add(index);
            }
            if (!page.hasNext()) break;
            pageIndex++;
        }
        assertEquals(6, pageIndex + 1);
        assertEquals(java.util.stream.IntStream.range(0, count).boxed().toList(), visited);
    }

    @Test
    void exactlyFullPagesDoNotLeaveEmptyTrailingPages() {
        var page = HerbalistMenuService.buyPage(90, 1);
        assertEquals(2, page.pageCount());
        assertEquals(45, page.fromInclusive());
        assertEquals(90, page.toExclusive());
        assertTrue(page.hasPrevious());
        assertFalse(page.hasNext());
    }

    @Test
    void ordinaryDefaultShopNeedsNoNavigation() {
        var page = HerbalistMenuService.buyPage(35, 0);
        assertEquals(0, page.index());
        assertEquals(35, page.toExclusive());
        assertFalse(page.hasPrevious());
        assertFalse(page.hasNext());
    }

    @Test
    void staleOrExtremeRequestsAreClampedAndEmptyCatalogRemainsSafe() {
        assertEquals(0, HerbalistMenuService.buyPage(235, Integer.MIN_VALUE).index());
        assertEquals(5, HerbalistMenuService.buyPage(235, Integer.MAX_VALUE).index());
        var empty = HerbalistMenuService.buyPage(0, 8);
        assertEquals(0, empty.fromInclusive());
        assertEquals(0, empty.toExclusive());
        assertFalse(empty.hasPrevious());
        assertFalse(empty.hasNext());
        var huge = HerbalistMenuService.buyPage(Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, huge.toExclusive());
        assertThrows(IllegalArgumentException.class, () -> HerbalistMenuService.buyPage(-1, 0));
    }

    @Test
    void navigationActionsAcceptOnlyValidNonnegativeIntegerPages() {
        assertEquals(0, HerbalistMarketManager.buyPageFromAction("open:buy:0").orElseThrow());
        assertEquals(123, HerbalistMarketManager.buyPageFromAction("open:buy:123").orElseThrow());
        for (String malformed : List.of("open:buy", "open:buy:", "open:buy:-1",
                "open:buy:2147483648", "open:buy:abc", "open:buy:1:extra", "open:market:1")) {
            assertTrue(HerbalistMarketManager.buyPageFromAction(malformed).isEmpty(), malformed);
        }
        assertTrue(HerbalistMarketManager.buyPageFromAction(null).isEmpty());
    }

    @Test
    void purchasesReopenThePageContainingTheSelectedOffer() {
        assertEquals(0, HerbalistMenuService.buyPageForIndex(0));
        assertEquals(0, HerbalistMenuService.buyPageForIndex(44));
        assertEquals(1, HerbalistMenuService.buyPageForIndex(45));
        assertEquals(5, HerbalistMenuService.buyPageForIndex(234));
        assertEquals(0, HerbalistMenuService.buyPageForIndex(-1));
    }
}
