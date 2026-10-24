package com.verdantvice.farm;

import com.verdantvice.common.BlockPosition;
import com.verdantvice.config.ConfigManager;
import com.verdantvice.integration.RegionProtectionService;
import com.verdantvice.storage.FarmRepository;
import com.verdantvice.storage.StoragePersistenceException;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

public final class FarmManager {
    private static final long MAXIMUM_REGION_VOLUME = 2_000_000L;
    private static final int MAXIMUM_REGION_SIDE = 128;

    private final JavaPlugin plugin;
    private final ConfigManager configs;
    private final FarmRepository repository;
    private final RegionProtectionService regionProtection;
    private final FarmService farmService = new FarmService();
    private final Map<UUID, Farm> farms = new ConcurrentHashMap<>();
    private final Map<UUID, Farm> pendingFarms = new ConcurrentHashMap<>();
    private final Map<UUID, BlockPosition> managerTables = new ConcurrentHashMap<>();
    private final Map<BlockPosition, FarmStorage> storages = new ConcurrentHashMap<>();
    private final Map<ChunkKey, Set<UUID>> chunkIndex = new ConcurrentHashMap<>();
    private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();
    private final Set<BlockPosition> pendingStorageChanges = ConcurrentHashMap.newKeySet();

    public FarmManager(
            JavaPlugin plugin,
            ConfigManager configs,
            FarmRepository repository,
            RegionProtectionService regionProtection
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.regionProtection = Objects.requireNonNull(regionProtection, "regionProtection");
    }

    public CompletableFuture<Void> load() {
        return repository.loadAll().thenCompose(snapshot -> runOnMain(() -> {
            farms.clear();
            managerTables.clear();
            storages.clear();
            chunkIndex.clear();
            snapshot.farms().forEach(farm -> {
                farms.put(farm.farmId(), farm);
                index(farm);
            });
            managerTables.putAll(snapshot.tablePositions());
            snapshot.storages().forEach(storage ->
                    storages.put(storage.position(), storage));
        }));
    }

    public CompletableFuture<Farm> create(Player player, String requestedName) {
        return create(player, requestedName, null);
    }

    public CompletableFuture<Farm> createAt(
            Player player,
            String requestedName,
            Block managerTable
    ) {
        Objects.requireNonNull(managerTable, "managerTable");
        return create(player, requestedName, position(managerTable.getLocation()));
    }

    private CompletableFuture<Farm> create(
            Player player,
            String requestedName,
            BlockPosition requestedTable
    ) {
        requireMainThread();
        try {
            return prepareCreation(player, requestedName, requestedTable);
        } catch (RuntimeException failure) {
            // All validation failures use the same future contract. In
            // particular, a placed table must reach the listener's refund path.
            return CompletableFuture.failedFuture(failure);
        }
    }

    private CompletableFuture<Farm> prepareCreation(
            Player player,
            String requestedName,
            BlockPosition requestedTable
    ) {
        String name = sanitizeName(requestedName);
        List<Farm> claimedRegions = new ArrayList<>(farms.values());
        claimedRegions.addAll(pendingFarms.values());
        long owned = claimedRegions.stream()
                .filter(farm -> farm.ownerUuid().equals(player.getUniqueId()))
                .count();
        int limit = configs.main().getInt("limits.farms-per-player", 2);
        if (owned >= limit && !player.hasPermission("verdantvice.admin")) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("farm_limit")
            );
        }
        FarmRegion region = selectedOrDefault(player);
        validateRegion(region);
        BlockPosition table = requestedTable == null
                ? tablePosition(region)
                : requestedTable;
        if (!region.contains(table)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("manager_table_outside_farm")
            );
        }
        Location tableLocation = location(table);
        if (tableLocation == null || !regionProtection.canBuild(player, tableLocation)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("region_denied")
            );
        }
        Block block = tableLocation.getBlock();
        if (!block.isReplaceable() && block.getType() != Material.LECTERN) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("manager_table_blocked")
            );
        }

        Farm farm;
        try {
            farm = farmService.create(
                    UUID.randomUUID(),
                    player.getUniqueId(),
                    name,
                    region,
                    configs.main().getInt("limits.plants-per-player", 64),
                    configs.main().getInt("performance.max-workers-per-farm", 5),
                    FarmSettings.defaults(),
                    claimedRegions,
                    Instant.now()
            );
        } catch (IllegalArgumentException exception) {
            return CompletableFuture.failedFuture(exception);
        }

        // Keep the claim visible while the database is writing so two rapid
        // requests cannot bypass the limit or claim overlapping regions.
        pendingFarms.put(farm.farmId(), farm);
        CompletableFuture<Void> persisted;
        try {
            persisted = repository.create(farm, table);
        } catch (RuntimeException failure) {
            pendingFarms.remove(farm.farmId());
            return CompletableFuture.failedFuture(failure);
        }
        return persisted.thenCompose(ignored -> runOnMain(() -> {
            block.setType(Material.LECTERN, false);
            farms.put(farm.farmId(), farm);
            managerTables.put(farm.farmId(), table);
            index(farm);
            selections.remove(player.getUniqueId());
        })).thenApply(ignored -> farm)
                .whenComplete((ignored, failure) -> pendingFarms.remove(farm.farmId()));
    }

    public CompletableFuture<Boolean> delete(Player player, UUID farmId) {
        requireMainThread();
        Farm farm = farms.get(farmId);
        if (farm == null || (!farm.ownerUuid().equals(player.getUniqueId())
                && !player.hasPermission("verdantvice.admin"))) {
            return CompletableFuture.completedFuture(false);
        }
        return repository.delete(farmId, farm.ownerUuid())
                .thenCompose(deleted -> runOnMain(() -> {
                    if (!deleted) {
                        return;
                    }
                    farms.remove(farmId);
                    deindex(farm);
                    BlockPosition table = managerTables.remove(farmId);
                    Location location = table == null ? null : location(table);
                    if (location != null && location.getBlock().getType() == Material.LECTERN) {
                        location.getBlock().setType(Material.AIR, false);
                    }
                    storages.entrySet().removeIf(entry ->
                            entry.getValue().farmId().equals(farmId));
                }).thenApply(ignored -> deleted));
    }

    public CompletableFuture<Farm> addMember(
            Player actor,
            UUID farmId,
            UUID playerUuid,
            FarmRole role
    ) {
        requireMainThread();
        Farm farm = requirePermission(actor, farmId, FarmPermission.MANAGE_MEMBERS);
        FarmMember member = new FarmMember(
                playerUuid,
                role,
                Set.of(),
                Set.of(),
                Instant.now()
        );
        Farm updated = farmService.addMember(farm, member);
        return repository.putMember(farmId, member)
                .thenCompose(ignored -> runOnMain(() -> farms.computeIfPresent(
                        farmId, (ignoredId, current) -> farmService.addMember(current, member))))
                .thenApply(ignored -> farms.getOrDefault(farmId, updated));
    }

    public CompletableFuture<Boolean> removeMember(
            Player actor,
            UUID farmId,
            UUID playerUuid
    ) {
        requireMainThread();
        Farm farm = requirePermission(actor, farmId, FarmPermission.MANAGE_MEMBERS);
        if (farm.ownerUuid().equals(playerUuid)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("owner_cannot_be_removed")
            );
        }
        if (!farm.members().containsKey(playerUuid)) {
            return CompletableFuture.completedFuture(false);
        }
        return repository.removeMember(farmId, playerUuid)
                .thenCompose(removed -> runOnMain(() -> {
                    if (removed) {
                        farms.computeIfPresent(farmId, (ignoredId, current) ->
                                applyMemberRemoval(current, playerUuid));
                    }
                }).thenApply(ignored -> removed));
    }

    public CompletableFuture<StorageLinkResult> linkStorage(
            Player player,
            UUID farmId,
            Block block,
            StorageType type
    ) {
        requireMainThread();
        Optional<StorageTarget> resolved = StorageTargetResolver.resolve(block);
        if (resolved.isEmpty()) {
            return CompletableFuture.completedFuture(
                    StorageLinkResult.failure(StorageLinkResult.Status.NOT_A_CONTAINER));
        }
        StorageTarget target = resolved.get();
        Farm farm = farms.get(farmId);
        StorageLinkResult access = storageAccess(player, farm);
        if (access != null) {
            return CompletableFuture.completedFuture(access);
        }
        FarmStorage assigned = storages.get(target.anchor());
        if (assigned != null) {
            return CompletableFuture.completedFuture(StorageLinkResult.failure(
                    assigned.farmId().equals(farmId)
                            ? StorageLinkResult.Status.ALREADY_LINKED
                            : StorageLinkResult.Status.OTHER_FARM));
        }
        StorageLinkResult targetAccess = storageTargetAccess(player, farm, target);
        if (targetAccess != null) {
            return CompletableFuture.completedFuture(targetAccess);
        }
        if (!pendingStorageChanges.add(target.anchor())) {
            return CompletableFuture.completedFuture(
                    StorageLinkResult.failure(StorageLinkResult.Status.ALREADY_LINKED));
        }
        FarmStorage storage = new FarmStorage(
                UUID.randomUUID(),
                farmId,
                target.anchor(),
                type,
                true,
                Instant.now()
        );
        StorageLinkResult decision = farmService.linkStorage(
                farm, storage, storages.values(), target.positions());
        if (decision.status() != StorageLinkResult.Status.LINKED) {
            pendingStorageChanges.remove(target.anchor());
            return CompletableFuture.completedFuture(decision);
        }
        Farm updated = decision.farm().orElseThrow();
        return repository.linkStorage(storage, updated)
                .handle((ignored, failure) -> {
                    if (failure == null) {
                        return runOnMain(() -> {
                            storages.put(target.anchor(), storage);
                            farms.computeIfPresent(farmId, (ignoredId, current) ->
                                    applyStorageSnapshot(current, updated));
                        }).thenApply(value -> decision);
                    }
                    Throwable cause = unwrap(failure);
                    if (cause instanceof StoragePersistenceException conflict) {
                        return CompletableFuture.completedFuture(
                                StorageLinkResult.failure(conflict.status()));
                    }
                    return CompletableFuture.<StorageLinkResult>failedFuture(cause);
                })
                .thenCompose(result -> result)
                .whenComplete((ignored, failure) ->
                        pendingStorageChanges.remove(target.anchor()));
    }

    public CompletableFuture<StorageLinkResult> unlinkStorage(
            Player player,
            UUID farmId,
            Block block
    ) {
        requireMainThread();
        Optional<StorageTarget> resolved = StorageTargetResolver.resolve(block);
        if (resolved.isEmpty()) {
            return CompletableFuture.completedFuture(
                    StorageLinkResult.failure(StorageLinkResult.Status.NOT_A_CONTAINER));
        }
        StorageTarget target = resolved.get();
        Farm farm = farms.get(farmId);
        StorageLinkResult access = storageAccess(player, farm);
        if (access != null) {
            return CompletableFuture.completedFuture(access);
        }
        FarmStorage storage = storages.get(target.anchor());
        if (storage == null) {
            StorageLinkResult targetAccess = storageTargetAccess(player, farm, target);
            return CompletableFuture.completedFuture(targetAccess == null
                    ? StorageLinkResult.failure(StorageLinkResult.Status.NOT_LINKED)
                    : targetAccess);
        }
        if (!storage.farmId().equals(farmId)) {
            return CompletableFuture.completedFuture(
                    StorageLinkResult.failure(StorageLinkResult.Status.OTHER_FARM));
        }
        StorageLinkResult targetAccess = storageTargetAccess(player, farm, target);
        if (targetAccess != null) {
            return CompletableFuture.completedFuture(targetAccess);
        }
        return unlinkStorage(player, farmId, storage.storageId());
    }

    public CompletableFuture<StorageLinkResult> unlinkStorage(
            Player player,
            UUID farmId,
            UUID storageId
    ) {
        requireMainThread();
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(farmId, "farmId");
        Objects.requireNonNull(storageId, "storageId");
        Farm farm = farms.get(farmId);
        StorageLinkResult access = storageAccess(player, farm);
        if (access != null) {
            return CompletableFuture.completedFuture(access);
        }
        FarmStorage storage = storages.values().stream()
                .filter(candidate -> candidate.storageId().equals(storageId))
                .findFirst()
                .orElse(null);
        if (storage == null) {
            return CompletableFuture.completedFuture(
                    StorageLinkResult.failure(StorageLinkResult.Status.NOT_LINKED));
        }
        if (!storage.farmId().equals(farmId)) {
            return CompletableFuture.completedFuture(
                    StorageLinkResult.failure(StorageLinkResult.Status.OTHER_FARM));
        }
        BlockPosition anchor = storage.position();
        if (!pendingStorageChanges.add(anchor)) {
            return CompletableFuture.completedFuture(
                    StorageLinkResult.failure(StorageLinkResult.Status.ALREADY_LINKED));
        }
        StorageLinkResult decision = farmService.unlinkStorage(farm, storage);
        if (decision.status() != StorageLinkResult.Status.UNLINKED) {
            pendingStorageChanges.remove(anchor);
            return CompletableFuture.completedFuture(decision);
        }
        Farm updated = decision.farm().orElseThrow();
        return repository.unlinkStorage(storage, updated)
                .handle((ignored, failure) -> {
                    if (failure == null) {
                        return runOnMain(() -> {
                            storages.remove(anchor, storage);
                            farms.computeIfPresent(farmId, (ignoredId, current) ->
                                    applyStorageSnapshot(current, updated));
                        }).thenApply(value -> decision);
                    }
                    Throwable cause = unwrap(failure);
                    if (cause instanceof StoragePersistenceException conflict) {
                        return CompletableFuture.completedFuture(
                                StorageLinkResult.failure(conflict.status()));
                    }
                    return CompletableFuture.<StorageLinkResult>failedFuture(cause);
                })
                .thenCompose(result -> result)
                .whenComplete((ignored, failure) -> pendingStorageChanges.remove(anchor));
    }

    private StorageLinkResult storageAccess(Player player, Farm farm) {
        if (farm == null) {
            return StorageLinkResult.failure(StorageLinkResult.Status.NO_OWNED_FARM);
        }
        if (!player.hasPermission("verdantvice.admin")
                && !farm.can(player.getUniqueId(), FarmPermission.MANAGE_STORAGE)) {
            return StorageLinkResult.failure(StorageLinkResult.Status.NO_PERMISSION);
        }
        return null;
    }

    private StorageLinkResult storageTargetAccess(
            Player player,
            Farm farm,
            StorageTarget target
    ) {
        if (target.positions().stream().anyMatch(position -> !farm.region().contains(position))) {
            return StorageLinkResult.failure(StorageLinkResult.Status.OUTSIDE_FARM);
        }
        boolean denied = target.positions().stream()
                .map(this::location)
                .anyMatch(location -> location == null
                        || !regionProtection.canBuild(player, location));
        return denied
                ? StorageLinkResult.failure(StorageLinkResult.Status.PROTECTED_REGION)
                : null;
    }

    private Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    public void setSelection(Player player, int point, Location location) {
        requireMainThread();
        BlockPosition value = position(location);
        selections.compute(player.getUniqueId(), (ignored, current) -> {
            Selection selection = current == null ? new Selection(null, null) : current;
            return point == 1
                    ? new Selection(value, selection.second())
                    : new Selection(selection.first(), value);
        });
    }

    public Optional<Farm> at(Location location) {
        BlockPosition position = position(location);
        ChunkKey key = new ChunkKey(
                position.worldUuid(),
                Math.floorDiv(position.x(), 16),
                Math.floorDiv(position.z(), 16)
        );
        for (UUID id : chunkIndex.getOrDefault(key, Set.of())) {
            Farm farm = farms.get(id);
            if (farm != null && farm.region().contains(position)) {
                return Optional.of(farm);
            }
        }
        return Optional.empty();
    }

    public Optional<Farm> owned(UUID playerUuid) {
        return farms.values().stream()
                .filter(farm -> farm.ownerUuid().equals(playerUuid))
                .findFirst();
    }

    public List<Farm> forPlayer(UUID playerUuid) {
        return farms.values().stream()
                .filter(farm -> farm.members().containsKey(playerUuid))
                .sorted(java.util.Comparator.comparing(Farm::name))
                .toList();
    }

    public Optional<Farm> get(UUID farmId) {
        return Optional.ofNullable(farms.get(farmId));
    }

    public boolean can(Player player, Location location, FarmPermission permission) {
        Optional<Farm> farm = at(location);
        return farm.isEmpty()
                || player.hasPermission("verdantvice.admin")
                || farm.get().can(player.getUniqueId(), permission);
    }

    public Optional<FarmStorage> storageAt(Location location) {
        Objects.requireNonNull(location, "location");
        return storageAt(location.getBlock());
    }

    public Optional<FarmStorage> storageAt(Block block) {
        return StorageTargetResolver.resolve(block)
                .map(StorageTarget::anchor)
                .map(storages::get);
    }

    public boolean isManagerTable(Location location) {
        return managerTables.containsValue(position(location));
    }

    public Collection<FarmStorage> storages(UUID farmId) {
        return storages.values().stream()
                .filter(storage -> storage.farmId().equals(farmId))
                .toList();
    }

    public Collection<Farm> all() {
        return List.copyOf(farms.values());
    }

    private Farm requirePermission(Player player, UUID farmId, FarmPermission permission) {
        Farm farm = farms.get(farmId);
        if (farm == null) {
            throw new IllegalArgumentException("unknown_farm");
        }
        if (!player.hasPermission("verdantvice.admin")
                && !farm.can(player.getUniqueId(), permission)) {
            throw new IllegalStateException("farm_permission_denied");
        }
        return farm;
    }

    private FarmRegion selectedOrDefault(Player player) {
    Selection selected = selections.get(player.getUniqueId());

    if (selected != null && selected.first() != null && selected.second() != null) {
        FarmRegion selectedRegion = FarmRegion.between(
                selected.first(),
                selected.second()
        );

        World world = plugin.getServer().getWorld(selectedRegion.worldUuid());
        if (world == null) {
            throw new IllegalStateException("farm_world_unavailable");
        }

        int minimumY = Math.max(
                world.getMinHeight(),
                selectedRegion.minimumY()
        );

        int maximumY = Math.min(
                world.getMaxHeight() - 1,
                selectedRegion.maximumY()
        );

        if (maximumY - minimumY < 2) {
            maximumY = Math.min(
                    world.getMaxHeight() - 1,
                    minimumY + 16
            );
        }

        return new FarmRegion(
                selectedRegion.worldUuid(),
                selectedRegion.minimumX(),
                minimumY,
                selectedRegion.minimumZ(),
                selectedRegion.maximumX(),
                maximumY,
                selectedRegion.maximumZ()
        );
    }

    Location location = player.getLocation();
    World world = location.getWorld();
    int radius = 8;

    return new FarmRegion(
            world.getUID(),
            location.getBlockX() - radius,
            Math.max(world.getMinHeight(), location.getBlockY()),
            location.getBlockZ() - radius,
            location.getBlockX() + radius,
            Math.min(world.getMaxHeight() - 1, location.getBlockY() + radius * 2),
            location.getBlockZ() + radius
    );
}

    private void validateRegion(FarmRegion region) {
        int width = region.maximumX() - region.minimumX() + 1;
        int height = region.maximumY() - region.minimumY() + 1;
        int depth = region.maximumZ() - region.minimumZ() + 1;
        if (width > MAXIMUM_REGION_SIDE || height > MAXIMUM_REGION_SIDE
                || depth > MAXIMUM_REGION_SIDE
                || region.blockVolume() > MAXIMUM_REGION_VOLUME) {
            throw new IllegalArgumentException("farm_region_too_large");
        }
    }

    private String sanitizeName(String source) {
        if (source == null) {
            throw new IllegalArgumentException("farm_name_required");
        }
        String normalized = source.strip().replaceAll("\\s+", " ");
        if (normalized.length() < 3 || normalized.length() > 48
                || normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("invalid_farm_name");
        }
        return normalized;
    }

    private void index(Farm farm) {
        for (int x = Math.floorDiv(farm.region().minimumX(), 16);
             x <= Math.floorDiv(farm.region().maximumX(), 16); x++) {
            for (int z = Math.floorDiv(farm.region().minimumZ(), 16);
                 z <= Math.floorDiv(farm.region().maximumZ(), 16); z++) {
                chunkIndex.computeIfAbsent(
                        new ChunkKey(farm.region().worldUuid(), x, z),
                        ignored -> ConcurrentHashMap.newKeySet()
                ).add(farm.farmId());
            }
        }
    }

    private void deindex(Farm farm) {
        chunkIndex.values().forEach(ids -> ids.remove(farm.farmId()));
        chunkIndex.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    private BlockPosition tablePosition(FarmRegion region) {
    World world = plugin.getServer().getWorld(region.worldUuid());
    if (world == null) {
        throw new IllegalStateException("farm_world_unavailable");
    }

    int centerX = region.minimumX()
            + (region.maximumX() - region.minimumX()) / 2;

    int centerZ = region.minimumZ()
            + (region.maximumZ() - region.minimumZ()) / 2;

    int minimumY = Math.max(
            region.minimumY(),
            world.getMinHeight() + 1
    );

    int maximumY = Math.min(
            region.maximumY(),
            world.getMaxHeight() - 1
    );

    for (int y = minimumY; y <= maximumY; y++) {
        Block candidate = world.getBlockAt(centerX, y, centerZ);
        Block support = world.getBlockAt(centerX, y - 1, centerZ);

        boolean positionAvailable =
                candidate.isReplaceable()
                        || candidate.getType() == Material.LECTERN;

        if (positionAvailable && support.getType().isSolid()) {
            return new BlockPosition(
                    region.worldUuid(),
                    centerX,
                    y,
                    centerZ
            );
        }
    }

    throw new IllegalStateException("manager_table_blocked");
}

    private BlockPosition position(Location location) {
        return new BlockPosition(
                Objects.requireNonNull(location.getWorld()).getUID(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ()
        );
    }

    private Location location(BlockPosition position) {
        World world = plugin.getServer().getWorld(position.worldUuid());
        return world == null ? null : new Location(world, position.x(), position.y(), position.z());
    }

    static Farm applyMemberRemoval(Farm current, UUID playerUuid) {
        Map<UUID, FarmMember> members = new HashMap<>(current.members());
        members.remove(playerUuid);
        return copy(current, members, current.linkedStorage(), current.salaryStorage());
    }

    static Farm applyStorageSnapshot(Farm current, Farm storageSnapshot) {
        if (!current.farmId().equals(storageSnapshot.farmId())) {
            throw new IllegalArgumentException("farm_snapshot_mismatch");
        }
        return copy(current, current.members(), storageSnapshot.linkedStorage(),
                storageSnapshot.salaryStorage());
    }

    private static Farm copy(
            Farm farm,
            Map<UUID, FarmMember> members,
            Set<UUID> linkedStorage,
            Optional<UUID> salary
    ) {
        return new Farm(
                farm.farmId(),
                farm.ownerUuid(),
                farm.name(),
                farm.region(),
                farm.level(),
                farm.plantLimit(),
                farm.workerLimit(),
                linkedStorage,
                farm.linkedDryingRacks(),
                farm.linkedCuringContainers(),
                salary,
                farm.createdAt(),
                farm.settings(),
                members
        );
    }

    private CompletableFuture<Void> runOnMain(Runnable action) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                action.run();
                future.complete(null);
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        });
        return future;
    }

    private void requireMainThread() {
        if (!plugin.getServer().isPrimaryThread()) {
            throw new IllegalStateException("Farm operations must start on the server thread");
        }
    }

    private record Selection(BlockPosition first, BlockPosition second) {
    }

    private record ChunkKey(UUID world, int x, int z) {
    }
}
