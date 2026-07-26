package com.verdantvice.worker.ai;

import com.destroystokyo.paper.entity.Pathfinder;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Villager;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Small public-Paper-API navigation adapter. It never teleports during normal
 * navigation and tests a bounded list of approach points.
 */
public final class PaperWorkerPathfinder {
    private final JavaPlugin plugin;

    public PaperWorkerPathfinder(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public Optional<Navigation> begin(
            Villager villager,
            Location target,
            double speed,
            Duration timeout
    ) {
        requireMainThread();
        Objects.requireNonNull(villager, "villager");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(timeout, "timeout");
        if (speed <= 0.0 || timeout.isZero() || timeout.isNegative()
                || !villager.isValid() || villager.isDead()
                || target.getWorld() == null
                || !villager.getWorld().equals(target.getWorld())) {
            return Optional.empty();
        }
        Pathfinder pathfinder = villager.getPathfinder();
        for (Location candidate : approaches(target)) {
            if (!safeFeet(candidate)) {
                continue;
            }
            Pathfinder.PathResult path = pathfinder.findPath(candidate);
            if (path != null && pathfinder.moveTo(path, speed)) {
                Instant now = Instant.now();
                return Optional.of(new Navigation(
                        candidate.clone(),
                        now,
                        now.plus(timeout)
                ));
            }
        }
        return Optional.empty();
    }

    public NavigationStatus poll(Villager villager, Navigation navigation) {
        requireMainThread();
        Objects.requireNonNull(villager, "villager");
        Objects.requireNonNull(navigation, "navigation");
        if (!villager.isValid() || villager.isDead()
                || navigation.destination().getWorld() == null
                || !villager.getWorld().equals(navigation.destination().getWorld())) {
            return NavigationStatus.TARGET_UNAVAILABLE;
        }
        if (villager.getLocation().distanceSquared(navigation.destination()) <= 2.25) {
            villager.getPathfinder().stopPathfinding();
            return NavigationStatus.ARRIVED;
        }
        if (!Instant.now().isBefore(navigation.deadline())) {
            villager.getPathfinder().stopPathfinding();
            return NavigationStatus.TIMED_OUT;
        }
        if (!villager.getPathfinder().hasPath()) {
            return NavigationStatus.NO_PATH;
        }
        return NavigationStatus.MOVING;
    }

    public void stop(Villager villager) {
        requireMainThread();
        if (villager.isValid() && !villager.isDead()) {
            villager.getPathfinder().stopPathfinding();
        }
    }

    public boolean emergencyMove(Villager villager, Location destination) {
        requireMainThread();
        Objects.requireNonNull(villager, "villager");
        Objects.requireNonNull(destination, "destination");
        return villager.isValid()
                && !villager.isDead()
                && destination.getWorld() != null
                && safeFeet(destination)
                && villager.teleport(destination);
    }

    private List<Location> approaches(Location target) {
        List<Location> candidates = new ArrayList<>(13);
        candidates.add(center(target));
        int[][] offsets = {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1},
                {2, 0}, {-2, 0}, {0, 2}, {0, -2}
        };
        for (int[] offset : offsets) {
            candidates.add(center(target.clone().add(offset[0], 0.0, offset[1])));
        }
        return List.copyOf(candidates);
    }

    private Location center(Location source) {
        return new Location(
                source.getWorld(),
                source.getBlockX() + 0.5,
                source.getBlockY(),
                source.getBlockZ() + 0.5,
                source.getYaw(),
                source.getPitch()
        );
    }

    private boolean safeFeet(Location location) {
        World world = location.getWorld();
        if (world == null || !world.isChunkLoaded(
                location.getBlockX() >> 4,
                location.getBlockZ() >> 4)) {
            return false;
        }
        Block feet = world.getBlockAt(
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ()
        );
        Block head = feet.getRelative(0, 1, 0);
        Block floor = feet.getRelative(0, -1, 0);
        return feet.isPassable()
                && head.isPassable()
                && floor.getType().isSolid();
    }

    private void requireMainThread() {
        if (!plugin.getServer().isPrimaryThread()) {
            throw new IllegalStateException("Worker navigation must run on the server thread");
        }
    }

    public record Navigation(
            Location destination,
            Instant startedAt,
            Instant deadline
    ) {
        public Navigation {
            destination = Objects.requireNonNull(destination, "destination").clone();
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(deadline, "deadline");
            if (!deadline.isAfter(startedAt)) {
                throw new IllegalArgumentException("Navigation deadline must follow start");
            }
        }

        @Override
        public Location destination() {
            return destination.clone();
        }
    }

    public enum NavigationStatus {
        MOVING,
        ARRIVED,
        NO_PATH,
        TIMED_OUT,
        TARGET_UNAVAILABLE
    }
}
