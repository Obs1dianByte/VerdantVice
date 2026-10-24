package com.verdantvice.command;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LanguageCommandChoicesTest {
    private static final Set<String> SUPPORTED = Set.of(
            "pl_PL", "en_US", "de_DE", "es_ES", "cs_CZ", "uk_UA");

    @Test
    void everySupportedLanguageHasOneSortedChoice() {
        List<LanguageCommandChoices.Choice> choices = LanguageCommandChoices.choices(SUPPORTED);

        assertEquals(SUPPORTED.size(), choices.size());
        assertEquals(SUPPORTED.stream().sorted().toList(),
                choices.stream().map(LanguageCommandChoices.Choice::locale).toList());
    }

    @Test
    void everyClickDispatchesAnExplicitSupportedLocaleWithoutEmptyArguments() {
        for (LanguageCommandChoices.Choice choice : LanguageCommandChoices.choices(SUPPORTED)) {
            String[] arguments = choice.command().split(" ", -1);
            assertEquals(3, arguments.length);
            assertEquals("/vv", arguments[0]);
            assertEquals("language", arguments[1]);
            assertEquals(choice.locale(), arguments[2]);
            assertTrue(SUPPORTED.contains(arguments[2]));
            assertFalse(choice.command().endsWith(" "));
        }
    }

    @Test
    void anEmptyCatalogDoesNotDispatchAnArgumentFreeCommand() {
        assertTrue(LanguageCommandChoices.choices(Set.of()).isEmpty());
    }

    @Test
    void malformedLocaleCannotBecomeAClickableCommand() {
        for (String invalid : List.of("", "en_US extra", "en_US\nvv admin save", "../en_US")) {
            assertThrows(IllegalArgumentException.class,
                    () -> LanguageCommandChoices.choices(Set.of(invalid)));
        }
    }
}
