package com.verdantvice.resourcepack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.verdantvice.plant.GrowthStage;
import com.verdantvice.security.CustomItemType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Checks production assets directly. Runs with normal Gradle test/check; no live client needed. */
final class ResourcePackAssetsTest {
    private static Path project;
    private static Path packs;
    private static JsonObject catalog;
    private static final Map<String, Map<Integer, String>> EXPECTED = new LinkedHashMap<>();
    private static final Set<String> PLANTS = new HashSet<>();

    @BeforeAll
    static void load() throws Exception {
        project = Path.of("").toAbsolutePath();
        while (project != null && !Files.isDirectory(project.resolve("resource-pack/source"))) {
            project = project.getParent();
        }
        assertNotNull(project, "Tests must run inside the repository");
        packs = project.resolve("resource-pack");
        catalog = json(packs.resolve("source/catalog.json"));
        catalog.getAsJsonArray("items").forEach(value -> {
            var item = value.getAsJsonObject();
            expect(item.get("material").getAsString(), item.get("custom_model_data").getAsInt(), item.get("model").getAsString());
        });
        catalog.getAsJsonArray("generic_stages").forEach(value -> {
            var stage = value.getAsJsonObject();
            expect("AMETHYST_SHARD", stage.get("custom_model_data").getAsInt(), stage.get("model").getAsString());
            PLANTS.add(stage.get("model").getAsString());
        });
        catalog.getAsJsonArray("strains").forEach(value -> {
            var strain = value.getAsJsonObject();
            expect("WHEAT_SEEDS", strain.get("seed_model_data").getAsInt(), strain.get("seed_model").getAsString());
            strain.getAsJsonArray("stages").forEach(stageValue -> {
                var stage = stageValue.getAsJsonObject();
                expect("AMETHYST_SHARD", stage.get("custom_model_data").getAsInt(), stage.get("model").getAsString());
                PLANTS.add(stage.get("model").getAsString());
            });
        });
    }

    @Test
    void everyRuntimeItemHasItsOwnConfiguredArtworkAndStableModelNumber() throws Exception {
        var config = YamlConfiguration.loadConfiguration(project.resolve("verdantvice-paper/src/main/resources/items.yml").toFile());
        Set<CustomItemType> covered = new HashSet<>();
        Set<String> graphics = new HashSet<>();
        for (var value : catalog.getAsJsonArray("items")) {
            var item = value.getAsJsonObject();
            var type = CustomItemType.valueOf(item.get("type").getAsString());
            String id = item.get("id").getAsString();
            assertTrue(covered.add(type), "Duplicate type: " + type);
            assertEquals(item.get("material").getAsString(), config.getString(id + ".material"), id);
            assertEquals(item.get("custom_model_data").getAsInt(), config.getInt(id + ".custom-model-data"), id);
            assertEquals(ResourcePackModels.item(type), config.getInt(id + ".custom-model-data"), id);
            assertTrue(graphics.add(digest(texture("1.21.4", item.get("model").getAsString()))),
                    "An item must not reuse another item's artwork: " + id);
        }
        assertEquals(Set.copyOf(Arrays.asList(CustomItemType.values())), covered);
        assertEquals(25, graphics.size());
    }

    @Test
    void everyStrainAndGrowthStageMatchesTheActualRuntimeModelSelector() throws Exception {
        var config = YamlConfiguration.loadConfiguration(project.resolve("verdantvice-paper/src/main/resources/strains.yml").toFile());
        List<String> ids = new ArrayList<>();
        Set<String> seedGraphics = new HashSet<>();
        for (var value : catalog.getAsJsonArray("strains")) {
            var strain = value.getAsJsonObject();
            String id = strain.get("id").getAsString();
            ids.add(id);
            assertEquals(strain.get("display_name").getAsString(), config.getString("strains." + id + ".display-name"));
            assertEquals(strain.get("index").getAsInt(), ResourcePackModels.strainIds().indexOf(id));
            assertEquals(strain.get("seed_model_data").getAsInt(), ResourcePackModels.seed(id, 12001));
            assertTrue(seedGraphics.add(digest(texture("1.21.4", strain.get("seed_model").getAsString()))), id);
            assertEquals(8, strain.getAsJsonArray("stages").size());
            for (var stageValue : strain.getAsJsonArray("stages")) {
                var stage = stageValue.getAsJsonObject();
                assertEquals(stage.get("custom_model_data").getAsInt(),
                        ResourcePackModels.plant(id, GrowthStage.valueOf(stage.get("stage").getAsString())));
            }
        }
        assertEquals(ResourcePackModels.strainIds(), ids);
        Set<String> phases = new HashSet<>();
        for (var value : catalog.getAsJsonArray("generic_stages")) {
            var stage = value.getAsJsonObject();
            assertEquals(stage.get("custom_model_data").getAsInt(),
                    ResourcePackModels.plant("custom_unknown", GrowthStage.valueOf(stage.get("stage").getAsString())));
            assertTrue(phases.add(digest(texture("1.21.4", stage.get("model").getAsString()))),
                    "Growth phases must not reuse the same image");
        }
        assertEquals(8, phases.size());
    }

    @TestFactory
    Stream<DynamicTest> allTexturesAreCrispTransparentAndEveryModelReferenceExists() {
        var names = EXPECTED.values().stream().flatMap(map -> map.values().stream()).distinct().sorted().toList();
        assertEquals(141, names.size());
        return Stream.of("1.21.4", "26.2").flatMap(version -> names.stream().map(name ->
                DynamicTest.dynamicTest(version + "/" + name, () -> {
                    var image = ImageIO.read(texture(version, name).toFile());
                    assertNotNull(image, name);
                    assertEquals(32, image.getWidth(), name); assertEquals(32, image.getHeight(), name);
                    assertTrue(image.getColorModel().hasAlpha(), name);
                    int visible = 0;
                    Set<Integer> colors = new HashSet<>();
                    for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) {
                        int pixel = image.getRGB(x, y), alpha = pixel >>> 24;
                        assertTrue(alpha == 0 || alpha == 255, "Antialiasing/halo at " + name + ":" + x + "," + y);
                        if (alpha != 0) { visible++; colors.add(pixel); }
                        if (x == 0 || y == 0 || x == 31 || y == 31) assertEquals(0, alpha, "Clipped silhouette: " + name);
                    }
                    assertTrue(visible >= 20 && visible < 900, "Empty or opaque rectangle: " + name);
                    assertTrue(colors.size() >= 3 && colors.size() <= 24, "Uncontrolled palette: " + name);
                    var model = json(packs.resolve(version + "/assets/verdantvice/models/item/" + name + ".json"));
                    for (var entry : model.getAsJsonObject("textures").entrySet()) {
                        String reference = entry.getValue().getAsString();
                        if (reference.startsWith("verdantvice:")) assertTrue(Files.isRegularFile(
                                packs.resolve(version + "/assets/verdantvice/textures/" + reference.substring(12) + ".png")), reference);
                    }
                    if (PLANTS.contains(name)) {
                        assertFalse(model.has("parent"), "World plants must not be flat generated item icons");
                        assertEquals(2, model.getAsJsonArray("elements").size());
                        Set<Integer> angles = new HashSet<>();
                        for (var element : model.getAsJsonArray("elements")) {
                            var plane = element.getAsJsonObject();
                            angles.add(plane.getAsJsonObject("rotation").get("angle").getAsInt());
                            assertEquals("y", plane.getAsJsonObject("rotation").get("axis").getAsString());
                            assertEquals(Set.of("north", "south"), plane.getAsJsonObject("faces").keySet());
                            assertEquals(plane.getAsJsonArray("from").get(2), plane.getAsJsonArray("to").get(2));
                        }
                        assertEquals(Set.of(-45, 45), angles);
                        assertTrue(model.getAsJsonObject("display").has("fixed"));
                    } else assertEquals("minecraft:item/generated", model.get("parent").getAsString());
                })));
    }

    @Test
    void customModelDispatchResolvesEveryOwnedNumberAndPreservesVanillaForUnownedNumbers() throws Exception {
        for (String version : List.of("1.21.4", "26.2")) for (var entry : EXPECTED.entrySet()) {
            String carrier = entry.getKey().toLowerCase(java.util.Locale.ROOT);
            var model = json(packs.resolve(version + "/assets/minecraft/items/" + carrier + ".json")).getAsJsonObject("model");
            assertEquals("minecraft:range_dispatch", model.get("type").getAsString());
            assertEquals("minecraft:custom_model_data", model.get("property").getAsString());
            assertEquals(0, model.get("index").getAsInt());
            int previous = -1;
            for (var threshold : model.getAsJsonArray("entries")) {
                int number = threshold.getAsJsonObject().get("threshold").getAsInt();
                assertTrue(number > previous, "Dispatch must be strictly ordered"); previous = number;
            }
            // Exhaustive integer lookup covers every gap/reset between all owned ranges.
            JsonObject vanilla = vanillaDefinition(version, carrier).getAsJsonObject("model");
            for (int number = 0; number < 13300; number++) {
                JsonObject expected = vanilla;
                if (entry.getValue().containsKey(number)) {
                    expected = new JsonObject();
                    expected.addProperty("type", "minecraft:model");
                    expected.addProperty("model", "verdantvice:item/" + entry.getValue().get(number));
                }
                assertEquals(expected, resolve(model, number), version + "/" + carrier + "/" + number);
            }
            assertEquals(vanilla, resolve(model, 999999));
        }
    }

    @Test
    void vanillaFallbacksPreserveNativeBehaviorAndReferenceVerifiedClientModels() throws Exception {
        for (String version : List.of("1.21.4", "26.2")) {
            var references = json(packs.resolve("source/vanilla/" + version + "/references.json"));
            assertEquals(version, references.get("minecraft_version").getAsString());
            var checkedModels = references.getAsJsonObject("model_files_sha256");
            Set<String> carriers = EXPECTED.keySet().stream().map(value -> value.toLowerCase(java.util.Locale.ROOT))
                    .collect(java.util.stream.Collectors.toSet());
            assertEquals(carriers, references.getAsJsonObject("item_definitions_sha256").keySet());
            for (String carrier : carriers) {
                Path source = packs.resolve("source/vanilla/" + version + "/items/" + carrier + ".json");
                assertEquals(references.getAsJsonObject("item_definitions_sha256").get(carrier).getAsString(), digest(source));
                JsonObject nativeDefinition = json(source);
                JsonObject bundled = json(packs.resolve(version + "/assets/minecraft/items/" + carrier + ".json"));
                assertEquals(nativeDefinition.get("model"), bundled.getAsJsonObject("model").get("fallback"), carrier);
                JsonObject expectedTopLevel = nativeDefinition.deepCopy();
                expectedTopLevel.add("model", bundled.get("model"));
                assertEquals(expectedTopLevel, bundled, "Preserve native top-level item behavior: " + carrier);
                assertNativeModelReferencesExist(nativeDefinition, checkedModels);
            }
            assertEquals("minecraft:block/stonecutter", vanillaDefinition(version, "stonecutter")
                    .getAsJsonObject("model").get("model").getAsString());
            assertEquals("minecraft:block/chiseled_bookshelf_inventory", vanillaDefinition(version, "chiseled_bookshelf")
                    .getAsJsonObject("model").get("model").getAsString());
            assertEquals("minecraft:block/lectern", vanillaDefinition(version, "lectern")
                    .getAsJsonObject("model").get("model").getAsString());
            assertEquals("minecraft:block/barrel", vanillaDefinition(version, "barrel")
                    .getAsJsonObject("model").get("model").getAsString());
            assertEquals("minecraft:select", vanillaDefinition(version, "clock").getAsJsonObject("model").get("type").getAsString());
            assertEquals("minecraft:select", vanillaDefinition(version, "bundle").getAsJsonObject("model").get("type").getAsString());
            assertEquals("minecraft:range_dispatch", vanillaDefinition(version, "brush").getAsJsonObject("model").get("type").getAsString());
            assertEquals("minecraft:special", vanillaDefinition(version, "decorated_pot").getAsJsonObject("model").get("type").getAsString());
        }
    }

    @Test
    void soundAliasesReferenceVerifiedVanillaEventsRatherThanInventedOggPaths() throws Exception {
        Map<String, String> expected = Map.of(
                "plant_ready", "block.azalea_leaves.place",
                "tool", "item.brush.brushing.generic",
                "process_complete", "block.amethyst_block.chime");
        for (String version : List.of("1.21.4", "26.2")) {
            var references = json(packs.resolve("source/vanilla/" + version + "/references.json"));
            var nativeEvents = references.getAsJsonObject("sound_events");
            var files = references.getAsJsonObject("sound_files_sha1");
            assertEquals(Set.copyOf(expected.values()), nativeEvents.keySet());
            var aliases = json(packs.resolve(version + "/assets/verdantvice/sounds.json"));
            assertEquals(expected.keySet(), aliases.keySet());
            for (var alias : expected.entrySet()) {
                var sounds = aliases.getAsJsonObject(alias.getKey()).getAsJsonArray("sounds");
                assertEquals(1, sounds.size());
                var event = sounds.get(0).getAsJsonObject();
                assertEquals("event", event.get("type").getAsString());
                assertEquals("minecraft:" + alias.getValue(), event.get("name").getAsString());
                for (var nativeSound : nativeEvents.getAsJsonObject(alias.getValue()).getAsJsonArray("sounds")) {
                    String name = nativeSound.isJsonPrimitive() ? nativeSound.getAsString()
                            : nativeSound.getAsJsonObject().get("name").getAsString();
                    assertTrue(files.has("minecraft/sounds/" + name.replace("minecraft:", "") + ".ogg"), name);
                }
            }
        }
    }

    @Test
    void packVariantsShareCustomAssetsButRetainVersionSpecificVanillaDefinitions() throws Exception {
        Path older = packs.resolve("1.21.4"), newer = packs.resolve("26.2");
        assertEquals(relativeFiles(older), relativeFiles(newer), "Pack variants must have the same file set");
        try (var files = Files.walk(older)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path relative = older.relativize(file);
                String name = relative.toString().replace('\\', '/');
                if (!name.equals("pack.mcmeta") && !name.startsWith("assets/minecraft/items/")) assertArrayEquals(
                        Files.readAllBytes(file), Files.readAllBytes(newer.resolve(relative)), relative.toString());
            }
        }
        assertEquals(46, json(older.resolve("pack.mcmeta")).getAsJsonObject("pack").get("pack_format").getAsInt());
        var modern = json(newer.resolve("pack.mcmeta")).getAsJsonObject("pack");
        assertEquals(88, modern.getAsJsonArray("min_format").get(0).getAsInt());
        assertEquals(modern.get("min_format"), modern.get("max_format"));
        for (String version : List.of("1.21.4", "26.2")) {
            var names = EXPECTED.values().stream().flatMap(map -> map.values().stream())
                    .collect(java.util.stream.Collectors.toSet());
            assertEquals(names.stream().map(name -> name + ".png").collect(java.util.stream.Collectors.toSet()),
                    relativeFiles(packs.resolve(version + "/assets/verdantvice/textures/item")));
            assertEquals(names.stream().map(name -> name + ".json").collect(java.util.stream.Collectors.toSet()),
                    relativeFiles(packs.resolve(version + "/assets/verdantvice/models/item")));
        }
        assertNotNull(ImageIO.read(packs.resolve("source/all-items-preview.png").toFile()));
        assertNotNull(ImageIO.read(packs.resolve("source/all-growth-stages-preview.png").toFile()));
    }

    private static void expect(String carrier, int number, String name) {
        assertNull(EXPECTED.computeIfAbsent(carrier, ignored -> new LinkedHashMap<>()).put(number, name),
                "Duplicate carrier/model number: " + carrier + "/" + number);
    }

    private static Set<String> relativeFiles(Path directory) throws Exception {
        try (var files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile).map(directory::relativize)
                    .map(path -> path.toString().replace('\\', '/'))
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

    private static JsonObject resolve(JsonObject model, int number) {
        JsonObject chosen = model.getAsJsonObject("fallback");
        for (var value : model.getAsJsonArray("entries")) {
            var entry = value.getAsJsonObject();
            if (entry.get("threshold").getAsInt() > number) break;
            chosen = entry.getAsJsonObject("model");
        }
        return chosen;
    }

    private static JsonObject vanillaDefinition(String version, String carrier) throws Exception {
        return json(packs.resolve("source/vanilla/" + version + "/items/" + carrier + ".json"));
    }

    private static void assertNativeModelReferencesExist(JsonElement value, JsonObject checkedModels) {
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (object.has("type")) {
                String type = object.get("type").getAsString();
                String model = switch (type) {
                    case "minecraft:model" -> object.get("model").getAsString();
                    case "minecraft:special" -> object.get("base").getAsString();
                    default -> null;
                };
                if (model != null) {
                    assertTrue(checkedModels.has(model), "Model must exist in the matching official client: " + model);
                    assertTrue(checkedModels.get(model).getAsString().matches("[0-9a-f]{64}"));
                }
            }
            object.entrySet().forEach(entry -> assertNativeModelReferencesExist(entry.getValue(), checkedModels));
        } else if (value.isJsonArray()) {
            value.getAsJsonArray().forEach(child -> assertNativeModelReferencesExist(child, checkedModels));
        }
    }

    private static JsonObject json(Path path) throws Exception {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }
    private static Path texture(String version, String name) {
        return packs.resolve(version + "/assets/verdantvice/textures/item/" + name + ".png");
    }
    private static String digest(Path path) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
