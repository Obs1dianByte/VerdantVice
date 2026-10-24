package com.verdantvice.gui;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiContextTest {
    private static final UUID FARM = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000202");

    @Test
    void farmFactoryStoresFarmId() {
        assertEquals(Optional.of(FARM), GuiContext.farm(FARM).farmId());
    }

    @Test
    void subjectDoesNotReplaceFarmId() {
        GuiContext context = GuiContext.farm(FARM).withSubject(SUBJECT);
        assertEquals(Optional.of(FARM), context.farmId());
        assertEquals(Optional.of(SUBJECT), context.subjectId());
    }

    @Test
    void clearingSubjectPreservesFarmId() {
        GuiContext context = GuiContext.farm(FARM).withSubject(SUBJECT).withoutSubject();
        assertEquals(Optional.of(FARM), context.farmId());
        assertTrue(context.subjectId().isEmpty());
    }

    @Test
    void emptyContextContainsNoIdentifiers() {
        assertTrue(GuiContext.empty().farmId().isEmpty());
        assertTrue(GuiContext.empty().subjectId().isEmpty());
    }
}
