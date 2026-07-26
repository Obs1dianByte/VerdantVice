package com.verdantvice.profile;

import com.verdantvice.common.DomainChecks;

public record SkillProgress(int level, long experience) {
    public SkillProgress {
        level = DomainChecks.range(level, 0, 20, "level");
        if (experience < 0L) throw new IllegalArgumentException("experience must not be negative");
    }

    public static SkillProgress initial() {
        return new SkillProgress(0, 0L);
    }
}
