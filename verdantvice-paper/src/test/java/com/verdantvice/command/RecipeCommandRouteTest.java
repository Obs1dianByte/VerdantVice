package com.verdantvice.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RecipeCommandRouteTest {
    @Test
    void recipesOpensDedicatedGuide() {
        assertEquals(
                RecipeCommandRoute.Action.OPEN_GUIDE,
                RecipeCommandRoute.resolve(new String[] {"recipes"})
        );
    }

    @Test
    void recipesRollStartsRolling() {
        assertEquals(
                RecipeCommandRoute.Action.START_ROLLING,
                RecipeCommandRoute.resolve(new String[] {"recipes", "RoLl"})
        );
    }

    @Test
    void unrelatedRecipeArgumentsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RecipeCommandRoute.resolve(new String[] {"recipes", "perfect"})
        );
    }
}
