package com.verdantvice.storage;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/**
 * Serializes asynchronous work per key while allowing operations for different
 * keys to run concurrently. A failed operation does not poison the key queue.
 *
 * @param <K> queue key type
 */
public final class PerKeyAsyncQueue<K> {
    private final ConcurrentHashMap<K, CompletableFuture<Void>> tails =
            new ConcurrentHashMap<>();

    /**
     * Enqueues one asynchronous operation for the supplied key.
     *
     * @param key operation key
     * @param operation asynchronous operation supplier
     * @param <T> result type
     * @return future completed with the operation result
     */
    public <T> CompletableFuture<T> submit(
            K key,
            Supplier<? extends CompletionStage<T>> operation
    ) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(operation, "operation");

        CompletableFuture<T> result = new CompletableFuture<>();

        CompletableFuture<Void> tail = tails.compute(key, (ignoredKey, previous) -> {
            CompletableFuture<Void> ready = previous == null
                    ? CompletableFuture.completedFuture(null)
                    : previous.handle((ignoredValue, ignoredFailure) -> (Void) null);

            return ready.thenCompose(
                    ignoredReady -> execute(operation, result)
            );
        });

        tail.whenComplete((ignoredValue, ignoredFailure) ->
                tails.remove(key, tail)
        );
        return result;
    }

    /**
     * Returns the number of keys that currently have queued or running work.
     *
     * @return active key count
     */
    public int activeKeys() {
        return tails.size();
    }

    private <T> CompletionStage<Void> execute(
            Supplier<? extends CompletionStage<T>> operation,
            CompletableFuture<T> result
    ) {
        CompletionStage<T> stage;
        try {
            stage = Objects.requireNonNull(
                    operation.get(),
                    "operation result"
            );
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
            return CompletableFuture.completedFuture(null);
        }

        return stage.handle((value, failure) -> {
            if (failure == null) {
                result.complete(value);
            } else {
                result.completeExceptionally(unwrap(failure));
            }
            return (Void) null;
        });
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
