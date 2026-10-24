package com.verdantvice.processing;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Prevents duplicate asynchronous starts for both a station and an item instance. */
final class ProcessingStartGuard {
    private final Set<UUID> stations;
    private final Set<UUID> items = ConcurrentHashMap.newKeySet();

    ProcessingStartGuard() {
        this(ConcurrentHashMap.newKeySet());
    }

    ProcessingStartGuard(Set<UUID> stations) {
        this.stations = Objects.requireNonNull(stations, "stations");
    }

    Optional<Lease> tryAcquire(UUID stationId, UUID itemId) {
        Objects.requireNonNull(stationId, "stationId");
        Objects.requireNonNull(itemId, "itemId");
        if (!stations.add(stationId)) {
            return Optional.empty();
        }
        if (!items.add(itemId)) {
            stations.remove(stationId);
            return Optional.empty();
        }
        return Optional.of(new Lease(stationId, itemId));
    }

    boolean stationPending(UUID stationId) {
        return stations.contains(stationId);
    }

    boolean itemPending(UUID itemId) {
        return items.contains(itemId);
    }

    void clear() {
        stations.clear();
        items.clear();
    }

    final class Lease implements AutoCloseable {
        private final UUID stationId;
        private final UUID itemId;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(UUID stationId, UUID itemId) {
            this.stationId = stationId;
            this.itemId = itemId;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                items.remove(itemId);
                stations.remove(stationId);
            }
        }
    }
}
