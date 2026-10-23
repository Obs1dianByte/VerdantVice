package com.verdantvice.genetics;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class GeneticsDeliveryQueueTest {
    @Test
    void newCommitDuringAnExistingSnapshotGetsItsOwnScan() {
        GeneticsDeliveryQueue queue = new GeneticsDeliveryQueue();
        UUID owner = UUID.randomUUID();
        AtomicInteger scans = new AtomicInteger();
        CompletableFuture<Integer> snapshot = new CompletableFuture<>();
        var first = queue.submit(owner, () -> {
            scans.incrementAndGet();
            return snapshot;
        });
        var newReceipt = queue.submit(owner, () -> {
            scans.incrementAndGet();
            return CompletableFuture.completedFuture(1);
        });
        assertEquals(1, scans.get());
        assertFalse(newReceipt.isDone());
        snapshot.complete(0);
        assertEquals(0, first.join());
        assertEquals(1, newReceipt.join());
        assertEquals(2, scans.get(), "The second request must not reuse the stale snapshot");
    }

    @Test
    void failedDeliveryDoesNotBlockTheNextReceiptScan() {
        GeneticsDeliveryQueue queue = new GeneticsDeliveryQueue();
        UUID owner = UUID.randomUUID();
        CompletableFuture<Integer> failure = new CompletableFuture<>();
        var first = queue.submit(owner, () -> failure);
        var retry = queue.submit(owner, () -> CompletableFuture.completedFuture(2));
        failure.completeExceptionally(new IllegalStateException("database temporarily unavailable"));
        assertThrows(CompletionException.class, first::join);
        assertEquals(2, retry.join());
    }

    @Test
    void unrelatedPlayersDoNotBlockEachOtherAndSynchronousFailureReleasesTheQueue() {
        GeneticsDeliveryQueue queue = new GeneticsDeliveryQueue();
        UUID firstOwner = UUID.randomUUID();
        CompletableFuture<Integer> pending = new CompletableFuture<>();
        queue.submit(firstOwner, () -> pending);
        assertEquals(3, queue.submit(UUID.randomUUID(), () -> CompletableFuture.completedFuture(3)).join());
        UUID failedOwner = UUID.randomUUID();
        assertThrows(CompletionException.class,
                () -> queue.submit(failedOwner, () -> { throw new IllegalStateException("scheduler stopped"); }).join());
        assertEquals(1, queue.submit(failedOwner, () -> CompletableFuture.completedFuture(1)).join());
        pending.complete(0);
    }
}
