package com.verdantvice.gui;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlantFarmRuntimeRegressionTest {
    private static final Path PLANTS = Path.of(
            "src/main/java/com/verdantvice/plant/PlantManager.java");
    private static final Path PLUGIN = Path.of(
            "src/main/java/com/verdantvice/VerdantVicePlugin.java");
    private static final Path GUI = Path.of(
            "src/main/java/com/verdantvice/gui/DefaultGuiDataProvider.java");

    @Test
    void playerPlantingResolvesFarmFromExactStationPosition() throws Exception {
        String source = read(PLANTS);
        assertTrue(source.contains("farms.at(station.getLocation())"));
        assertTrue(source.contains("now,\n                    farmId).withEnvironment"));
    }

    @Test
    void workerPlantingAlwaysUsesItsSelectedFarmId() throws Exception {
        String source = read(PLANTS);
        assertTrue(source.contains("now,\n                    Optional.of(farmId)).withEnvironment"));
    }

    @Test
    void farmCacheLoadsBeforePlantFarmIdMigration() throws Exception {
        String source = read(PLUGIN);
        assertTrue(source.contains(
                "farmsLoaded.thenCompose(ignored -> plants.load())"));
    }

    @Test
    void startupMigrationPersistsWithOptimisticVersioning() throws Exception {
        String source = read(PLANTS);
        assertTrue(source.contains(
                "repository.saveVersioned(repaired, \"FARM_ID_MIGRATED\")"));
    }

    @Test
    void farmDeleteTileUsesSelectedFarmContextInsteadOfOwnedFarmLookup() throws Exception {
        String source = read(GUI);
        assertTrue(source.contains("\"delete-farm:\" + farm.farmId()"));
        assertFalse(source.contains("\"command:vv farm delete\""));
    }

    private String read(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }
}
