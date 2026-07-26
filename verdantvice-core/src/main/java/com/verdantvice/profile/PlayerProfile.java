package com.verdantvice.profile;

import com.verdantvice.common.DomainChecks;
import com.verdantvice.tolerance.ToleranceState;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public record PlayerProfile(
        UUID playerUuid,
        String locale,
        double reputation,
        Map<SkillTree, SkillProgress> skills,
        ToleranceState tolerance,
        Instant createdAt,
        Instant lastSeenAt
) {
    private static final Pattern LOCALE = Pattern.compile("[a-z]{2}_[A-Z]{2}");

    public PlayerProfile {
        DomainChecks.required(playerUuid, "playerUuid");
        locale = DomainChecks.nonBlank(locale, "locale");
        if (!LOCALE.matcher(locale).matches()) {
            throw new IllegalArgumentException("locale must use language_COUNTRY form");
        }
        reputation = DomainChecks.range(reputation, 0.0, 100.0, "reputation");
        DomainChecks.required(skills, "skills");
        EnumMap<SkillTree, SkillProgress> skillCopy = new EnumMap<>(SkillTree.class);
        skillCopy.putAll(skills);
        for (SkillTree tree : SkillTree.values()) {
            skillCopy.putIfAbsent(tree, SkillProgress.initial());
        }
        skills = Map.copyOf(skillCopy);
        DomainChecks.required(tolerance, "tolerance");
        if (!tolerance.playerUuid().equals(playerUuid)) {
            throw new IllegalArgumentException("Tolerance state belongs to another player");
        }
        DomainChecks.ordered(createdAt, lastSeenAt, "profile activity");
    }

    public static PlayerProfile create(UUID playerUuid, String locale, Instant now) {
        EnumMap<SkillTree, SkillProgress> skills = new EnumMap<>(SkillTree.class);
        for (SkillTree tree : SkillTree.values()) skills.put(tree, SkillProgress.initial());
        return new PlayerProfile(playerUuid, locale, 0.0, skills,
                ToleranceState.initial(playerUuid, now), now, now);
    }

    public SkillProgress skill(SkillTree tree) {
        return skills.get(DomainChecks.required(tree, "tree"));
    }
}
