package com.verdantvice.test;

import com.verdantvice.common.BlockPosition;
import com.verdantvice.effects.EffectProfile;
import com.verdantvice.effects.EffectType;
import com.verdantvice.genetics.GeneticClass;
import com.verdantvice.genetics.GeneticProfile;
import com.verdantvice.harvest.HarvestTiming;
import com.verdantvice.plant.Plant;
import com.verdantvice.processing.CuringService;
import com.verdantvice.processing.DryingService;
import com.verdantvice.processing.ProductBatch;
import com.verdantvice.strain.Strain;
import com.verdantvice.strain.StrainRarity;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class TestFixtures {
    public static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");
    public static final UUID OWNER = UUID.fromString("10000000-0000-0000-0000-000000000001");
    public static final UUID WORLD = UUID.fromString("20000000-0000-0000-0000-000000000002");
    public static final UUID GENETICS_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");

    private TestFixtures() {
    }

    public static Strain strain() {
        return new Strain(
                "berry_fog", "Berry Fog", StrainRarity.BASIC, Duration.ofHours(10),
                35.0, 50.0, 80.0, 65.0, 1.0, 10.0, 0.8, 45.0,
                new EffectProfile(Map.of(EffectType.SPEED, 0.8, EffectType.STRESS_RELIEF, 0.5)),
                new EffectProfile(Map.of(EffectType.HUNGER, 0.25)),
                4, 10.0, "berry_fog", "soft_green");
    }

    public static GeneticProfile genetics() {
        return new GeneticProfile(GENETICS_ID, GeneticClass.GOOD, 1.0, 1.0, 1.0, 1.0,
                1.0, 1.0, 1.0, NOW);
    }

    public static Plant plant() {
        return Plant.planted(
                UUID.fromString("40000000-0000-0000-0000-000000000004"),
                OWNER,
                new BlockPosition(WORLD, 10, 64, 10),
                strain(),
                genetics(),
                NOW,
                Optional.empty());
    }

    public static ProductBatch freshBatch() {
        return ProductBatch.fresh(
                UUID.fromString("50000000-0000-0000-0000-000000000005"),
                OWNER,
                "berry_fog",
                GENETICS_ID,
                40.0,
                80.0,
                2.0,
                HarvestTiming.PERFECT,
                NOW);
    }

    public static ProductBatch driedBatch() {
        DryingService service = new DryingService();
        ProductBatch drying = service.start(freshBatch(), NOW, Duration.ofHours(10),
                100.0, 100.0, 1.0);
        return service.update(drying, NOW.plus(Duration.ofHours(10)), true);
    }

    public static ProductBatch curedBatch() {
        ProductBatch dried = driedBatch();
        CuringService service = new CuringService();
        ProductBatch curing = service.start(dried, NOW.plus(Duration.ofHours(10)), Duration.ofHours(10),
                100.0, 100.0);
        return service.update(curing, NOW.plus(Duration.ofHours(20)), true);
    }
}
