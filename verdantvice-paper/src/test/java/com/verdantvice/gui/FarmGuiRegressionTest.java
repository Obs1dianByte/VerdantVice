package com.verdantvice.gui;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarmGuiRegressionTest {
    private static final Path GUI = Path.of(
            "src/main/java/com/verdantvice/gui/GuiManager.java");
    private static final Path PROVIDER = Path.of(
            "src/main/java/com/verdantvice/gui/DefaultGuiDataProvider.java");
    private static final Path HOLDER = Path.of(
            "src/main/java/com/verdantvice/gui/VerdantGuiHolder.java");

    @Test
    void holderPersistsFarmContext() throws Exception {
        String source = read(HOLDER);
        assertTrue(source.contains("private final GuiContext context"));
        assertTrue(source.contains("public GuiContext context()"));
    }

    @Test
    void pageNavigationPreservesContext() throws Exception {
        String source = read(GUI);
        assertTrue(source.contains("open(player, holder.type(), page, holder.context())"));
    }

    @Test
    void farmBackNavigationReturnsToSameFarm() throws Exception {
        String source = read(GUI);
        assertTrue(source.contains("case PLANTS, WORKERS, STORAGES, DRYING, CURING"));
        assertTrue(source.contains("return \"open:farm\";"));
    }

    @Test
    void changingRuntimeDataCanBeRefreshedWithoutTickLoop() throws Exception {
        String source = read(GUI);
        assertTrue(source.contains("actions.put(refresh, \"refresh\")"));
        assertFalse(source.contains("runTaskTimer"));
    }

    @Test
    void emptyMenusUseSpecificMessagesInsteadOfCommonNone() throws Exception {
        String manager = read(GUI);
        String provider = read(PROVIDER);
        assertFalse(manager.contains("translations.component(player, \"common.none\")"));
        for (String key : new String[]{
                "empty.plants", "empty.workers", "empty.storages",
                "empty.drying", "empty.curing"
        }) {
            assertTrue(provider.contains(key));
        }
    }

    @Test
    void genericEntriesNoLongerInjectFakeZeroPlaceholders() throws Exception {
        String source = read(PROVIDER);
        assertFalse(source.contains("Map.of(\"count\", 0, \"morale\", 0)"));
    }

    @Test
    void workingFarmSubmenusAreNotPlaceholderListBranches() throws Exception {
        String source = read(PROVIDER);
        assertTrue(source.contains("case STORAGES -> storageEntries"));
        assertTrue(source.contains("case DRYING -> processingEntries"));
        assertTrue(source.contains("case CURING -> processingEntries"));
        assertTrue(source.contains("case PRIORITIES -> priorityEntries"));
        assertTrue(source.contains("case PERMISSIONS -> permissionEntries"));
        assertTrue(source.contains("case SALARY -> salaryEntries"));
        assertTrue(source.contains("case STATISTICS -> statisticEntries"));
        assertTrue(source.contains("case NOTIFICATIONS -> notificationEntries"));
    }

    @Test
    void noFarmTileUsesNoneAsAnAction() throws Exception {
        assertFalse(read(PROVIDER).contains("\"none\""));
    }

    @Test
    void standalonePlantsMenuKeepsItsExistingGlobalScope() throws Exception {
        String source = read(PROVIDER);
        assertTrue(source.contains("type == MenuType.PLANTS"));
        assertTrue(source.contains("FarmScopedData.plants(farmId, plants.all())"));
    }

    @Test
    void farmPlantsAreFilteredByFarmId() throws Exception {
        String source = read(PROVIDER);
        assertTrue(source.contains("FarmScopedData.plants(farmId, plants.all())"));
    }

    @Test
    void upgradeIsDisabledInsteadOfOpeningEmptyMenu() throws Exception {
        String source = read(PROVIDER);
        assertTrue(source.contains("gui.farm-panel.upgrades.unavailable"));
        assertFalse(source.contains("\"open:upgrades\""));
    }

    private String read(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
