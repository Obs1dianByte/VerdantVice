package com.verdantvice.genetics;

import com.verdantvice.common.DomainChecks;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validates player-authored fictional genetic line names.
 */
public final class GeneticLineService {
    private final int maximumNameLength;
    private final Set<String> blockedTerms;
    private final Pattern allowedCharacters;

    public GeneticLineService(int maximumNameLength, Set<String> blockedTerms) {
        this.maximumNameLength = DomainChecks.range(maximumNameLength, 3, 64, "maximumNameLength");
        this.blockedTerms = Set.copyOf(DomainChecks.required(blockedTerms, "blockedTerms")).stream()
                .map(term -> term.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.allowedCharacters = Pattern.compile("[\\p{L}\\p{N} _'-]+");
    }

    public GeneticLine create(UUID id, String requestedName, Set<String> parents, UUID creator,
                              int generation, Map<String, Double> traits, Set<String> colors,
                              Set<String> effects, double stability, Instant now) {
        String name = validateName(requestedName);
        return new GeneticLine(id, name, parents, creator, generation, traits, colors, effects, stability, now);
    }

    public String validateName(String requestedName) {
        String name = DomainChecks.nonBlank(requestedName, "name").replaceAll("\\s+", " ");
        if (name.length() > maximumNameLength) {
            throw new IllegalArgumentException("Genetic line name exceeds " + maximumNameLength + " characters");
        }
        if (!allowedCharacters.matcher(name).matches()) {
            throw new IllegalArgumentException("Genetic line name contains unsupported characters");
        }
        String folded = name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        boolean blocked = blockedTerms.stream()
                .map(term -> term.replaceAll("[^\\p{L}\\p{N}]", ""))
                .anyMatch(term -> !term.isBlank() && folded.contains(term));
        if (blocked) {
            throw new IllegalArgumentException("Genetic line name contains a blocked term");
        }
        return name;
    }
}
