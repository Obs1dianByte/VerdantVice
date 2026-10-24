package com.verdantvice.processing;

import org.bukkit.event.block.Action;

import java.util.Objects;

public final class ProcessingStationPolicy {
    private ProcessingStationPolicy() {
    }

    public static boolean opensMenu(Action action) {
        return action == Action.RIGHT_CLICK_BLOCK;
    }

    public static BreakDecision breakDecision(
            ProcessingStationType type,
            boolean hasActiveJob,
            boolean startPending
    ) {
        Objects.requireNonNull(type, "type");
        return hasActiveJob || startPending ? BreakDecision.BUSY : BreakDecision.ALLOW;
    }

    public enum BreakDecision {
        ALLOW,
        BUSY
    }
}
