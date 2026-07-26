package com.verdantvice.worker;

import com.verdantvice.common.DomainChecks;

public record MoraleFactors(
        boolean paid,
        boolean hasBed,
        boolean hasFood,
        boolean safe,
        boolean receivedBreak,
        int assignedTasks,
        int reasonableTaskLimit,
        boolean monstersNearby,
        boolean missingTools,
        boolean nightWork,
        int blockedTasks,
        boolean coworkerDied
) {
    public MoraleFactors {
        assignedTasks = DomainChecks.range(assignedTasks, 0, 100_000, "assignedTasks");
        reasonableTaskLimit = DomainChecks.range(reasonableTaskLimit, 1, 100_000, "reasonableTaskLimit");
        blockedTasks = DomainChecks.range(blockedTasks, 0, 100_000, "blockedTasks");
    }
}
