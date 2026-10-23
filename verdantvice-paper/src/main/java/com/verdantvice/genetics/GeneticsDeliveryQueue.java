package com.verdantvice.genetics;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Serializes per-player receipt scans without dropping requests arriving mid-scan. */
public final class GeneticsDeliveryQueue {
    private final Map<UUID, CompletableFuture<Integer>> inFlight = new ConcurrentHashMap<>();

    public CompletableFuture<Integer> submit(UUID owner, Supplier<CompletableFuture<Integer>> action) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(action, "action");
        CompletableFuture<Integer> result = new CompletableFuture<>();
        CompletableFuture<Integer> existing = inFlight.putIfAbsent(owner, result);
        if (existing != null) {
            // A newly committed seed might not be in the existing query's snapshot.
            // Always scan again after that query, including when it failed.
            return existing.handle((ignored, failure) -> null)
                    .thenCompose(ignored -> submit(owner, action));
        }
        try {
            Objects.requireNonNull(action.get(), "delivery future").whenComplete((count, failure) -> {
                // Remove before completing, because completion callbacks can submit again.
                inFlight.remove(owner, result);
                if (failure == null) result.complete(count);
                else result.completeExceptionally(failure);
            });
        } catch (RuntimeException failure) {
            inFlight.remove(owner, result);
            result.completeExceptionally(failure);
        }
        return result;
    }
}
