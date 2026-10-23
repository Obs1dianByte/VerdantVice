package com.verdantvice.gui;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record GuiContext(Optional<UUID> farmId, Optional<UUID> subjectId) {
    public GuiContext {
        farmId = Objects.requireNonNull(farmId, "farmId");
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
    }

    public static GuiContext empty() {
        return new GuiContext(Optional.empty(), Optional.empty());
    }

    public static GuiContext farm(UUID farmId) {
        return new GuiContext(Optional.of(Objects.requireNonNull(farmId, "farmId")), Optional.empty());
    }

    public GuiContext withSubject(UUID subjectId) {
        return new GuiContext(farmId, Optional.of(Objects.requireNonNull(subjectId, "subjectId")));
    }

    public GuiContext withoutSubject() {
        return new GuiContext(farmId, Optional.empty());
    }
}
