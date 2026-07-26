package com.verdantvice.processing;

import com.verdantvice.common.DomainChecks;

import java.util.List;

public record RollingAttempt(
        List<RollingStep> performedSteps,
        List<Long> timingErrorsMillis,
        double fillRatio
) {
    public RollingAttempt {
        performedSteps = List.copyOf(DomainChecks.required(performedSteps, "performedSteps"));
        timingErrorsMillis = List.copyOf(DomainChecks.required(timingErrorsMillis, "timingErrorsMillis"));
        if (performedSteps.size() != timingErrorsMillis.size()) {
            throw new IllegalArgumentException("Every step requires a timing error");
        }
        if (timingErrorsMillis.stream().anyMatch(value -> value == null || value < 0L)) {
            throw new IllegalArgumentException("Timing errors must be non-negative");
        }
        fillRatio = DomainChecks.range(fillRatio, 0.0, 2.0, "fillRatio");
    }
}
