package com.verdantvice.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.verdantvice.common.BlockPosition;
import com.verdantvice.database.DatabaseManager;
import com.verdantvice.farm.Farm;
import com.verdantvice.farm.FarmMember;
import com.verdantvice.farm.FarmPermission;
import com.verdantvice.farm.FarmRegion;
import com.verdantvice.farm.FarmRole;
import com.verdantvice.farm.FarmSettings;
import com.verdantvice.farm.FarmStorage;
import com.verdantvice.farm.StorageLinkResult;
import com.verdantvice.farm.StorageType;
import com.verdantvice.processing.QualityGrade;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class FarmRepository {
    private final DatabaseManager database;
    private final Map<UUID, Long> versions = new ConcurrentHashMap<>();

    public FarmRepository(DatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public CompletableFuture<FarmSnapshot> loadAll() {
        CompletableFuture<List<FarmRow>> farms = database.query(
                "SELECT * FROM farms",
                this::mapFarm
        );
        CompletableFuture<List<MemberRow>> members = database.query(
                "SELECT * FROM farm_members",
                this::mapMember
        );
        CompletableFuture<List<FarmStorage>> storages = database.query(
                "SELECT * FROM farm_storages",
                this::mapStorage
        );
        return farms.thenCombine(members, FarmParts::new)
                .thenCombine(storages, (parts, storageRows) ->
                        assemble(parts.farms(), parts.members(), storageRows));
    }

    public CompletableFuture<Void> create(Farm farm) {
        return create(farm, new BlockPosition(
                farm.region().worldUuid(),
                farm.region().minimumX()
                        + (farm.region().maximumX() - farm.region().minimumX()) / 2,
                farm.region().minimumY(),
                farm.region().minimumZ()
                        + (farm.region().maximumZ() - farm.region().minimumZ()) / 2
        ));
    }

    public CompletableFuture<Void> create(Farm farm, BlockPosition managerTable) {
        Objects.requireNonNull(farm, "farm");
        Objects.requireNonNull(managerTable, "managerTable");
        if (!farm.region().contains(managerTable)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("manager_table_outside_farm"));
        }
        return database.transaction(session -> {
            PlayerDataRepository.ensure(
                    session,
                    farm.ownerUuid(),
                    null,
                    farm.createdAt()
            );
            session.update(
                    "INSERT INTO farms "
                            + "(farm_id, owner_uuid, farm_name, world_uuid, table_x, table_y, "
                            + "table_z, farm_level, reputation, settings_json, created_at, "
                            + "updated_at, row_version) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, 0)",
                    farm.farmId(),
                    farm.ownerUuid(),
                    farm.name(),
                    farm.region().worldUuid(),
                    managerTable.x(),
                    managerTable.y(),
                    managerTable.z(),
                    farm.level(),
                    settings(farm),
                    farm.createdAt(),
                    farm.createdAt()
            );
            insertMember(session, farm.farmId(), farm.members().get(farm.ownerUuid()));
            return null;
        }).thenRun(() -> versions.put(farm.farmId(), 0L));
    }

    public CompletableFuture<Void> save(Farm farm) {
        long expected = versions.getOrDefault(farm.farmId(), 0L);
        return database.transaction(session -> {
            int changed = session.update(
                    "UPDATE farms SET farm_name=?, farm_level=?, settings_json=?, "
                            + "updated_at=?, row_version=row_version+1 "
                            + "WHERE farm_id=? AND row_version=?",
                    farm.name(),
                    farm.level(),
                    settings(farm),
                    Instant.now(),
                    farm.farmId(),
                    expected
            );
            if (changed != 1) {
                throw new SQLException("Concurrent farm modification: " + farm.farmId());
            }
            return null;
        }).thenRun(() -> versions.replace(farm.farmId(), expected, expected + 1));
    }

    public CompletableFuture<Void> putMember(UUID farmId, FarmMember member) {
        return database.transaction(session -> {
            PlayerDataRepository.ensure(
                    session,
                    member.playerUuid(),
                    null,
                    member.joinedAt()
            );
            int removed = session.update(
                    "DELETE FROM farm_members WHERE farm_id=? AND player_uuid=?",
                    farmId,
                    member.playerUuid()
            );
            insertMember(session, farmId, member);
            return null;
        });
    }

    public CompletableFuture<Boolean> removeMember(UUID farmId, UUID playerUuid) {
        return database.update(
                "DELETE FROM farm_members WHERE farm_id=? AND player_uuid=?",
                farmId,
                playerUuid
        ).thenApply(changed -> changed == 1);
    }

    public CompletableFuture<Void> linkStorage(FarmStorage storage, Farm updatedFarm) {
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(updatedFarm, "updatedFarm");
        long expected = versions.getOrDefault(updatedFarm.farmId(), 0L);
        Instant now = Instant.now();
        return database.transaction(session -> {
            Optional<StorageOwner> existing = session.queryOne(
                    "SELECT storage_id, farm_id FROM farm_storages "
                            + "WHERE world_uuid=? AND block_x=? AND block_y=? AND block_z=?",
                    result -> new StorageOwner(
                            UUID.fromString(result.getString("storage_id")),
                            UUID.fromString(result.getString("farm_id"))
                    ),
                    storage.position().worldUuid(),
                    storage.position().x(),
                    storage.position().y(),
                    storage.position().z()
            );
            if (existing.isPresent()) {
                throw new StoragePersistenceException(
                        existing.get().farmId().equals(storage.farmId())
                                ? StorageLinkResult.Status.ALREADY_LINKED
                                : StorageLinkResult.Status.OTHER_FARM
                );
            }
            if (storage.type() == StorageType.SALARY_STORAGE && session.exists(
                    "SELECT storage_id FROM farm_storages "
                            + "WHERE farm_id=? AND storage_type=?",
                    storage.farmId(),
                    StorageType.SALARY_STORAGE
            )) {
                throw new StoragePersistenceException(
                        StorageLinkResult.Status.SALARY_STORAGE_ALREADY_EXISTS);
            }
            session.update(
                    "INSERT INTO farm_storages "
                            + "(storage_id, farm_id, storage_type, world_uuid, block_x, block_y, "
                            + "block_z, created_at, updated_at, row_version) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)",
                    storage.storageId(),
                    storage.farmId(),
                    storage.type(),
                    storage.position().worldUuid(),
                    storage.position().x(),
                    storage.position().y(),
                    storage.position().z(),
                    storage.createdAt(),
                    storage.createdAt()
            );
            int changed = session.update(
                    "UPDATE farms SET updated_at=?, row_version=row_version+1 "
                            + "WHERE farm_id=? AND row_version=?",
                    now,
                    updatedFarm.farmId(),
                    expected
            );
            if (changed != 1) {
                throw new SQLException("Concurrent farm storage modification: "
                        + updatedFarm.farmId());
            }
            return null;
        }).thenRun(() -> versions.replace(updatedFarm.farmId(), expected, expected + 1L));
    }

    public CompletableFuture<Void> unlinkStorage(FarmStorage storage, Farm updatedFarm) {
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(updatedFarm, "updatedFarm");
        long expected = versions.getOrDefault(updatedFarm.farmId(), 0L);
        Instant now = Instant.now();
        return database.transaction(session -> {
            int removed = session.update(
                    "DELETE FROM farm_storages WHERE storage_id=? AND farm_id=?",
                    storage.storageId(),
                    storage.farmId()
            );
            if (removed != 1) {
                throw new StoragePersistenceException(StorageLinkResult.Status.NOT_LINKED);
            }
            int changed = session.update(
                    "UPDATE farms SET updated_at=?, row_version=row_version+1 "
                            + "WHERE farm_id=? AND row_version=?",
                    now,
                    updatedFarm.farmId(),
                    expected
            );
            if (changed != 1) {
                throw new SQLException("Concurrent farm storage modification: "
                        + updatedFarm.farmId());
            }
            return null;
        }).thenRun(() -> versions.replace(updatedFarm.farmId(), expected, expected + 1L));
    }

    public CompletableFuture<Boolean> delete(UUID farmId, UUID ownerUuid) {
        return database.update(
                "DELETE FROM farms WHERE farm_id=? AND owner_uuid=?",
                farmId,
                ownerUuid
        ).thenApply(changed -> {
            if (changed == 1) {
                versions.remove(farmId);
                return true;
            }
            return false;
        });
    }

    private FarmSnapshot assemble(
            List<FarmRow> farmRows,
            List<MemberRow> memberRows,
            List<FarmStorage> storageRows
    ) {
        Map<UUID, Map<UUID, FarmMember>> members = new HashMap<>();
        for (MemberRow row : memberRows) {
            members.computeIfAbsent(row.farmId(), ignored -> new LinkedHashMap<>())
                    .put(row.member().playerUuid(), row.member());
        }
        Map<UUID, Set<FarmStorage>> storages = new HashMap<>();
        for (FarmStorage storage : storageRows) {
            storages.computeIfAbsent(storage.farmId(), ignored -> new HashSet<>())
                    .add(storage);
        }

        List<Farm> farms = new ArrayList<>(farmRows.size());
        for (FarmRow row : farmRows) {
            Set<FarmStorage> linked = storages.getOrDefault(row.id(), Set.of());
            Set<UUID> linkedIds = linked.stream().map(FarmStorage::storageId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            Optional<UUID> salary = linked.stream()
                    .filter(storage -> storage.type() == StorageType.SALARY_STORAGE)
                    .map(FarmStorage::storageId)
                    .findFirst();
            Farm farm = new Farm(
                    row.id(),
                    row.owner(),
                    row.name(),
                    row.region(),
                    row.level(),
                    row.plantLimit(),
                    row.workerLimit(),
                    linkedIds,
                    Set.of(),
                    Set.of(),
                    salary,
                    row.createdAt(),
                    row.settings(),
                    members.getOrDefault(row.id(), Map.of())
            );
            farms.add(farm);
            versions.put(row.id(), row.version());
        }
        Map<UUID, BlockPosition> tables = new LinkedHashMap<>();
        farmRows.forEach(row -> tables.put(row.id(), row.tablePosition()));
        return new FarmSnapshot(List.copyOf(farms), Set.copyOf(storageRows), Map.copyOf(tables));
    }

    private FarmRow mapFarm(ResultSet result) throws SQLException {
        UUID id = UUID.fromString(result.getString("farm_id"));
        UUID owner = UUID.fromString(result.getString("owner_uuid"));
        UUID world = UUID.fromString(result.getString("world_uuid"));
        JsonObject json = JsonParser.parseString(result.getString("settings_json"))
                .getAsJsonObject();
        FarmRegion region = new FarmRegion(
                world,
                integer(json, "minimumX"),
                integer(json, "minimumY"),
                integer(json, "minimumZ"),
                integer(json, "maximumX"),
                integer(json, "maximumY"),
                integer(json, "maximumZ")
        );
        FarmSettings settings = new FarmSettings(
                bool(json, "workersMayPlant"),
                bool(json, "allowRareSeeds"),
                bool(json, "legendarySeedRequiresApproval"),
                QualityGrade.valueOf(text(json, "minimumHarvestQuality")),
                integer(json, "maximumPlantsPerWorker")
        );
        return new FarmRow(
                id,
                owner,
                result.getString("farm_name"),
                region,
                result.getInt("farm_level"),
                new BlockPosition(
                        world,
                        result.getInt("table_x"),
                        result.getInt("table_y"),
                        result.getInt("table_z")
                ),
                integer(json, "plantLimit"),
                integer(json, "workerLimit"),
                Instant.ofEpochMilli(result.getLong("created_at")),
                settings,
                result.getLong("row_version")
        );
    }

    private MemberRow mapMember(ResultSet result) throws SQLException {
        UUID farmId = UUID.fromString(result.getString("farm_id"));
        UUID playerId = UUID.fromString(result.getString("player_uuid"));
        JsonObject permissions = JsonParser.parseString(
                result.getString("permissions_json")
        ).getAsJsonObject();
        FarmMember member = new FarmMember(
                playerId,
                FarmRole.valueOf(result.getString("member_role")),
                permissions(permissions, "granted"),
                permissions(permissions, "denied"),
                Instant.ofEpochMilli(result.getLong("joined_at"))
        );
        return new MemberRow(farmId, member);
    }

    private FarmStorage mapStorage(ResultSet result) throws SQLException {
        return new FarmStorage(
                UUID.fromString(result.getString("storage_id")),
                UUID.fromString(result.getString("farm_id")),
                new BlockPosition(
                        UUID.fromString(result.getString("world_uuid")),
                        result.getInt("block_x"),
                        result.getInt("block_y"),
                        result.getInt("block_z")
                ),
                StorageType.valueOf(result.getString("storage_type")),
                true,
                Instant.ofEpochMilli(result.getLong("created_at"))
        );
    }

    private String settings(Farm farm) {
        JsonObject json = new JsonObject();
        json.addProperty("minimumX", farm.region().minimumX());
        json.addProperty("minimumY", farm.region().minimumY());
        json.addProperty("minimumZ", farm.region().minimumZ());
        json.addProperty("maximumX", farm.region().maximumX());
        json.addProperty("maximumY", farm.region().maximumY());
        json.addProperty("maximumZ", farm.region().maximumZ());
        json.addProperty("plantLimit", farm.plantLimit());
        json.addProperty("workerLimit", farm.workerLimit());
        json.addProperty("workersMayPlant", farm.settings().workersMayPlant());
        json.addProperty("allowRareSeeds", farm.settings().allowRareSeeds());
        json.addProperty(
                "legendarySeedRequiresApproval",
                farm.settings().legendarySeedRequiresApproval()
        );
        json.addProperty(
                "minimumHarvestQuality",
                farm.settings().minimumHarvestQuality().name()
        );
        json.addProperty(
                "maximumPlantsPerWorker",
                farm.settings().maximumPlantsPerWorker()
        );
        return json.toString();
    }

    private void insertMember(
            com.verdantvice.database.SqlSession session,
            UUID farmId,
            FarmMember member
    ) throws SQLException {
        JsonObject permissions = new JsonObject();
        permissions.add("granted", enumArray(member.grantedOverrides()));
        permissions.add("denied", enumArray(member.deniedOverrides()));
        session.update(
                "INSERT INTO farm_members "
                        + "(farm_id, player_uuid, member_role, permissions_json, joined_at, "
                        + "row_version) VALUES (?, ?, ?, ?, ?, 0)",
                farmId,
                member.playerUuid(),
                member.role(),
                permissions.toString(),
                member.joinedAt()
        );
    }

    private JsonArray enumArray(Set<? extends Enum<?>> values) {
        JsonArray array = new JsonArray();
        values.stream().map(Enum::name).sorted().forEach(array::add);
        return array;
    }

    private Set<FarmPermission> permissions(JsonObject json, String key) {
        EnumSet<FarmPermission> result = EnumSet.noneOf(FarmPermission.class);
        if (json.has(key)) {
            json.getAsJsonArray(key).forEach(value ->
                    result.add(FarmPermission.valueOf(value.getAsString())));
        }
        return Set.copyOf(result);
    }

    private int integer(JsonObject json, String key) {
        return json.get(key).getAsInt();
    }

    private boolean bool(JsonObject json, String key) {
        return json.get(key).getAsBoolean();
    }

    private String text(JsonObject json, String key) {
        return json.get(key).getAsString();
    }

    public record FarmSnapshot(
            List<Farm> farms,
            Set<FarmStorage> storages,
            Map<UUID, BlockPosition> tablePositions
    ) {
        public FarmSnapshot {
            farms = List.copyOf(farms);
            storages = Set.copyOf(storages);
            tablePositions = Map.copyOf(tablePositions);
        }
    }

    private record FarmRow(
            UUID id,
            UUID owner,
            String name,
            FarmRegion region,
            int level,
            BlockPosition tablePosition,
            int plantLimit,
            int workerLimit,
            Instant createdAt,
            FarmSettings settings,
            long version
    ) {
    }

    private record MemberRow(UUID farmId, FarmMember member) {
    }

    private record FarmParts(List<FarmRow> farms, List<MemberRow> members) {
    }

    private record StorageOwner(UUID storageId, UUID farmId) {
    }

}
