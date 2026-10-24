package com.verdantvice.smoke;

import com.verdantvice.VerdantVicePlugin;
import com.verdantvice.api.VerdantViceApi;
import com.verdantvice.common.BlockPosition;
import com.verdantvice.config.ConfigManager;
import com.verdantvice.genetics.GeneticClass;
import com.verdantvice.genetics.GeneticProfile;
import com.verdantvice.gui.GuiContext;
import com.verdantvice.gui.MenuType;
import com.verdantvice.gui.VerdantGuiHolder;
import com.verdantvice.herbalist.HerbalistTier;
import com.verdantvice.plant.GrowthStage;
import com.verdantvice.plant.Plant;
import com.verdantvice.plant.PlantDisplayService;
import com.verdantvice.plant.PlantViewSource;
import com.verdantvice.profile.SkillTree;
import com.verdantvice.processing.ProcessingMenuHolder;
import com.verdantvice.processing.ProcessingStationType;
import com.verdantvice.processing.RollingMenuHolder;
import com.verdantvice.resourcepack.ResourcePackModels;
import com.verdantvice.security.CustomItemType;
import com.verdantvice.worker.ai.StorageSaveBarrier;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;

/** A real Paper plugin: no Bukkit mocks and no synthetic player. */
public final class PaperSmokePlugin extends JavaPlugin {
    private static final UUID OWNER = UUID.fromString("28f960d9-22ae-43f9-9303-251e60966d91");
    private final List<String> checks = new ArrayList<>();
    private final Properties persisted = new Properties();
    private final String phase = System.getProperty("verdantvice.smoke.phase", "first");
    private VerdantVicePlugin target;
    private int waitingTicks;
    private boolean started;

    @Override
    public void onEnable() {
        target = (VerdantVicePlugin) Bukkit.getPluginManager().getPlugin("VerdantVice");
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (started) return;
            waitingTicks += 10;
            if (target == null || !target.isEnabled() || waitingTicks > 2_400) {
                started = true;
                finish(new IllegalStateException("VerdantVice failed to reach READY"));
            } else if (target.ready()) {
                started = true;
                try {
                    runChecks().whenComplete((ignored, failure) -> onMain(() -> {
                        finish(failure);
                        return CompletableFuture.completedFuture(null);
                    }));
                } catch (Throwable failure) {
                    finish(failure);
                }
            }
        }, 10L, 10L);
    }

    private CompletableFuture<Void> runChecks() throws Exception {
        Files.createDirectories(getDataFolder().toPath());
        if (phase.equals("second")) {
            try (var input = Files.newInputStream(getDataFolder().toPath().resolve("persisted.properties"))) {
                persisted.load(input);
            }
        }
        require(Bukkit.isPrimaryThread(), "tests begin on the real server thread");
        VerdantViceApi api = Bukkit.getServicesManager().load(VerdantViceApi.class);
        require(api != null && api == target.api() && api.apiVersion() == 1,
                "public API is registered and ready");
        require(target.strains().size() >= 12, "bundled strains loaded");
        require(target.translations().languageCount() == 6, "six languages loaded");
        for (String file : ConfigManager.CONFIG_FILES) {
            require(Files.isRegularFile(target.getDataFolder().toPath().resolve(file)),
                    "configuration generated: " + file);
        }
        for (String file : ConfigManager.LANGUAGE_FILES) {
            require(Files.isRegularFile(target.getDataFolder().toPath().resolve("languages").resolve(file)),
                    "language generated: " + file);
        }
        String keyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(target.getDataFolder().toPath().resolve("data/signing.key"))));
        if (phase.equals("second")) {
            require(keyHash.equals(persisted.getProperty("signing-key-sha256")),
                    "signing key survives restart unchanged");
        } else {
            persisted.setProperty("signing-key-sha256", keyHash);
        }
        require(Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "vv admin database"),
                "console database command dispatched");
        require(Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "vv admin performance"),
                "console performance command dispatched");
        require(Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "vv admin reload"),
                "console reload command dispatched");
        checkInventory();
        checkDisplays();

        return checkMigrations().thenCompose(ignored -> api.profile(OWNER))
                .thenCompose(profile -> {
                    if (phase.equals("second")) {
                        require(profile.skill(SkillTree.GROWER).experience() == 137L,
                                "profile skill experience survives restart");
                        require(profile.reputation() == 17.0, "profile reputation survives restart");
                        return CompletableFuture.completedFuture(profile);
                    }
                    require(profile.reputation() == 0.0, "fresh isolated profile has no reputation");
                    return onMain(() -> target.profiles().grantExperience(OWNER, SkillTree.GROWER, 137L))
                            .thenCompose(ignored -> onMain(() -> target.profiles().addReputation(OWNER, 17.0)));
                }).thenCompose(ignored -> onMain(this::checkSignedItems))
                .thenCompose(ignored -> phase.equals("first") ? checkConsumptionRollback()
                        : CompletableFuture.completedFuture(null))
                .thenCompose(ignored -> onMain(this::checkStorageSave))
                .thenCompose(ignored -> onMain(this::checkHerbalist))
                .thenCompose(ignored -> target.database().backupNow())
                .thenAccept(path -> require(Files.isRegularFile(path), "live SQLite backup succeeds"));
    }

    private CompletableFuture<Void> checkMigrations() {
        return target.database().query("SELECT version, checksum, installed_at, success FROM vv_schema_history ORDER BY version",
                row -> row.getInt("version") + ":" + row.getString("checksum") + ":"
                        + row.getLong("installed_at") + ":" + row.getInt("success"))
                .thenAccept(rows -> {
                    require(!rows.isEmpty() && rows.stream().allMatch(row -> row.endsWith(":1")),
                            "all schema migrations succeeded");
                    String history = String.join("|", rows);
                    if (phase.equals("second")) {
                        require(history.equals(persisted.getProperty("schema-history")),
                                "restart does not reapply migrations or change checksums");
                    } else {
                        persisted.setProperty("schema-history", history);
                    }
                });
    }

    private void checkInventory() {
        VerdantGuiHolder holder = new VerdantGuiHolder(OWNER, MenuType.STRAINS, 0,
                GuiContext.empty(), Map.of(0, "back"));
        var inventory = Bukkit.createInventory(holder, 27, Component.text("VerdantVice smoke"));
        holder.attach(inventory);
        var item = target.secureItems().prepare(Bukkit.getConsoleSender(), CustomItemType.SEED, OWNER, "berry_fog");
        inventory.setItem(0, item.stack());
        require(inventory.getHolder() == holder && holder.getInventory() == inventory,
                "real Bukkit inventory preserves production GUI holder");
        require(inventory.getItem(0).hasItemMeta() && inventory.getItem(0).getItemMeta().hasDisplayName(),
                "production item factory renders translated metadata in a real inventory");
        expectIllegalState(() -> holder.attach(inventory), "general GUI rejects a second inventory attachment");

        RollingMenuHolder rolling = new RollingMenuHolder(OWNER, UUID.randomUUID());
        var rollingInventory = Bukkit.createInventory(rolling, 27, Component.text("Rolling smoke"));
        rolling.attach(rollingInventory);
        require(rollingInventory.getHolder() == rolling && rolling.getInventory() == rollingInventory,
                "rolling GUI holder returns its real Bukkit inventory");
        expectIllegalState(() -> rolling.attach(rollingInventory), "rolling GUI rejects a second inventory attachment");
        for (ProcessingStationType station : ProcessingStationType.values()) {
            ProcessingMenuHolder processing = new ProcessingMenuHolder(UUID.randomUUID(), station);
            var processingInventory = Bukkit.createInventory(processing, 27, Component.text("Processing smoke"));
            processing.attach(processingInventory);
            require(processingInventory.getHolder() == processing && processing.getInventory() == processingInventory,
                    "processing GUI holder returns real inventory for " + station);
            expectIllegalState(() -> processing.attach(processingInventory),
                    "processing GUI rejects second attachment for " + station);
        }

        Set<Integer> seedModels = new HashSet<>();
        for (String strainId : ResourcePackModels.strainIds()) {
            ItemStack seed = target.secureItems().prepare(Bukkit.getConsoleSender(), CustomItemType.SEED,
                    OWNER, strainId).stack();
            int model = seed.getItemMeta().getCustomModelData();
            require(model == ResourcePackModels.seed(strainId, ResourcePackModels.item(CustomItemType.SEED))
                            && seedModels.add(model), "unique real seed model metadata for " + strainId);
        }
    }

    private void checkDisplays() {
        World world = Bukkit.getWorlds().getFirst();
        var location = world.getSpawnLocation().clone().add(2, 3, 2);
        location.getChunk().load();
        Instant now = Instant.now();
        var genetics = new GeneticProfile(UUID.randomUUID(), GeneticClass.GOOD,
                1, 1, 1, 1, 1, 1, 1, now);
        Plant plant = Plant.planted(UUID.randomUUID(), OWNER,
                new BlockPosition(world.getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ()),
                target.strains().require("berry_fog"), genetics, now, Optional.empty());
        PlantViewSource source = new PlantViewSource() {
            public Collection<Plant> plantsInChunk(UUID id, int x, int z) { return List.of(plant); }
            public Optional<Plant> plant(UUID id) { return id.equals(plant.uniqueId()) ? Optional.of(plant) : Optional.empty(); }
        };
        PlantDisplayService service = new PlantDisplayService(target, target.configs(), target.translations(),
                target.resourcePack(), source);
        for (GrowthStage stage : GrowthStage.values()) {
            Plant staged = plant.withSimulation(stage, 90, 100, 75, 75, 0, 35, 80, now);
            service.spawn(staged);
            List<Entity> entities = world.getEntities().stream()
                    .filter(entity -> service.plantId(entity).filter(plant.uniqueId()::equals).isPresent()).toList();
            require(entities.stream().filter(ItemDisplay.class::isInstance).count() == 1
                            && entities.stream().filter(Interaction.class::isInstance).count() == 1,
                    "real display + interaction entities rendered for " + stage);
        }
        service.remove(plant.uniqueId());
        require(world.getEntities().stream().noneMatch(entity -> service.plantId(entity)
                .filter(plant.uniqueId()::equals).isPresent()), "display lifecycle removes both entities");
        boolean enabledBefore = target.resourcePack().enabled();
        target.configs().get("resourcepack.yml").set("enabled", true);
        try {
            for (String strainId : ResourcePackModels.strainIds()) {
                Plant strainPlant = Plant.planted(plant.uniqueId(), OWNER, plant.position(),
                        target.strains().require(strainId), genetics, now, Optional.empty());
                for (GrowthStage stage : GrowthStage.values()) {
                    service.spawn(strainPlant.withSimulation(stage, 90, 100, 75, 75, 0, 35, 80, now));
                    var displays = world.getEntities().stream().filter(ItemDisplay.class::isInstance)
                            .filter(entity -> service.plantId(entity).filter(plant.uniqueId()::equals).isPresent())
                            .map(ItemDisplay.class::cast).toList();
                    require(displays.size() == 1 && displays.getFirst().getItemStack().getItemMeta()
                                    .getCustomModelData() == ResourcePackModels.plant(strainId, stage),
                            "real resource-pack model metadata for " + strainId + "/" + stage);
                }
            }
        } finally {
            target.configs().get("resourcepack.yml").set("enabled", enabledBefore);
            service.remove(plant.uniqueId());
        }
        service.shutdown();
    }

    private CompletableFuture<Void> checkSignedItems() {
        if (phase.equals("second")) {
            try {
                ItemStack restored = ItemStack.deserializeBytes(Files.readAllBytes(
                        getDataFolder().toPath().resolve("signed-seed.bin")));
                require(target.itemSigner().verify(restored).valid(), "real ItemStack signature survives restart");
                return target.secureItems().validateActive(restored, Set.of(CustomItemType.SEED), OWNER)
                        .thenAccept(item -> require(item.data().uniqueInstanceId().toString()
                                .equals(persisted.getProperty("seed-instance")), "active registry identity survives restart"))
                        .thenCompose(ignored -> onMain(() -> expectRejected(target.secureItems().consume(restored,
                                Set.of(CustomItemType.SEED), OWNER, "smoke:restored-consumption"), false,
                                "restored active item can be consumed")));
            } catch (Exception failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
        return target.secureItems().issue(Bukkit.getConsoleSender(), CustomItemType.SEED, OWNER, "berry_fog")
                .thenCompose(stack -> onMain(() -> {
                    var verification = target.itemSigner().verify(stack);
                    require(verification.valid(), "production factory issues a signed real ItemStack");
                    target.secureItems().beginDelivery(verification.data().uniqueInstanceId());
                    require(target.secureItems().deliveryPending(stack)
                                    && target.secureItems().deliveryPending(stack.clone()),
                            "pending storage delivery lock follows the exact signed item identity");
                    target.secureItems().endDelivery(verification.data().uniqueInstanceId());
                    require(!target.secureItems().deliveryPending(stack),
                            "durable storage acknowledgement releases the item delivery lock");
                    persisted.setProperty("seed-instance", verification.data().uniqueInstanceId().toString());
                    try {
                        Files.write(getDataFolder().toPath().resolve("signed-seed.bin"), stack.serializeAsBytes());
                    } catch (Exception failure) { throw new IllegalStateException(failure); }
                    ItemStack tampered = stack.clone();
                    var meta = tampered.getItemMeta();
                    meta.getPersistentDataContainer().set(new NamespacedKey(target, "strain"),
                            PersistentDataType.STRING, "smoke_tampered");
                    tampered.setItemMeta(meta);
                    require(!target.itemSigner().verify(tampered).valid(), "tampered PDC is rejected");
                    return expectRejected(target.secureItems().validateActive(stack,
                                    Set.of(CustomItemType.SEED), UUID.randomUUID()), true,
                                    "active validation rejects a different owner")
                            .thenCompose(ignored -> onMain(() -> expectRejected(target.secureItems().consume(
                                    stack, Set.of(CustomItemType.SEED), UUID.randomUUID(),
                                    "smoke:consume:wrong-owner"), true, "consumption rejects a different owner")))
                            .thenCompose(ignored -> onMain(() -> target.secureItems().issue(
                                    Bukkit.getConsoleSender(), CustomItemType.SEED, OWNER, "berry_fog")))
                            .thenCompose(consumable -> onMain(() -> target.secureItems().consume(consumable,
                                            Set.of(CustomItemType.SEED), OWNER, "smoke:consume:first")
                                    .thenCompose(consumed -> onMain(() -> expectRejected(target.secureItems().consume(
                                            consumable, Set.of(CustomItemType.SEED), OWNER,
                                            "smoke:consume:duplicate"), true,
                                            "consumption rejects a duplicate instance")))));
                }));
    }

    private CompletableFuture<Void> checkConsumptionRollback() {
        return target.secureItems().issue(Bukkit.getConsoleSender(), CustomItemType.SEED, OWNER, "berry_fog")
                .thenCompose(item -> onMain(() -> {
                    UUID instance = target.itemSigner().verify(item).data().uniqueInstanceId();
                    String trigger = """
                            CREATE TRIGGER smoke_reject_consumption BEFORE UPDATE ON item_registry
                            WHEN NEW.instance_id = '%s' AND NEW.registry_state = 'CONSUMED'
                            BEGIN SELECT RAISE(ABORT, 'injected consumption failure'); END
                            """.formatted(instance);
                    return target.database().update(trigger)
                            .thenCompose(ignored -> expectRejected(target.secureItems().consume(item,
                                    Set.of(CustomItemType.SEED), OWNER, "smoke:rollback:failure"), true,
                                    "database failure rejects consumption after reservation"))
                            .thenCompose(ignored -> target.secureItems().registryRecord(instance))
                            .thenCompose(record -> {
                                var restored = record.orElseThrow();
                                require(restored.state().name().equals("ACTIVE")
                                                && restored.reservationToken() == null
                                                && restored.reservedBy() == null
                                                && restored.reservedUntil() == null
                                                && restored.consumedAt() == null
                                                && restored.consumedByOperation() == null
                                                && restored.version() == 0,
                                        "failed consumption rolls back registry state and reservation");
                                return target.database().update("DROP TRIGGER smoke_reject_consumption");
                            })
                            .thenCompose(ignored -> target.secureItems().consume(item,
                                    Set.of(CustomItemType.SEED), OWNER, "smoke:rollback:retry"))
                            .thenCompose(consumed -> target.secureItems().registryRecord(instance))
                            .thenAccept(record -> require(record.orElseThrow().state().name().equals("CONSUMED"),
                                    "the same signed item can be consumed after rollback and retry"));
                }));
    }

    private CompletableFuture<Void> checkStorageSave() {
        World world = Bukkit.getWorlds().getFirst();
        if (phase.equals("second")) {
            int x = Integer.parseInt(persisted.getProperty("storage-x"));
            int y = Integer.parseInt(persisted.getProperty("storage-y"));
            int z = Integer.parseInt(persisted.getProperty("storage-z"));
            world.getChunkAt(x >> 4, z >> 4).load();
            require(world.getBlockAt(x, y, z).getState() instanceof Container,
                    "saved real delivery container survives restart");
            Container storage = (Container) world.getBlockAt(x, y, z).getState();
            ItemStack output = storage.getInventory().getItem(0);
            var verification = target.itemSigner().verify(output);
            require(verification.valid() && verification.data().uniqueInstanceId().toString()
                            .equals(persisted.getProperty("storage-instance")),
                    "saved real container retains the exact signed output after restart");
            return target.secureItems().validateActive(output, Set.of(CustomItemType.SEED), OWNER)
                    .thenAccept(ignored -> require(true,
                            "saved delivery output remains active in the durable registry after restart"));
        }
        return target.secureItems().issue(Bukkit.getConsoleSender(), CustomItemType.SEED, OWNER, "berry_fog")
                .thenCompose(output -> onMain(() -> {
                    var location = world.getSpawnLocation().clone().add(4, 3, 4);
                    location.getChunk().load();
                    var block = location.getBlock();
                    block.setType(Material.CHEST, false);
                    require(block.getState() instanceof Container, "real delivery container is created");
                    Container storage = (Container) block.getState();
                    storage.getInventory().clear();
                    storage.getInventory().setItem(0, output);
                    UUID instance = target.itemSigner().verify(output).data().uniqueInstanceId();
                    BlockPosition position = new BlockPosition(world.getUID(), block.getX(), block.getY(), block.getZ());
                    CompletableFuture<Void> saved = new StorageSaveBarrier(target).save(position, () -> {
                        if (!(block.getState() instanceof Container current)) return false;
                        var identity = target.itemSigner().verify(current.getInventory().getItem(0));
                        return identity.valid() && identity.data().uniqueInstanceId().equals(instance);
                    });
                    require(!saved.isDone(), "real world-save barrier defers acknowledgement until its scheduled tick");
                    return saved.thenRun(() -> {
                        require(Bukkit.isPrimaryThread(), "real world-save acknowledgement runs on the server thread");
                        persisted.setProperty("storage-x", Integer.toString(block.getX()));
                        persisted.setProperty("storage-y", Integer.toString(block.getY()));
                        persisted.setProperty("storage-z", Integer.toString(block.getZ()));
                        persisted.setProperty("storage-instance", instance.toString());
                        require(target.itemSigner().verify(((Container) block.getState()).getInventory().getItem(0)).valid(),
                                "real world-save barrier completes with the signed output still in its container");
                    });
                }));
    }

    private CompletableFuture<Void> checkHerbalist() {
        World world = Bukkit.getWorlds().getFirst();
        if (phase.equals("second")) {
            world.getSpawnLocation().getChunk().load();
            UUID id = UUID.fromString(persisted.getProperty("herbalist-uuid"));
            Entity entity = Bukkit.getEntity(id);
            require(entity instanceof Villager, "real herbalist entity survives world restart");
            require(target.herbalists().isHerbalist((Villager) entity), "herbalist PDC survives restart");
            return target.herbalists().herbalistRepository().find(UUID.fromString(
                    persisted.getProperty("herbalist-record-uuid"))).thenAccept(record ->
                    require(record.isPresent(), "herbalist database record survives restart"));
        }
        Villager farmer = world.spawn(world.getSpawnLocation().clone().add(0, 3, 0), Villager.class);
        farmer.setAI(false);
        farmer.setInvulnerable(true);
        farmer.setPersistent(true);
        farmer.setProfession(Villager.Profession.FARMER);
        return target.herbalists().forceCreate(farmer, true, Optional.of(HerbalistTier.VERDANT_EXPERT))
                .thenCompose(evaluation -> onMain(() -> {
                    require(evaluation.herbalist().isPresent(), "real farmer converts to herbalist");
                    require(target.herbalists().isHerbalist(farmer), "converted farmer has production PDC markers");
                    require(farmer.customName() != null, "converted farmer renders translated custom name");
                    persisted.setProperty("herbalist-uuid", farmer.getUniqueId().toString());
                    persisted.setProperty("herbalist-record-uuid", evaluation.herbalist().orElseThrow()
                            .herbalistUuid().toString());
                    return CompletableFuture.completedFuture(null);
                }));
    }

    private CompletableFuture<Void> expectRejected(CompletableFuture<?> operation, boolean rejected, String message) {
        return operation.handle((result, failure) -> {
            require((failure != null) == rejected, message);
            return null;
        });
    }

    private synchronized void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks.add(message);
        getLogger().info("PASS: " + message);
    }

    private void expectIllegalState(Runnable action, String message) {
        try { action.run(); } catch (IllegalStateException expected) { require(true, message); return; }
        throw new AssertionError(message);
    }

    private <T> CompletableFuture<T> onMain(Supplier<CompletableFuture<T>> action) {
        if (Bukkit.isPrimaryThread()) return action.get();
        CompletableFuture<T> result = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(this, () -> {
            try { action.get().whenComplete((value, failure) -> {
                if (failure != null) result.completeExceptionally(failure); else result.complete(value);
            }); } catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        return result;
    }

    private void finish(Throwable failure) {
        try {
            Path directory = getDataFolder().toPath();
            Files.createDirectories(directory);
            if (failure == null) {
                try (var output = Files.newOutputStream(directory.resolve("persisted.properties"))) {
                    persisted.store(output, "Isolated smoke test restart assertions");
                }
                Files.writeString(directory.resolve(phase + ".pass"), String.join("\n", checks), StandardCharsets.UTF_8);
                getLogger().info("SMOKE PASS " + phase + ": " + checks.size() + " assertions");
            } else {
                getLogger().log(Level.SEVERE, "SMOKE FAIL " + phase, failure);
                Files.writeString(directory.resolve(phase + ".fail"), failure.toString(), StandardCharsets.UTF_8);
            }
        } catch (Exception outputFailure) {
            getLogger().log(Level.SEVERE, "Unable to write smoke result", outputFailure);
        } finally {
            Bukkit.shutdown();
        }
    }
}
