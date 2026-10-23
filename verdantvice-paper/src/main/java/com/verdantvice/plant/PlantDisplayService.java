package com.verdantvice.plant;

import com.verdantvice.config.ConfigManager;
import com.verdantvice.i18n.TranslationService;
import com.verdantvice.resourcepack.ResourcePackService;
import com.verdantvice.resourcepack.ResourcePackModels;
import net.kyori.adventure.text.Component;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Display entities are a bounded, rebuildable projection of DB-authoritative
 * plants. They are never the source of gameplay state.
 */
public final class PlantDisplayService {
    private final JavaPlugin plugin;
    private final ConfigManager configs;
    private final TranslationService translations;
    private final ResourcePackService resourcePack;
    private final PlantViewSource source;
    private final NamespacedKey viewKey;
    private final NamespacedKey plantKey;
    private final Map<UUID, ViewEntities> views = new ConcurrentHashMap<>();

    public PlantDisplayService(
            JavaPlugin plugin,
            ConfigManager configs,
            TranslationService translations,
            ResourcePackService resourcePack,
            PlantViewSource source
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.translations = Objects.requireNonNull(translations, "translations");
        this.resourcePack = Objects.requireNonNull(resourcePack, "resourcePack");
        this.source = Objects.requireNonNull(source, "source");
        this.viewKey = new NamespacedKey(plugin, "plant_view");
        this.plantKey = new NamespacedKey(plugin, "plant_uuid");
    }

    public void restoreLoadedChunks() {
        requireMainThread();
        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                restoreChunk(chunk);
            }
        }
    }

    public void restoreChunk(Chunk chunk) {
        requireMainThread();
        removeStaleEntities(chunk);
        Collection<Plant> plants = source.plantsInChunk(
                chunk.getWorld().getUID(),
                chunk.getX(),
                chunk.getZ()
        );
        int maximum = Math.max(
                1,
                configs.main().getInt("performance.max-displays-per-chunk", 48)
        );
        int created = 0;
        for (Plant plant : plants) {
            if (created >= maximum) {
                break;
            }
            spawn(plant);
            created++;
        }
    }

    public void spawn(Plant plant) {
        requireMainThread();
        remove(plant.uniqueId());
        World world = plugin.getServer().getWorld(plant.position().worldUuid());
        if (world == null || !world.isChunkLoaded(
                Math.floorDiv(plant.position().x(), 16),
                Math.floorDiv(plant.position().z(), 16)
        )) {
            return;
        }
        Location center = new Location(
                world,
                plant.position().x() + 0.5,
                PlantDisplayGeometry.anchorY(plant.position().y()),
                plant.position().z() + 0.5
        );

        ItemDisplay display = world.spawn(center, ItemDisplay.class, entity -> {
            mark(entity, plant.uniqueId());
            entity.setPersistent(false);
            entity.setInvulnerable(true);
            entity.setGravity(false);
            entity.setBillboard(Display.Billboard.FIXED);
            entity.setViewRange(0.75f);
            entity.setShadowRadius(0.0f);
            entity.setShadowStrength(0.0f);
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            entity.setTransformation(new Transformation(
                    new Vector3f(0.0f, PlantDisplayGeometry.modelLift(plant.growthStage()), 0.0f),
                    new AxisAngle4f(),
                    scale(plant.growthStage()),
                    new AxisAngle4f()
            ));
            entity.setItemStack(displayItem(plant.growthStage(), plant.strainId()));
        });

        Interaction hitbox = world.spawn(center, Interaction.class, entity -> {
            mark(entity, plant.uniqueId());
            entity.setPersistent(false);
            entity.setInteractionWidth(width(plant.growthStage()));
            entity.setInteractionHeight(height(plant.growthStage()));
            entity.setResponsive(true);
        });
        views.put(plant.uniqueId(), new ViewEntities(display.getUniqueId(), hitbox.getUniqueId()));
    }

    public void update(Plant plant) {
        spawn(plant);
    }

    public void remove(UUID plantId) {
        requireMainThread();
        ViewEntities entities = views.remove(plantId);
        if (entities == null) {
            return;
        }
        removeEntity(entities.displayUuid());
        removeEntity(entities.interactionUuid());
    }

    public void unloadChunk(Chunk chunk) {
        requireMainThread();
        List<UUID> plantIds = new ArrayList<>();
        for (Entity entity : chunk.getEntities()) {
            String id = entity.getPersistentDataContainer().get(
                    plantKey,
                    PersistentDataType.STRING
            );
            if (id != null && isView(entity)) {
                try {
                    plantIds.add(UUID.fromString(id));
                } catch (IllegalArgumentException ignored) {
                    // Malformed entities are removed below.
                }
                entity.remove();
            }
        }
        plantIds.forEach(views::remove);
    }

    public void showInspection(Player player, Plant plant, Component text) {
        requireMainThread();
        World world = player.getWorld();
        Location location = new Location(
                world,
                plant.position().x() + 0.5,
                plant.position().y() + 1.65,
                plant.position().z() + 0.5
        );
        TextDisplay display = world.spawn(location, TextDisplay.class, entity -> {
            mark(entity, plant.uniqueId());
            entity.setPersistent(false);
            entity.setInvulnerable(true);
            entity.setGravity(false);
            entity.setBillboard(Display.Billboard.CENTER);
            entity.text(text);
            entity.setSeeThrough(true);
            entity.setDefaultBackground(false);
            entity.setViewRange(0.5f);
        });
        plugin.getServer().getScheduler().runTaskLater(plugin, display::remove, 100L);
    }

    public boolean isView(Entity entity) {
        return entity.getPersistentDataContainer().has(viewKey, PersistentDataType.BYTE);
    }

    public Optional<UUID> plantId(Entity entity) {
        if (!isView(entity)) {
            return Optional.empty();
        }
        String value = entity.getPersistentDataContainer().get(
                plantKey,
                PersistentDataType.STRING
        );
        try {
            return value == null ? Optional.empty() : Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    public void shutdown() {
        requireMainThread();
        for (ViewEntities value : List.copyOf(views.values())) {
            removeEntity(value.displayUuid());
            removeEntity(value.interactionUuid());
        }
        views.clear();
    }

    private void removeStaleEntities(Chunk chunk) {
        for (Entity entity : chunk.getEntities()) {
            if (isView(entity)) {
                entity.remove();
            }
        }
    }

    private ItemStack displayItem(GrowthStage stage, String strainId) {
        if (resourcePack.enabled()) {
            ItemStack item = new ItemStack(Material.AMETHYST_SHARD);
            ItemMeta meta = item.getItemMeta();
            String visualModel = configs.get("strains.yml").getString(
                    "strains." + strainId + ".visual-model", strainId);
            meta.setCustomModelData(ResourcePackModels.plant(visualModel, stage));
            item.setItemMeta(meta);
            return item;
        }
        String configured = configs.get("plants.yml").getString(
                "display.material-by-stage." + stage.name(),
                "AZALEA"
        );
        Material material = Material.matchMaterial(configured);
        if (material == null || !material.isItem()) {
            material = Material.AZALEA;
        }
        return new ItemStack(material);
    }

    private void mark(Entity entity, UUID plantId) {
        entity.getPersistentDataContainer().set(viewKey, PersistentDataType.BYTE, (byte) 1);
        entity.getPersistentDataContainer().set(
                plantKey,
                PersistentDataType.STRING,
                plantId.toString()
        );
    }

    private void removeEntity(UUID entityId) {
        Entity entity = plugin.getServer().getEntity(entityId);
        if (entity != null) {
            entity.remove();
        }
    }

    private Vector3f scale(GrowthStage stage) {
        float value = PlantDisplayGeometry.modelScale(stage);
        return new Vector3f(value, value, value);
    }

    private float width(GrowthStage stage) {
        return switch (stage) {
            case SEED, SPROUT -> 0.6f;
            case SEEDLING, VEGETATIVE -> 0.9f;
            default -> 1.2f;
        };
    }

    private float height(GrowthStage stage) {
        return switch (stage) {
            case SEED -> 0.35f;
            case SPROUT -> 0.6f;
            case SEEDLING -> 0.9f;
            case VEGETATIVE -> 1.2f;
            default -> 1.5f;
        };
    }

    private void requireMainThread() {
        if (!plugin.getServer().isPrimaryThread()) {
            throw new IllegalStateException("Display operations must run on the server thread");
        }
    }

    private record ViewEntities(UUID displayUuid, UUID interactionUuid) {
    }
}
