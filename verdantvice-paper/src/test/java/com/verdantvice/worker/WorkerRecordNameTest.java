package com.verdantvice.worker;

import com.verdantvice.common.BlockPosition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class WorkerRecordNameTest {
    @Test
    void persistenceEnvelopeNeverExposesLegacyFormattingToGuiOrNotifications() {
        UUID world = UUID.randomUUID();
        Worker worker = new Worker(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                WorkerSpecialization.GROWER, 1, 0L, 75.0, 4, 0, 0L,
                WorkerState.IDLE, Optional.empty());
        WorkerRecord record = new WorkerRecord(
                worker, UUID.randomUUID(), "§2Herbalist",
                new WorkerSchedule(6000, 7000, 12000, 13000, 18000, 22000),
                Optional.of(new BlockPosition(world, 0, 64, 0)),
                Optional.empty(), Instant.EPOCH, Instant.EPOCH, 0L);
        assertEquals("Herbalist", record.name());
    }
}
