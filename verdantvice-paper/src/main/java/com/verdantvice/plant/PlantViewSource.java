package com.verdantvice.plant;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface PlantViewSource {
    Collection<Plant> plantsInChunk(UUID worldUuid, int chunkX, int chunkZ);

    Optional<Plant> plant(UUID plantId);
}
