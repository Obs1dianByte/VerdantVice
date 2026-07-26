package com.verdantvice.strain;

import com.verdantvice.common.DomainChecks;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
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
        return Optional.ofNullable(strains.get(DomainChecks.identifier(id, "id")));
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
