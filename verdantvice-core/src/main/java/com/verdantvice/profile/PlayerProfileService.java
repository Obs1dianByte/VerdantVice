package com.verdantvice.profile;

import com.verdantvice.common.DomainChecks;
import com.verdantvice.tolerance.ToleranceState;

import java.time.Instant;
import java.util.EnumMap;

public final class PlayerProfileService {
    public PlayerProfile grantExperience(PlayerProfile profile, SkillTree tree, long gainedExperience,
                                         Instant now) {
        DomainChecks.required(profile, "profile");
        DomainChecks.required(tree, "tree");
        if (gainedExperience < 0L) throw new IllegalArgumentException("gainedExperience must not be negative");
        validateTime(profile, now);
        SkillProgress current = profile.skill(tree);
        long experience = Math.addExact(current.experience(), gainedExperience);
        int level = levelFor(experience);
        EnumMap<SkillTree, SkillProgress> skills = new EnumMap<>(SkillTree.class);
        skills.putAll(profile.skills());
        skills.put(tree, new SkillProgress(level, experience));
        return copy(profile, profile.locale(), profile.reputation(), skills, profile.tolerance(), now);
    }

    public PlayerProfile changeLocale(PlayerProfile profile, String locale, Instant now) {
        validateTime(profile, now);
        return copy(profile, locale, profile.reputation(), profile.skills(), profile.tolerance(), now);
    }

    public PlayerProfile addReputation(PlayerProfile profile, double delta, Instant now) {
        validateTime(profile, now);
        DomainChecks.finite(delta, "delta");
        return copy(profile, profile.locale(),
                DomainChecks.clamp(profile.reputation() + delta, 0.0, 100.0),
                profile.skills(), profile.tolerance(), now);
    }

    public PlayerProfile withTolerance(PlayerProfile profile, ToleranceState tolerance, Instant now) {
        validateTime(profile, now);
        return copy(profile, profile.locale(), profile.reputation(), profile.skills(), tolerance, now);
    }

    public int levelFor(long experience) {
        if (experience < 0L) throw new IllegalArgumentException("experience must not be negative");
        int level = (int) Math.floor(Math.sqrt(experience / 100.0));
        return Math.min(20, level);
    }

    public long experienceForLevel(int level) {
        DomainChecks.range(level, 0, 20, "level");
        return 100L * level * level;
    }

    private static PlayerProfile copy(PlayerProfile profile, String locale, double reputation,
                                      java.util.Map<SkillTree, SkillProgress> skills,
                                      ToleranceState tolerance, Instant now) {
        return new PlayerProfile(profile.playerUuid(), locale, reputation, skills, tolerance,
                profile.createdAt(), now);
    }

    private static void validateTime(PlayerProfile profile, Instant now) {
        DomainChecks.required(profile, "profile");
        DomainChecks.required(now, "now");
        if (now.isBefore(profile.lastSeenAt())) {
            throw new IllegalArgumentException("now cannot precede lastSeenAt");
        }
    }
}
