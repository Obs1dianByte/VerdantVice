package com.verdantvice.processing;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class StationBreakGuard<K> {
    private final Set<K> claimed = ConcurrentHashMap.newKeySet();

    public Optional<Lease<K>> acquire(K key, boolean busy) {
        Objects.requireNonNull(key, "key");
        if (busy || !claimed.add(key)) {
            return Optional.empty();
        }
        return Optional.of(new Lease<>(key, claimed));
    }

    public boolean isClaimed(K key) {
        return claimed.contains(Objects.requireNonNull(key, "key"));
    }

    public void clear() {
        claimed.clear();
    }

    public static final class Lease<K> implements AutoCloseable {
        private final K key;
        private final Set<K> owner;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(K key, Set<K> owner) {
            this.key = key;
            this.owner = owner;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.remove(key);
            }
        }
    }
}
