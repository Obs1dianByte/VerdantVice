package com.verdantvice.worker.ai;

import com.verdantvice.common.BlockPosition;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/**
 * Batches inventory delivery saves by world on the next server tick. An outbox
 * may be acknowledged only after this future succeeds. Bukkit documents
 * {@link World#save()} as saving the world to disk; it must never run async.
 * A failed save/unavailable container leaves the durable outbox pending.
 */
public final class StorageSaveBarrier {
    private final Backend backend;
    private final Map<UUID, List<Request>> pending = new LinkedHashMap<>();
    private boolean scheduled;

    public StorageSaveBarrier(JavaPlugin plugin) {
        this(new BukkitBackend(Objects.requireNonNull(plugin, "plugin")));
    }

    StorageSaveBarrier(Backend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    public CompletableFuture<Void> save(BlockPosition position) {
        return save(position, () -> true);
    }

    public CompletableFuture<Void> save(BlockPosition position, BooleanSupplier containsDelivery) {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(containsDelivery, "containsDelivery");
        requireMainThread();
        if (!backend.available(position) || !containsDelivery.getAsBoolean()) return CompletableFuture.failedFuture(
                new IllegalStateException("Delivery storage is not loaded"));
        CompletableFuture<Void> result = new CompletableFuture<>();
        pending.computeIfAbsent(position.worldUuid(), ignored -> new ArrayList<>())
                .add(new Request(position, containsDelivery, result));
        if (!scheduled) {
            scheduled = true;
            try { backend.nextTick(this::flush); }
            catch (Throwable failure) {
                scheduled = false;
                Map<UUID, List<Request>> failed = new LinkedHashMap<>(pending);
                pending.clear();
                failed.values().forEach(requests -> requests.forEach(request ->
                        request.result().completeExceptionally(failure)));
            }
        }
        return result;
    }

    private void flush() {
        requireMainThread();
        scheduled = false;
        Map<UUID, List<Request>> batch = new LinkedHashMap<>(pending);
        pending.clear();
        batch.forEach((worldId, requests) -> {
            List<Request> available = new ArrayList<>();
            for (Request request : requests) {
                try {
                    if (backend.available(request.position()) && request.containsDelivery().getAsBoolean()) {
                        available.add(request);
                    } else request.result().completeExceptionally(
                            new IllegalStateException("Delivery storage or output unavailable before save"));
                } catch (Throwable failure) {
                    request.result().completeExceptionally(failure);
                }
            }
            if (available.isEmpty()) return;
            try {
                backend.saveWorld(worldId);
                for (Request request : available) {
                    if (backend.available(request.position()) && request.containsDelivery().getAsBoolean()) {
                        request.result().complete(null);
                    }
                    else request.result().completeExceptionally(
                            new IllegalStateException("Delivery storage or output unavailable after save"));
                }
            } catch (Throwable failure) {
                available.forEach(request -> request.result().completeExceptionally(failure));
            }
        });
    }

    private void requireMainThread() {
        if (!backend.isMainThread()) throw new IllegalStateException(
                "Storage saves must run on the server thread");
    }

    interface Backend {
        boolean isMainThread();
        void nextTick(Runnable action);
        boolean available(BlockPosition position);
        void saveWorld(UUID worldId);
    }

    private record Request(BlockPosition position, BooleanSupplier containsDelivery,
                           CompletableFuture<Void> result) { }

    private record BukkitBackend(JavaPlugin plugin) implements Backend {
        @Override public boolean isMainThread() { return plugin.getServer().isPrimaryThread(); }
        @Override public void nextTick(Runnable action) {
            plugin.getServer().getScheduler().runTask(plugin, action);
        }
        @Override public boolean available(BlockPosition position) {
            World world = plugin.getServer().getWorld(position.worldUuid());
            return world != null && world.isChunkLoaded(position.x() >> 4, position.z() >> 4)
                    && world.getBlockAt(position.x(), position.y(), position.z()).getState() instanceof Container;
        }
        @Override public void saveWorld(UUID worldId) {
            World world = plugin.getServer().getWorld(worldId);
            if (world == null) throw new IllegalStateException("Delivery world unloaded before save");
            world.save();
        }
    }
}
