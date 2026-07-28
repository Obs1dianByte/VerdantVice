from __future__ import annotations

import re
import sys
from pathlib import Path


def resolve_root() -> Path:
    if len(sys.argv) > 1:
        candidate = Path(sys.argv[1]).expanduser().resolve()
    else:
        candidate = Path.cwd().resolve()
    if not (candidate / "settings.gradle.kts").is_file():
        raise SystemExit(
            "Podaj katalog repozytorium, np.: py apply_runtime_fix.py D:\\adobe\\VerdantVice"
        )
    return candidate


ROOT = resolve_root()


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content.replace("\r\n", "\n"), encoding="utf-8", newline="\n")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count == 0 and new in text:
        return text
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def replace_between(text: str, start: str, end: str, replacement: str, label: str) -> str:
    start_index = text.find(start)
    if start_index < 0:
        if replacement.strip() in text:
            return text
        raise RuntimeError(f"{label}: start marker not found")
    end_index = text.find(end, start_index + len(start))
    if end_index < 0:
        raise RuntimeError(f"{label}: end marker not found")
    return text[:start_index] + replacement.rstrip() + "\n\n" + text[end_index:]


def add_java_file(path: str, content: str) -> None:
    write(path, content.strip() + "\n")


def patch_plant_repository() -> None:
    path = "verdantvice-paper/src/main/java/com/verdantvice/storage/PlantRepository.java"
    text = read(path)
    text = replace_once(
        text,
        "import java.util.concurrent.ConcurrentHashMap;\n",
        "import java.util.concurrent.ConcurrentHashMap;\n",
        "PlantRepository imports",
    )
    text = replace_once(
        text,
        "    private final DatabaseManager database;\n"
        "    private final Map<UUID, Long> versions = new ConcurrentHashMap<>();\n",
        "    private final DatabaseManager database;\n"
        "    private final Map<UUID, Long> versions = new ConcurrentHashMap<>();\n"
        "    private final Map<UUID, Instant> persistedUpdates = new ConcurrentHashMap<>();\n"
        "    private final PerKeyAsyncQueue<UUID> writeQueue = new PerKeyAsyncQueue<>();\n",
        "PlantRepository fields",
    )

    load_all = '''    public CompletableFuture<List<StoredPlant>> loadAll() {
        return database.query(SELECT_ALL, this::map).thenApply(plants -> {
            versions.clear();
            persistedUpdates.clear();
            plants.forEach(stored -> {
                UUID plantId = stored.plant().uniqueId();
                versions.put(plantId, stored.rowVersion());
                persistedUpdates.put(plantId, stored.plant().lastUpdatedAt());
            });
            return plants;
        });
    }'''
    text = replace_between(
        text,
        "    public CompletableFuture<List<StoredPlant>> loadAll() {",
        "    public CompletableFuture<Optional<StoredPlant>> find",
        load_all,
        "PlantRepository.loadAll",
    )

    find_method = '''    public CompletableFuture<Optional<StoredPlant>> find(UUID plantId) {
        return database.queryOne(
                SELECT_ALL + " WHERE p.plant_uuid=?",
                this::map,
                plantId
        ).thenApply(optional -> {
            optional.ifPresent(stored -> {
                versions.put(plantId, stored.rowVersion());
                persistedUpdates.put(plantId, stored.plant().lastUpdatedAt());
            });
            return optional;
        });
    }'''
    text = replace_between(
        text,
        "    public CompletableFuture<Optional<StoredPlant>> find(UUID plantId) {",
        "    public CompletableFuture<Void> create",
        find_method,
        "PlantRepository.find",
    )

    create_method = '''    public CompletableFuture<Void> create(Plant plant, GeneticProfile genetics) {
        Objects.requireNonNull(plant, "plant");
        Objects.requireNonNull(genetics, "genetics");
        return writeQueue.submit(
                plant.uniqueId(),
                () -> createNow(plant, genetics)
        );
    }

    private CompletableFuture<Void> createNow(Plant plant, GeneticProfile genetics) {
        String traits = DomainJson.genetics(genetics);
        String geneticSignature = sha256(traits);
        return database.transaction(session -> {
            PlayerDataRepository.ensure(
                    session,
                    plant.ownerUuid(),
                    null,
                    plant.plantedAt()
            );
            if (!session.exists(
                    "SELECT 1 FROM genetic_profiles WHERE genetic_profile_id=?",
                    genetics.id()
            )) {
                session.update(
                        "INSERT INTO genetic_profiles "
                                + "(genetic_profile_id, owner_uuid, genetic_class, traits_json, "
                                + "parent_profiles_json, signature, schema_version, created_at, "
                                + "updated_at, row_version) VALUES (?, ?, ?, ?, NULL, ?, 1, ?, ?, 0)",
                        genetics.id(),
                        plant.ownerUuid(),
                        genetics.geneticClass(),
                        traits,
                        geneticSignature,
                        genetics.createdAt(),
                        genetics.createdAt()
                );
            }
            insertPlant(session, plant);
            session.update(
                    "INSERT INTO plant_events "
                            + "(plant_uuid, event_type, event_data_json, actor_uuid, "
                            + "idempotency_key, created_at) VALUES (?, 'PLANTED', ?, ?, ?, ?)",
                    plant.uniqueId(),
                    DomainJson.stringMap(Map.of("strain", plant.strainId())),
                    plant.ownerUuid(),
                    "plant:" + plant.uniqueId(),
                    plant.plantedAt()
            );
            return null;
        }).thenRun(() -> {
            versions.put(plant.uniqueId(), 0L);
            persistedUpdates.put(plant.uniqueId(), plant.lastUpdatedAt());
        });
    }'''
    text = replace_between(
        text,
        "    public CompletableFuture<Void> create(Plant plant, GeneticProfile genetics) {",
        "    public CompletableFuture<Void> save",
        create_method,
        "PlantRepository.create",
    )

    save_method = '''    public CompletableFuture<Void> save(Plant plant, String eventType) {
        return saveVersioned(plant, eventType).thenAccept(ignored -> {
        });
    }

    public CompletableFuture<Long> saveVersioned(Plant plant, String eventType) {
        Objects.requireNonNull(plant, "plant");
        return writeQueue.submit(
                plant.uniqueId(),
                () -> saveNow(plant, eventType)
        );
    }

    private CompletableFuture<Long> saveNow(Plant plant, String eventType) {
        UUID plantId = plant.uniqueId();
        Instant persistedAt = persistedUpdates.get(plantId);
        if (persistedAt != null && plant.lastUpdatedAt().isBefore(persistedAt)) {
            return CompletableFuture.failedFuture(
                    new ConcurrentPlantModificationException(
                            "Refusing stale plant update for " + plantId
                                    + "; persisted=" + persistedAt
                                    + ", attempted=" + plant.lastUpdatedAt()
                    )
            );
        }
        long expected = versions.getOrDefault(plantId, 0L);
        return database.transaction(session -> {
            int changed = session.update(
                    """
                    UPDATE plants SET
                      growth_stage=?, growth_progress=?, health=?, hydration=?, nutrition=?,
                      light_level=?, stress=?, cleanliness=?, disease_id=?, pest_level=?,
                      resistance=?, yield_potential=?, quality_potential=?,
                      last_updated_at=?, last_watered_at=?, harvest_window_start=?,
                      harvest_window_end=?, farm_id=?, worker_assigned=?,
                      row_version=row_version+1
                    WHERE plant_uuid=? AND row_version=?
                    """,
                    plant.growthStage(),
                    plant.growthProgress(),
                    plant.health(),
                    plant.hydration(),
                    plant.nutrition(),
                    plant.lightLevel(),
                    plant.stress(),
                    plant.cleanliness(),
                    plant.diseaseId().orElse(null),
                    plant.pestLevel(),
                    plant.resistance(),
                    plant.yieldPotential(),
                    plant.qualityPotential(),
                    plant.lastUpdatedAt(),
                    plant.lastWateredAt(),
                    plant.harvestWindowStart(),
                    plant.harvestWindowEnd(),
                    plant.farmId().orElse(null),
                    plant.workerAssigned().orElse(null),
                    plantId,
                    expected
            );
            if (changed != 1) {
                throw new ConcurrentPlantModificationException(
                        "Concurrent plant modification: " + plantId
                                + " (expected row_version=" + expected + ')'
                );
            }
            if (eventType != null && !eventType.isBlank()) {
                session.update(
                        "INSERT INTO plant_events "
                                + "(plant_uuid, event_type, event_data_json, actor_uuid, "
                                + "idempotency_key, created_at) VALUES (?, ?, NULL, NULL, NULL, ?)",
                        plantId,
                        eventType,
                        plant.lastUpdatedAt()
                );
            }
            return null;
        }).thenApply(ignored -> {
            long nextVersion = expected + 1L;
            versions.put(plantId, nextVersion);
            persistedUpdates.put(plantId, plant.lastUpdatedAt());
            return nextVersion;
        });
    }'''
    text = replace_between(
        text,
        "    public CompletableFuture<Void> save(Plant plant, String eventType) {",
        "    public CompletableFuture<Boolean> delete",
        save_method,
        "PlantRepository.save",
    )

    delete_method = '''    public CompletableFuture<Boolean> delete(UUID plantId, String reason, UUID actor) {
        Objects.requireNonNull(plantId, "plantId");
        return writeQueue.submit(
                plantId,
                () -> deleteNow(plantId, reason, actor)
        );
    }

    private CompletableFuture<Boolean> deleteNow(UUID plantId, String reason, UUID actor) {
        long expected = versions.getOrDefault(plantId, 0L);
        return database.transaction(session -> {
            if (reason != null) {
                session.update(
                        "INSERT INTO plant_events "
                                + "(plant_uuid, event_type, event_data_json, actor_uuid, "
                                + "idempotency_key, created_at) VALUES (?, 'REMOVED', ?, ?, ?, ?)",
                        plantId,
                        DomainJson.stringMap(Map.of("reason", reason)),
                        actor,
                        "remove:" + plantId,
                        Instant.now()
                );
            }
            int changed = session.update(
                    "DELETE FROM plants WHERE plant_uuid=? AND row_version=?",
                    plantId,
                    expected
            );
            return changed == 1;
        }).thenApply(deleted -> {
            if (deleted) {
                versions.remove(plantId);
                persistedUpdates.remove(plantId);
            }
            return deleted;
        });
    }

    int activeWriteQueues() {
        return writeQueue.activeKeys();
    }'''
    text = replace_between(
        text,
        "    public CompletableFuture<Boolean> delete(UUID plantId, String reason, UUID actor) {",
        "    private void insertPlant",
        delete_method,
        "PlantRepository.delete",
    )
    write(path, text)


def patch_plant_manager() -> None:
    path = "verdantvice-paper/src/main/java/com/verdantvice/plant/PlantManager.java"
    text = read(path)
    text = replace_once(
        text,
        "    private final ArrayDeque<UUID> updateQueue = new ArrayDeque<>();\n",
        "    private final ArrayDeque<UUID> updateQueue = new ArrayDeque<>();\n"
        "    private final PlantWriteTracker writeTracker = new PlantWriteTracker();\n",
        "PlantManager tracker field",
    )
    text = replace_once(
        text,
        "        pendingStations.clear();\n",
        "        pendingStations.clear();\n"
        "        writeTracker.clear();\n",
        "PlantManager.stop",
    )

    set_stage = '''    public CompletableFuture<Plant> setStage(UUID plantId, GrowthStage stage) {
        requireMainThread();
        PlantRepository.StoredPlant stored = plants.get(plantId);
        if (stored == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("unknown_plant")
            );
        }
        Plant current = stored.plant();
        double progress = switch (stage) {
            case SEED -> 0.0;
            case SPROUT -> 2.0;
            case SEEDLING -> 12.0;
            case VEGETATIVE -> 30.0;
            case MATURING -> 65.0;
            case READY -> 100.0;
            case OVERRIPE, SPOILED -> 120.0;
        };
        Plant updated = current.withSimulation(
                stage,
                progress,
                stage == GrowthStage.SPOILED ? 0.0 : current.health(),
                current.hydration(),
                current.nutrition(),
                current.stress(),
                current.yieldPotential(),
                stage == GrowthStage.SPOILED ? 0.0 : current.qualityPotential(),
                Instant.now()
        );
        return persistWorkerUpdate(stored, updated, "ADMIN_STAGE");
    }'''
    text = replace_between(
        text,
        "    public CompletableFuture<Plant> setStage(UUID plantId, GrowthStage stage) {",
        "    public void inspect(Player player, UUID plantId, boolean advanced)",
        set_stage,
        "PlantManager.setStage",
    )

    process_cycle = '''    private void processCycle() {
        requireMainThread();
        int maximum = Math.max(1, configs.main().getInt("performance.plants-per-cycle", 100));
        Instant now = Instant.now();
        for (int processed = 0; processed < maximum && !updateQueue.isEmpty(); processed++) {
            UUID id = updateQueue.removeFirst();
            updateQueue.addLast(id);
            if (writeTracker.hasPending(id)) {
                continue;
            }
            PlantRepository.StoredPlant stored = plants.get(id);
            if (stored == null) {
                continue;
            }
            Plant plant = stored.plant();
            Strain strain;
            try {
                strain = strains.require(plant.strainId());
            } catch (IllegalArgumentException missingStrain) {
                plugin.getLogger().log(Level.WARNING,
                        "Plant {0} references missing strain {1}",
                        new Object[]{plant.uniqueId(), plant.strainId()});
                continue;
            }
            World world = Bukkit.getWorld(plant.position().worldUuid());
            boolean loaded = world != null && world.isChunkLoaded(
                    Math.floorDiv(plant.position().x(), 16),
                    Math.floorDiv(plant.position().z(), 16)
            );
            PlantUpdate update = growthService.update(
                    plant,
                    strain,
                    stored.genetics(),
                    now,
                    growthPolicy(),
                    loaded
            );
            Plant updated = update.plant();
            if (loaded) {
                Location at = location(updated.position());
                if (at != null) {
                    updated = updated.withEnvironment(
                            at.getBlock().getLightLevel(),
                            updated.cleanliness(),
                            updated.diseaseId(),
                            updated.pestLevel()
                    );
                }
            }
            updated = maybeDevelopProblem(updated);
            if (sameSimulation(plant, updated)) {
                continue;
            }
            persistUpdate(stored, updated, "GROWTH_UPDATED");
        }
    }'''
    text = replace_between(
        text,
        "    private void processCycle() {",
        "    private Plant maybeDevelopProblem(Plant plant)",
        process_cycle,
        "PlantManager.processCycle",
    )

    persistence_methods = '''    private void persistUpdate(
            PlantRepository.StoredPlant oldStored,
            Plant updated,
            String eventType
    ) {
        persistStoredUpdate(oldStored, updated, eventType).whenComplete((ignored, failure) -> {
            UUID plantId = updated.uniqueId();
            if (failure == null) {
                writeTracker.markSuccess(plantId);
                return;
            }
            Throwable cause = unwrapFailure(failure);
            if (!writeTracker.shouldLogFailure(plantId)) {
                return;
            }
            if (cause instanceof com.verdantvice.storage.ConcurrentPlantModificationException) {
                plugin.getLogger().log(
                        Level.WARNING,
                        "Plant persistence conflict for {0}: {1}",
                        new Object[]{plantId, cause.getMessage()}
                );
            } else {
                plugin.getLogger().log(
                        Level.WARNING,
                        "Could not persist plant " + plantId,
                        cause
                );
            }
        });
    }

    private CompletableFuture<Plant> persistWorkerUpdate(
            PlantRepository.StoredPlant oldStored,
            Plant updated,
            String eventType
    ) {
        return persistStoredUpdate(oldStored, updated, eventType);
    }

    private CompletableFuture<Plant> persistStoredUpdate(
            PlantRepository.StoredPlant oldStored,
            Plant updated,
            String eventType
    ) {
        requireMainThread();
        UUID plantId = updated.uniqueId();
        PlantRepository.StoredPlant current = plants.get(plantId);
        if (current == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("target_unavailable")
            );
        }
        if (current.plant().lastUpdatedAt().isAfter(updated.lastUpdatedAt())) {
            return CompletableFuture.failedFuture(
                    new com.verdantvice.storage.ConcurrentPlantModificationException(
                            "A newer plant state is already cached for " + plantId
                    )
            );
        }

        writeTracker.begin(plantId);
        PlantRepository.StoredPlant optimistic = new PlantRepository.StoredPlant(
                updated,
                oldStored.genetics(),
                current.rowVersion()
        );
        plants.put(plantId, optimistic);

        CompletableFuture<Plant> result = new CompletableFuture<>();
        repository.saveVersioned(updated, eventType).whenComplete((rowVersion, failure) ->
                onMain(() -> {
                    try {
                        if (failure != null) {
                            Throwable cause = unwrapFailure(failure);
                            PlantRepository.StoredPlant cached = plants.get(plantId);
                            if (cached != null && cached.plant().equals(updated)) {
                                plants.put(plantId, oldStored);
                                if (displays != null) {
                                    displays.update(oldStored.plant());
                                }
                            }
                            result.completeExceptionally(cause);
                            return;
                        }

                        PlantRepository.StoredPlant cached = plants.get(plantId);
                        if (cached == null) {
                            result.completeExceptionally(
                                    new IllegalStateException("target_unavailable")
                            );
                            return;
                        }
                        plants.put(
                                plantId,
                                new PlantRepository.StoredPlant(
                                        cached.plant(),
                                        cached.genetics(),
                                        rowVersion
                                )
                        );
                        if (oldStored.plant().growthStage() != updated.growthStage()) {
                            Bukkit.getPluginManager().callEvent(
                                    new VerdantPlantStageChangeEvent(
                                            plantId,
                                            oldStored.plant().growthStage(),
                                            updated.growthStage()
                                    )
                            );
                        }
                        if (displays != null) {
                            displays.update(cached.plant());
                        }
                        writeTracker.markSuccess(plantId);
                        result.complete(updated);
                    } finally {
                        writeTracker.end(plantId);
                    }
                }).exceptionally(mainFailure -> {
                    result.completeExceptionally(unwrapFailure(mainFailure));
                    return null;
                })
        );
        return result;
    }'''
    text = replace_between(
        text,
        "    private void persistUpdate(",
        "    private void water(Player player, PlantRepository.StoredPlant stored, ItemStack tool)",
        persistence_methods,
        "PlantManager persistence methods",
    )

    unwrap_method = '''    private Throwable unwrapFailure(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

'''
    text = replace_once(
        text,
        "    private CompletableFuture<Void> onMain(Runnable action) {\n",
        unwrap_method + "    private CompletableFuture<Void> onMain(Runnable action) {\n",
        "PlantManager unwrap helper",
    )
    write(path, text)


def patch_farm_service() -> None:
    path = "verdantvice-core/src/main/java/com/verdantvice/farm/FarmService.java"
    text = read(path)
    text = replace_once(
        text,
        'throw new IllegalArgumentException("Farm region overlaps an existing farm");',
        'throw new IllegalArgumentException("farm_region_overlap");',
        "FarmService overlap code",
    )
    write(path, text)


def patch_command() -> None:
    path = "verdantvice-paper/src/main/java/com/verdantvice/command/VerdantViceCommand.java"
    text = read(path)
    replacement = '''    private void asyncFailure(CommandSender sender, Throwable failure) {
        CommandFailureMapper.FailureMapping mapping = CommandFailureMapper.map(failure);
        sender.sendMessage(translation().component(
                sender,
                mapping.translationKey(),
                Map.of("limit", "configured")
        ));
        if (mapping.logStackTrace()) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "Asynchronous command operation failed",
                    mapping.cause()
            );
        }
    }'''
    text = replace_between(
        text,
        "    private void asyncFailure(CommandSender sender, Throwable failure) {",
        "    private TranslationService translation()",
        replacement,
        "VerdantViceCommand.asyncFailure",
    )
    write(path, text)


def patch_languages() -> None:
    translations = {
        "pl_PL": [
            '  farm-region-overlap: "<red>Nie można utworzyć plantacji, ponieważ wybrany teren nachodzi na inną plantację.</red>"',
            '  manager-table-blocked: "<red>Miejsce na Stół zarządcy jest zajęte.</red>"',
            '  manager-table-outside-farm: "<red>Stół zarządcy musi znajdować się wewnątrz plantacji.</red>"',
            '  farm-region-too-large: "<red>Wybrany teren plantacji jest zbyt duży.</red>"',
            '  farm-name-required: "<red>Podaj nazwę plantacji.</red>"',
        ],
        "en_US": [
            '  farm-region-overlap: "<red>The farm cannot be created because the selected region overlaps another farm.</red>"',
            '  manager-table-blocked: "<red>The manager table location is occupied.</red>"',
            '  manager-table-outside-farm: "<red>The manager table must be inside the farm.</red>"',
            '  farm-region-too-large: "<red>The selected farm region is too large.</red>"',
            '  farm-name-required: "<red>Enter a farm name.</red>"',
        ],
        "de_DE": [
            '  farm-region-overlap: "<red>Die Farm kann nicht erstellt werden, da sich das ausgewählte Gebiet mit einer anderen Farm überschneidet.</red>"',
            '  manager-table-blocked: "<red>Der Platz für den Verwaltungstisch ist belegt.</red>"',
            '  manager-table-outside-farm: "<red>Der Verwaltungstisch muss innerhalb der Farm stehen.</red>"',
            '  farm-region-too-large: "<red>Das ausgewählte Farmgebiet ist zu groß.</red>"',
            '  farm-name-required: "<red>Gib einen Farmnamen ein.</red>"',
        ],
        "es_ES": [
            '  farm-region-overlap: "<red>No se puede crear la plantación porque la región seleccionada se superpone con otra plantación.</red>"',
            '  manager-table-blocked: "<red>El lugar de la mesa de gestión está ocupado.</red>"',
            '  manager-table-outside-farm: "<red>La mesa de gestión debe estar dentro de la plantación.</red>"',
            '  farm-region-too-large: "<red>La región seleccionada para la plantación es demasiado grande.</red>"',
            '  farm-name-required: "<red>Introduce un nombre para la plantación.</red>"',
        ],
        "cs_CZ": [
            '  farm-region-overlap: "<red>Plantáž nelze vytvořit, protože se vybraná oblast překrývá s jinou plantáží.</red>"',
            '  manager-table-blocked: "<red>Místo pro správcovský stůl je obsazené.</red>"',
            '  manager-table-outside-farm: "<red>Správcovský stůl musí být uvnitř plantáže.</red>"',
            '  farm-region-too-large: "<red>Vybraná oblast plantáže je příliš velká.</red>"',
            '  farm-name-required: "<red>Zadej název plantáže.</red>"',
        ],
        "uk_UA": [
            '  farm-region-overlap: "<red>Плантацію неможливо створити, оскільки вибрана територія перетинається з іншою плантацією.</red>"',
            '  manager-table-blocked: "<red>Місце для столу керівника зайняте.</red>"',
            '  manager-table-outside-farm: "<red>Стіл керівника має бути всередині плантації.</red>"',
            '  farm-region-too-large: "<red>Вибрана територія плантації завелика.</red>"',
            '  farm-name-required: "<red>Введіть назву плантації.</red>"',
        ],
    }
    for locale, lines in translations.items():
        path = f"verdantvice-paper/src/main/resources/languages/{locale}.yml"
        text = read(path)
        if "  farm-region-overlap:" in text:
            continue
        marker = re.search(r"^  farm-permission:.*$", text, flags=re.MULTILINE)
        if marker is None:
            raise RuntimeError(f"{locale}: errors.farm-permission marker not found")
        insertion = marker.group(0) + "\n" + "\n".join(lines)
        text = text[:marker.start()] + insertion + text[marker.end():]
        write(path, text)


def add_support_classes() -> None:
    add_java_file(
        "verdantvice-paper/src/main/java/com/verdantvice/storage/ConcurrentPlantModificationException.java",
        '''
package com.verdantvice.storage;

import com.verdantvice.database.DatabaseException;

/**
 * Logical optimistic-locking conflict. This is deliberately not an SQL
 * exception, so the database retry policy must not replay it as a transient
 * availability failure.
 */
public final class ConcurrentPlantModificationException extends DatabaseException {
    public ConcurrentPlantModificationException(String message) {
        super(message);
    }
}
''',
    )
    add_java_file(
        "verdantvice-paper/src/main/java/com/verdantvice/storage/PerKeyAsyncQueue.java",
        '''
package com.verdantvice.storage;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Serializes asynchronous work per key while allowing different keys to run concurrently. */
public final class PerKeyAsyncQueue<K> {
    private final ConcurrentHashMap<K, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();

    public <T> CompletableFuture<T> submit(
            K key,
            Supplier<? extends CompletionStage<T>> operation
    ) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(operation, "operation");
        CompletableFuture<T> result = new CompletableFuture<>();
        CompletableFuture<Void> tail = tails.compute(key, (ignored, previous) -> {
            CompletableFuture<Void> ready = previous == null
                    ? CompletableFuture.completedFuture(null)
                    : previous.handle((value, failure) -> null);
            return ready.thenCompose(ignoredReady -> {
                CompletionStage<T> stage;
                try {
                    stage = Objects.requireNonNull(operation.get(), "operation result");
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
                    return null;
                }).toCompletableFuture();
            });
        });
        tail.whenComplete((ignored, failure) -> tails.remove(key, tail));
        return result;
    }

    public int activeKeys() {
        return tails.size();
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
''',
    )
    add_java_file(
        "verdantvice-paper/src/main/java/com/verdantvice/plant/PlantWriteTracker.java",
        '''
package com.verdantvice.plant;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class PlantWriteTracker {
    private final ConcurrentHashMap<UUID, Integer> pending = new ConcurrentHashMap<>();
    private final Set<UUID> loggedFailures = ConcurrentHashMap.newKeySet();

    void begin(UUID plantId) {
        pending.merge(plantId, 1, Integer::sum);
    }

    void end(UUID plantId) {
        pending.computeIfPresent(plantId, (ignored, count) -> count <= 1 ? null : count - 1);
    }

    boolean hasPending(UUID plantId) {
        return pending.containsKey(plantId);
    }

    boolean shouldLogFailure(UUID plantId) {
        return loggedFailures.add(plantId);
    }

    void markSuccess(UUID plantId) {
        loggedFailures.remove(plantId);
    }

    void clear() {
        pending.clear();
        loggedFailures.clear();
    }
}
''',
    )
    add_java_file(
        "verdantvice-paper/src/main/java/com/verdantvice/command/CommandFailureMapper.java",
        '''
package com.verdantvice.command;

import com.verdantvice.database.DatabaseUnavailableException;
import com.verdantvice.storage.ConcurrentPlantModificationException;

import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

final class CommandFailureMapper {
    private CommandFailureMapper() {
    }

    static FailureMapping map(Throwable failure) {
        Throwable cause = failure == null
                ? new IllegalStateException("Operation returned no result")
                : unwrap(failure);
        if (cause instanceof DatabaseUnavailableException) {
            return new FailureMapping("errors.database-unavailable", true, cause);
        }
        if (cause instanceof ConcurrentPlantModificationException) {
            return new FailureMapping("errors.database-conflict", false, cause);
        }
        String message = Objects.toString(cause.getMessage(), "");
        String key = switch (message) {
            case "farm_region_overlap", "Farm region overlaps an existing farm" ->
                    "errors.farm-region-overlap";
            case "manager_table_blocked" -> "errors.manager-table-blocked";
            case "manager_table_outside_farm" -> "errors.manager-table-outside-farm";
            case "farm_region_too_large" -> "errors.farm-region-too-large";
            case "farm_name_required" -> "errors.farm-name-required";
            case "invalid_farm_name", "invalid_name" -> "errors.invalid-name";
            case "farm_limit", "limit_reached", "chunk_limit" -> "errors.limit-reached";
            case "region_denied" -> "errors.protected-region";
            case "farm_permission_denied", "farm_permission" -> "errors.farm-permission";
            case "no_permission", "permission_denied", "missing_permission" ->
                    "errors.no-permission";
            case "invalid_or_wrong_item", "invalid_item", "same_parent_strain",
                 "not_a_container" -> "errors.invalid-item";
            case "item_already_used_or_modified", "item_reservation_failed",
                 "item_consumption_failed" -> "errors.duplicate-item";
            case "wrong_owner" -> "errors.wrong-owner";
            case "busy" -> "errors.busy";
            default -> "errors.internal";
        };
        return new FailureMapping(key, key.equals("errors.internal"), cause);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    record FailureMapping(String translationKey, boolean logStackTrace, Throwable cause) {
        FailureMapping {
            Objects.requireNonNull(translationKey, "translationKey");
            Objects.requireNonNull(cause, "cause");
        }
    }
}
''',
    )


def add_tests() -> None:
    add_java_file(
        "verdantvice-paper/src/test/java/com/verdantvice/storage/PerKeyAsyncQueueTest.java",
        '''
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
''',
    )
    add_java_file(
        "verdantvice-paper/src/test/java/com/verdantvice/plant/PlantWriteTrackerTest.java",
        '''
package com.verdantvice.plant;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlantWriteTrackerTest {
    @Test
    void simulationRemainsBlockedUntilEveryWriteCompletes() {
        PlantWriteTracker tracker = new PlantWriteTracker();
        UUID plantId = UUID.randomUUID();
        tracker.begin(plantId);
        tracker.begin(plantId);
        assertTrue(tracker.hasPending(plantId));
        tracker.end(plantId);
        assertTrue(tracker.hasPending(plantId));
        tracker.end(plantId);
        assertFalse(tracker.hasPending(plantId));
    }

    @Test
    void repeatedTickFailureIsLoggedOnceUntilSuccess() {
        PlantWriteTracker tracker = new PlantWriteTracker();
        UUID plantId = UUID.randomUUID();
        assertTrue(tracker.shouldLogFailure(plantId));
        assertFalse(tracker.shouldLogFailure(plantId));
        tracker.markSuccess(plantId);
        assertTrue(tracker.shouldLogFailure(plantId));
    }
}
''',
    )
    add_java_file(
        "verdantvice-paper/src/test/java/com/verdantvice/command/CommandFailureMapperTest.java",
        '''
package com.verdantvice.command;

import com.verdantvice.database.DatabaseUnavailableException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandFailureMapperTest {
    @Test
    void overlappingFarmUsesDomainMessageWithoutStackTrace() {
        var mapping = CommandFailureMapper.map(
                new IllegalArgumentException("farm_region_overlap")
        );
        assertEquals("errors.farm-region-overlap", mapping.translationKey());
        assertFalse(mapping.logStackTrace());
    }

    @Test
    void blockedManagerTableIsNormalPlayerError() {
        var mapping = CommandFailureMapper.map(
                new IllegalStateException("manager_table_blocked")
        );
        assertEquals("errors.manager-table-blocked", mapping.translationKey());
        assertFalse(mapping.logStackTrace());
    }

    @Test
    void trueDatabaseOutageStillUsesDatabaseUnavailable() {
        var mapping = CommandFailureMapper.map(
                new DatabaseUnavailableException("offline")
        );
        assertEquals("errors.database-unavailable", mapping.translationKey());
        assertTrue(mapping.logStackTrace());
    }

    @Test
    void permissionAndInvalidItemHaveSpecificMappings() {
        assertEquals(
                "errors.no-permission",
                CommandFailureMapper.map(new IllegalStateException("permission_denied"))
                        .translationKey()
        );
        assertEquals(
                "errors.invalid-item",
                CommandFailureMapper.map(new IllegalArgumentException("invalid_item"))
                        .translationKey()
        );
    }
}
''',
    )
    add_java_file(
        "verdantvice-paper/src/test/java/com/verdantvice/i18n/DomainTranslationKeysTest.java",
        '''
package com.verdantvice.i18n;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class DomainTranslationKeysTest {
    private static final List<String> LOCALES = List.of(
            "pl_PL", "en_US", "de_DE", "es_ES", "cs_CZ", "uk_UA"
    );
    private static final List<String> KEYS = List.of(
            "errors.farm-region-overlap",
            "errors.manager-table-blocked",
            "errors.manager-table-outside-farm",
            "errors.farm-region-too-large",
            "errors.farm-name-required"
    );

    @Test
    void everyBuiltInLanguageContainsDomainErrorKeys() {
        for (String locale : LOCALES) {
            File file = new File("src/main/resources/languages/" + locale + ".yml");
            YamlConfiguration language = YamlConfiguration.loadConfiguration(file);
            for (String key : KEYS) {
                assertNotNull(language.getString(key), locale + " is missing " + key);
            }
        }
    }
}
''',
    )
    add_java_file(
        "verdantvice-paper/src/test/java/com/verdantvice/storage/PlantRepositoryConcurrencyTest.java",
        '''
package com.verdantvice.storage;

import com.verdantvice.common.BlockPosition;
import com.verdantvice.database.DatabaseConfig;
import com.verdantvice.database.DatabaseManager;
import com.verdantvice.database.DatabaseType;
import com.verdantvice.genetics.GeneticClass;
import com.verdantvice.genetics.GeneticProfile;
import com.verdantvice.plant.GrowthStage;
import com.verdantvice.plant.Plant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlantRepositoryConcurrencyTest {
    @TempDir
    Path temporaryDirectory;

    private DatabaseManager database;
    private PlantRepository repository;

    @BeforeEach
    void startDatabase() {
        database = new DatabaseManager(config(temporaryDirectory), Logger.getAnonymousLogger());
        database.start().toCompletableFuture().join();
        repository = new PlantRepository(database);
    }

    @AfterEach
    void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void nearlySimultaneousSavesAreSerializedAndIncreaseVersion() {
        Fixture fixture = fixture();
        repository.create(fixture.plant(), fixture.genetics()).join();
        Plant first = simulated(fixture.plant(), 10.0, 1);
        Plant second = simulated(first, 20.0, 2);

        CompletableFuture<Void> firstSave = repository.save(first, "TICK_ONE");
        CompletableFuture<Void> secondSave = repository.save(second, "TICK_TWO");
        CompletableFuture.allOf(firstSave, secondSave).join();

        PlantRepository.StoredPlant stored = repository.find(first.uniqueId()).join().orElseThrow();
        assertEquals(2L, stored.rowVersion());
        assertEquals(second, stored.plant());
        for (int attempt = 0; attempt < 100 && repository.activeWriteQueues() != 0; attempt++) {
            Thread.onSpinWait();
        }
        assertEquals(0, repository.activeWriteQueues());
    }

    @Test
    void staleSaveCannotOverwriteNewerStateAndNextSaveStillRuns() {
        Fixture fixture = fixture();
        repository.create(fixture.plant(), fixture.genetics()).join();
        Plant newer = simulated(fixture.plant(), 30.0, 3);
        Plant stale = simulated(fixture.plant(), 5.0, 1);
        repository.save(newer, "NEWER").join();

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> repository.save(stale, "STALE").join()
        );
        assertInstanceOf(ConcurrentPlantModificationException.class, failure.getCause());

        Plant newest = simulated(newer, 40.0, 4);
        repository.save(newest, "RECOVERY").join();
        PlantRepository.StoredPlant stored = repository.find(newest.uniqueId()).join().orElseThrow();
        assertEquals(2L, stored.rowVersion());
        assertEquals(newest, stored.plant());
    }

    @Test
    void plantingAndSeveralSimulationTicksDoNotConflictOrSpamRetries() {
        Fixture fixture = fixture();
        repository.create(fixture.plant(), fixture.genetics()).join();
        Plant current = fixture.plant();
        for (int tick = 1; tick <= 5; tick++) {
            current = simulated(current, tick * 10.0, tick);
            repository.save(current, "GROWTH_UPDATED").join();
        }
        PlantRepository.StoredPlant stored = repository.find(current.uniqueId()).join().orElseThrow();
        assertEquals(5L, stored.rowVersion());
        assertEquals(current, stored.plant());
    }

    @Test
    void logicalOptimisticConflictIsNotRetriedByDatabaseManager() {
        AtomicInteger attempts = new AtomicInteger();
        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> database.transaction(session -> {
                    attempts.incrementAndGet();
                    throw new ConcurrentPlantModificationException("logical conflict");
                }).join()
        );
        assertInstanceOf(ConcurrentPlantModificationException.class, failure.getCause());
        assertEquals(1, attempts.get());
    }

    private Fixture fixture() {
        Instant plantedAt = Instant.parse("2026-01-01T00:00:00Z");
        UUID geneticsId = UUID.randomUUID();
        GeneticProfile genetics = new GeneticProfile(
                geneticsId,
                GeneticClass.COMMON,
                1.0,
                1.0,
                1.0,
                1.0,
                1.0,
                1.0,
                1.0,
                plantedAt
        );
        Plant plant = new Plant(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BlockPosition(UUID.randomUUID(), 10, 64, 10),
                "test_strain",
                geneticsId,
                GrowthStage.SEED,
                0.0,
                100.0,
                75.0,
                75.0,
                10,
                0.0,
                100.0,
                Optional.empty(),
                0.0,
                50.0,
                50.0,
                50.0,
                plantedAt,
                plantedAt,
                plantedAt,
                plantedAt.plus(Duration.ofHours(12)),
                plantedAt.plus(Duration.ofHours(14)),
                Optional.empty(),
                Optional.empty()
        );
        return new Fixture(plant, genetics);
    }

    private Plant simulated(Plant source, double progress, long seconds) {
        return source.withSimulation(
                GrowthStage.VEGETATIVE,
                progress,
                source.health(),
                source.hydration(),
                source.nutrition(),
                source.stress(),
                source.yieldPotential(),
                source.qualityPotential(),
                source.plantedAt().plusSeconds(seconds)
        );
    }

    private DatabaseConfig config(Path root) {
        DatabaseConfig.NetworkSettings network = new DatabaseConfig.NetworkSettings(
                "localhost",
                3306,
                "verdantvice",
                "verdantvice",
                "",
                Map.of()
        );
        return new DatabaseConfig(
                DatabaseType.SQLITE,
                root,
                root.resolve("data/verdantvice-test.db"),
                root.resolve("backups"),
                false,
                Duration.ZERO,
                2,
                Duration.ofSeconds(2),
                network,
                network,
                new DatabaseConfig.PoolSettings(
                        1,
                        2,
                        Duration.ofSeconds(2),
                        Duration.ofMinutes(5),
                        Duration.ofSeconds(1),
                        Duration.ofMinutes(1)
                ),
                new DatabaseConfig.RetrySettings(
                        3,
                        Duration.ZERO,
                        Duration.ofMillis(10)
                ),
                4,
                128,
                Duration.ofSeconds(5)
        );
    }

    private record Fixture(Plant plant, GeneticProfile genetics) {
    }
}
''',
    )


def main() -> None:
    patch_plant_repository()
    patch_plant_manager()
    patch_farm_service()
    patch_command()
    patch_languages()
    add_support_classes()
    add_tests()
    print("Runtime persistence and domain error patch applied.")


if __name__ == "__main__":
    main()
