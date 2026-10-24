package com.verdantvice.processing;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RollingStartGuard {
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();

    public Optional<Lease> acquire(UUID playerId, boolean sessionActive) {
        Objects.requireNonNull(playerId, "playerId");
        if (sessionActive || !pending.add(playerId)) {
            return Optional.empty();
        }
        return Optional.of(new Lease(playerId, pending));
    }

    public boolean isPending(UUID playerId) {
        return pending.contains(Objects.requireNonNull(playerId, "playerId"));
    }

    public void clear() {
        pending.clear();
    }

    public static final class Lease implements AutoCloseable {
        private final UUID playerId;
        private final Set<UUID> owner;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(UUID playerId, Set<UUID> owner) {
            this.playerId = playerId;
            this.owner = owner;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.remove(playerId);
            }
        }
    }
}
