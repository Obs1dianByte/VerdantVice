package com.verdantvice.worker.ai;

import com.verdantvice.common.BlockPosition;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

final class StorageSaveBarrierTest {
    private static final UUID WORLD = UUID.randomUUID();
    private static final BlockPosition FIRST = new BlockPosition(WORLD, 1, 64, 1);
    private static final BlockPosition SECOND = new BlockPosition(WORLD, 2, 64, 2);

    @Test void batchesManyDeliveriesIntoOneWorldSaveAndAcknowledgesOnlyAfterIt() {
        Backend backend = new Backend();
        StorageSaveBarrier barrier = new StorageSaveBarrier(backend);
        AtomicInteger acknowledgements = new AtomicInteger();
        List<CompletableFuture<Void>> deliveries = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            deliveries.add(barrier.save(i % 2 == 0 ? FIRST : SECOND)
                    .thenRun(acknowledgements::incrementAndGet));
        }
        assertEquals(1, backend.tasks.size());
        assertEquals(0, acknowledgements.get());
        assertTrue(deliveries.stream().noneMatch(CompletableFuture::isDone));
        backend.duringSave = () -> assertEquals(0, acknowledgements.get());
        backend.tasks.remove().run();
        assertEquals(List.of(WORLD), backend.saved);
        assertEquals(200, acknowledgements.get());
        assertTrue(deliveries.stream().allMatch(CompletableFuture::isDone));
    }

    @Test void savesEachWorldOnceAndSchedulesTheNextBatchIndependently() {
        Backend backend = new Backend();
        StorageSaveBarrier barrier = new StorageSaveBarrier(backend);
        UUID other = UUID.randomUUID();
        barrier.save(FIRST);
        barrier.save(new BlockPosition(other, 0, 64, 0));
        backend.tasks.remove().run();
        assertEquals(Set.of(WORLD, other), new HashSet<>(backend.saved));
        assertEquals(2, backend.saved.size());
        barrier.save(FIRST);
        assertEquals(1, backend.tasks.size());
        backend.tasks.remove().run();
        assertEquals(3, backend.saved.size());
    }

    @Test void failedSaveDoesNotAcknowledgeAndNextAttemptCanRetry() {
        Backend backend = new Backend();
        StorageSaveBarrier barrier = new StorageSaveBarrier(backend);
        AtomicInteger acknowledged = new AtomicInteger();
        backend.saveFailure = new IllegalStateException("disk full");
        CompletableFuture<Void> failed = barrier.save(FIRST).thenRun(acknowledged::incrementAndGet);
        backend.tasks.remove().run();
        assertThrows(CompletionException.class, failed::join);
        assertEquals(0, acknowledged.get());
        backend.saveFailure = null;
        CompletableFuture<Void> retry = barrier.save(FIRST).thenRun(acknowledged::incrementAndGet);
        backend.tasks.remove().run();
        retry.join();
        assertEquals(1, acknowledged.get());
    }

    @Test void unloadedOrReplacedContainerNeverAcknowledgesItsDelivery() {
        Backend backend = new Backend();
        StorageSaveBarrier barrier = new StorageSaveBarrier(backend);
        CompletableFuture<Void> unloaded = barrier.save(FIRST);
        CompletableFuture<Void> loaded = barrier.save(SECOND);
        backend.unavailable.add(FIRST);
        backend.tasks.remove().run();
        assertThrows(CompletionException.class, unloaded::join);
        loaded.join();
        assertEquals(List.of(WORLD), backend.saved);
        CompletableFuture<Void> alreadyUnloaded = barrier.save(FIRST);
        assertThrows(CompletionException.class, alreadyUnloaded::join);
        assertTrue(backend.tasks.isEmpty());
    }

    @Test void losingStorageDuringSaveFailsInsteadOfAcknowledging() {
        Backend backend = new Backend();
        StorageSaveBarrier barrier = new StorageSaveBarrier(backend);
        CompletableFuture<Void> result = barrier.save(FIRST);
        backend.duringSave = () -> backend.unavailable.add(FIRST);
        backend.tasks.remove().run();
        assertThrows(CompletionException.class, result::join);
    }

    @Test void failedSchedulingCompletesEveryWaiterAndAllowsLaterRetry() {
        Backend backend = new Backend();
        StorageSaveBarrier barrier = new StorageSaveBarrier(backend);
        backend.scheduleFailure = true;
        assertThrows(CompletionException.class, () -> barrier.save(FIRST).join());
        backend.scheduleFailure = false;
        CompletableFuture<Void> retry = barrier.save(FIRST);
        backend.tasks.remove().run();
        retry.join();
    }

    @Test void loadedContainerWithMissingOutputRemainsPendingUntilTheIdentityIsPresent() {
        Backend backend = new Backend();
        StorageSaveBarrier barrier = new StorageSaveBarrier(backend);
        AtomicBoolean outputPresent = new AtomicBoolean(true);
        AtomicInteger acknowledgements = new AtomicInteger();
        CompletableFuture<Void> replaced = barrier.save(FIRST, outputPresent::get)
                .thenRun(acknowledgements::incrementAndGet);
        outputPresent.set(false);
        backend.tasks.remove().run();
        assertThrows(CompletionException.class, replaced::join);
        assertTrue(backend.saved.isEmpty());
        assertEquals(0, acknowledgements.get());
        outputPresent.set(true);
        CompletableFuture<Void> retry = barrier.save(FIRST, outputPresent::get)
                .thenRun(acknowledgements::incrementAndGet);
        backend.tasks.remove().run();
        retry.join();
        assertEquals(List.of(WORLD), backend.saved);
        assertEquals(1, acknowledgements.get());
    }

    @Test void outputRemovedDuringWorldSaveIsNotAcknowledged() {
        Backend backend = new Backend();
        StorageSaveBarrier barrier = new StorageSaveBarrier(backend);
        AtomicBoolean outputPresent = new AtomicBoolean(true);
        CompletableFuture<Void> moved = barrier.save(FIRST, outputPresent::get);
        backend.duringSave = () -> outputPresent.set(false);
        backend.tasks.remove().run();
        assertThrows(CompletionException.class, moved::join);
        assertEquals(List.of(WORLD), backend.saved);
    }

    @Test void failingRevalidationDoesNotStrandOtherDeliveries() {
        Backend backend = new Backend();
        StorageSaveBarrier barrier = new StorageSaveBarrier(backend);
        AtomicBoolean failCheck = new AtomicBoolean(false);
        CompletableFuture<Void> broken = barrier.save(FIRST, () -> {
            if (failCheck.get()) throw new IllegalStateException("storage changed");
            return true;
        });
        CompletableFuture<Void> valid = barrier.save(SECOND);
        failCheck.set(true);
        backend.tasks.remove().run();
        assertThrows(CompletionException.class, broken::join);
        valid.join();
        assertEquals(List.of(WORLD), backend.saved);
    }

    @Test void rejectsAsyncBukkitAccessBeforeSchedulingOrSaving() {
        Backend backend = new Backend();
        backend.mainThread = false;
        StorageSaveBarrier barrier = new StorageSaveBarrier(backend);
        assertThrows(IllegalStateException.class, () -> barrier.save(FIRST));
        assertTrue(backend.tasks.isEmpty());
        assertTrue(backend.saved.isEmpty());
    }

    private static final class Backend implements StorageSaveBarrier.Backend {
        boolean mainThread = true;
        boolean scheduleFailure;
        RuntimeException saveFailure;
        Runnable duringSave = () -> { };
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        final Set<BlockPosition> unavailable = new HashSet<>();
        final List<UUID> saved = new ArrayList<>();
        public boolean isMainThread() { return mainThread; }
        public void nextTick(Runnable action) {
            if (scheduleFailure) throw new IllegalStateException("scheduler stopped");
            tasks.add(action);
        }
        public boolean available(BlockPosition position) { return !unavailable.contains(position); }
        public void saveWorld(UUID worldId) {
            assertTrue(mainThread);
            duringSave.run();
            if (saveFailure != null) throw saveFailure;
            saved.add(worldId);
        }
    }
}
