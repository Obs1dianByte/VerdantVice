package com.verdantvice.plant;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlantDisplayGeometryTest {
    @ParameterizedTest
    @EnumSource(GrowthStage.class)
    void displayBottomAndInteractionBaseClearSolidPotForEveryStage(GrowthStage stage) {
        for (double stationY : new double[] {-60, 0, 64, 255}) {
            double anchor = PlantDisplayGeometry.anchorY(stationY);
            double modelBottom = anchor + PlantDisplayGeometry.modelLift(stage)
                    - PlantDisplayGeometry.modelScale(stage) / 2.0;
            assertTrue(anchor > stationY + 1.0, "Interaction base must not be inside the solid pot");
            assertTrue(modelBottom > stationY + 1.0, "Visible sprite must not be buried inside the pot");
            assertEquals(stationY + 1.02, modelBottom, 0.000001,
                    "Growing models stay rooted above the same pot surface");
        }
    }
}
