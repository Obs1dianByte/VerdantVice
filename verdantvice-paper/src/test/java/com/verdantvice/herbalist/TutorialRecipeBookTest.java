package com.verdantvice.herbalist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

final class TutorialRecipeBookTest {
    @Test
    void recipesUsesDedicatedFivePageBookInsteadOfPerfectBatch() {
        TutorialBookService.BookType recipes = TutorialBookService.BookType.RECIPES;
        assertNotEquals(TutorialBookService.BookType.PERFECT_BATCH, recipes);
        assertEquals("recipes", recipes.slug());
        assertEquals(5, recipes.pages());
    }
}
