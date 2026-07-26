package com.verdantvice.effects;

public enum EffectType {
    SPEED(true),
    HASTE(true),
    NIGHT_VISION(true),
    RESISTANCE(true),
    REGENERATION(true),
    STRESS_RELIEF(true),
    PARTICLES(true),
    ALTERED_SOUNDS(true),
    HUNGER(false),
    SLOWNESS(false),
    WEAKNESS(false),
    NAUSEA(false),
    DARKNESS(false),
    COUGH(false),
    FALSE_FOOTSTEPS(false),
    DISORIENTATION(false),
    GREEN_OUT(false);

    private final boolean positive;

    EffectType(boolean positive) {
        this.positive = positive;
    }

    public boolean positive() {
        return positive;
    }
}
