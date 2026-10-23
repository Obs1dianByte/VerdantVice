package com.verdantvice.strain;

import com.verdantvice.common.DomainChecks;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.Locale;
import java.text.Normalizer;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry used by configuration reloads and the public plugin API.
 */
public final class StrainRegistry {
    private final Map<String, Strain> strains = new ConcurrentHashMap<>();

    public void register(Strain strain) {
        DomainChecks.required(strain, "strain");
        Strain existing = strains.putIfAbsent(strain.id(), strain);
        if (existing != null) {
            throw new IllegalArgumentException("Strain already registered: " + strain.id());
        }
    }

    public void replace(Strain strain) {
        DomainChecks.required(strain, "strain");
        strains.put(strain.id(), strain);
    }

    public Optional<Strain> find(String id) {
        String requested = DomainChecks.nonBlank(id, "id").toLowerCase(Locale.ROOT);
        Strain canonical = strains.get(requested);
        if (canonical != null) return Optional.of(canonical);
        String alias = commandAlias(requested);
        if (alias.isEmpty()) return Optional.empty();
        List<Strain> matches = strains.values().stream()
                .filter(strain -> commandAlias(strain.displayName()).equals(alias))
                .limit(2).toList();
        // Ambiguous cosmetic labels never silently select another persistent strain.
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    public List<String> commandSuggestions() {
        return all().stream().map(strain -> {
            String alias = commandAlias(strain.displayName());
            return !alias.isEmpty() && find(alias).map(value -> value.id().equals(strain.id())).orElse(false)
                    ? alias : strain.id();
        }).distinct().sorted().toList();
    }

    public static String commandAlias(String displayName) {
        return Normalizer.normalize(DomainChecks.required(displayName, "displayName"), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    public Strain require(String id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException("Unknown strain: " + id));
    }

    public Collection<Strain> all() {
        ArrayList<Strain> snapshot = new ArrayList<>(strains.values());
        snapshot.sort(Comparator.comparing(Strain::id));
        return java.util.List.copyOf(snapshot);
    }

    public int size() {
        return strains.size();
    }
}
