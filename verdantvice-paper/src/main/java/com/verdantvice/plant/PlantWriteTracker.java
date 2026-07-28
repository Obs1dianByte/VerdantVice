package com.verdantvice.plant;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class PlantWriteTracker {
    private final ConcurrentHashMap<UUID, Integer> pending = new ConcurrentHashMap<>();
    private final Set<UUID> loggedFailures = ConcurrentHashMap.newKeySet();

    void begin(UUID plantId) {
        pending.merge(plantId, 1, Integer::sum);
    }

    void end(UUID plantId) {
        pending.computeIfPresent(plantId, (ignored, count) -> count <= 1 ? null : count - 1);
    }

    boolean hasPending(UUID plantId) {
        return pending.containsKey(plantId);
    }

    boolean shouldLogFailure(UUID plantId) {
        return loggedFailures.add(plantId);
    }

    void markSuccess(UUID plantId) {
        loggedFailures.remove(plantId);
    }

    void clear() {
        pending.clear();
        loggedFailures.clear();
    }
}
