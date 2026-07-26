package com.verdantvice.effects;

import com.verdantvice.common.DomainChecks;

public record PlayerCondition(
        double health,
        double hunger,
        double stress,
        boolean activeStrongEffect
) {
    public PlayerCondition {
        health = DomainChecks.range(health, 0.0, 100.0, "health");
        hunger = DomainChecks.range(hunger, 0.0, 100.0, "hunger");
        stress = DomainChecks.range(stress, 0.0, 100.0, "stress");
    }
}
