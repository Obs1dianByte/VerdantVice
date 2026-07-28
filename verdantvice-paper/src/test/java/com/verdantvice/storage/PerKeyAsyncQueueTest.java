package com.verdantvice.storage;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerKeyAsyncQueueTest {
    @Test
    void samePlantRunsStrictlySequentially() {
        PerKeyAsyncQueue<UUID> queue = new PerKeyAsyncQueue<>();
        UUID plantId = UUID.randomUUID();
        CompletableFuture<Void> releaseFirst = new CompletableFuture<>();
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maximumRunning = new AtomicInteger();
        AtomicBoolean secondStarted = new AtomicBoolean();

        CompletableFuture<Integer> first = queue.submit(plantId, () -> {
            maximumRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            return releaseFirst.thenApply(ignored -> {
                running.decrementAndGet();
                return 1;
            });
        });
        CompletableFuture<Integer> second = queue.submit(plantId, () -> {
            secondStarted.set(true);
            maximumRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            running.decrementAndGet();
            return CompletableFuture.completedFuture(2);
        });

        assertFalse(secondStarted.get());
        releaseFirst.complete(null);
        assertEquals(1, first.join());
        assertEquals(2, second.join());
        assertEquals(1, maximumRunning.get());
        awaitQueueCleanup(queue);
    }

    @Test
    void differentPlantsMayRunInParallel() {
        PerKeyAsyncQueue<UUID> queue = new PerKeyAsyncQueue<>();
        CompletableFuture<Void> release = new CompletableFuture<>();
        AtomicInteger started = new AtomicInteger();

        CompletableFuture<Void> first = queue.submit(UUID.randomUUID(), () -> {
            started.incrementAndGet();
            return release;
        });
        CompletableFuture<Void> second = queue.submit(UUID.randomUUID(), () -> {
            started.incrementAndGet();
            return release;
        });

        assertEquals(2, started.get());
        release.complete(null);
        CompletableFuture.allOf(first, second).join();
    }

    @Test
    void failedOperationDoesNotPoisonPlantQueue() {
        PerKeyAsyncQueue<UUID> queue = new PerKeyAsyncQueue<>();
        UUID plantId = UUID.randomUUID();
        CompletableFuture<Void> failed = queue.submit(
                plantId,
                () -> CompletableFuture.failedFuture(new IllegalStateException("failed"))
        );
        CompletableFuture<Integer> next = queue.submit(
                plantId,
                () -> CompletableFuture.completedFuture(42)
        );

        assertThrows(CompletionException.class, failed::join);
        assertEquals(42, next.join());
        awaitQueueCleanup(queue);
    }

    private void awaitQueueCleanup(PerKeyAsyncQueue<UUID> queue) {
        for (int attempt = 0; attempt < 100 && queue.activeKeys() != 0; attempt++) {
            Thread.onSpinWait();
        }
        assertEquals(0, queue.activeKeys());
    }
}
