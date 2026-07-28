package com.verdantvice.trade;

import com.verdantvice.harvest.HarvestTiming;
import com.verdantvice.processing.BatchState;
import com.verdantvice.processing.ProductBatch;
import com.verdantvice.processing.QualityGrade;
import com.verdantvice.security.CustomItemType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaleItemPolicyTest {
    private final SaleItemPolicy policy = new SaleItemPolicy();

    @Test
    void safeDefaultsExcludeFreshBatch() {
        Set<CustomItemType> accepted = policy.acceptedTypes(List.of());
        assertEquals(Set.of(CustomItemType.DRY_BATCH, CustomItemType.CURED_BATCH,
                CustomItemType.PACKAGE), accepted);
        assertFalse(accepted.contains(CustomItemType.FRESH_BATCH));
    }

    @Test
    void administratorCanExplicitlyAcceptFreshBatch() {
        assertTrue(policy.acceptedTypes(List.of("FRESH_BATCH", "DRY_BATCH"))
                .contains(CustomItemType.FRESH_BATCH));
    }

    @Test
    void malformedConfigurationFallsBackToSafeDefaults() {
        assertEquals(Set.of(CustomItemType.DRY_BATCH, CustomItemType.CURED_BATCH,
                        CustomItemType.PACKAGE),
                policy.acceptedTypes(List.of("not-a-real-type", " ")));
    }

    @Test
    void nonBatchConfigurationEntriesAreIgnored() {
        assertEquals(Set.of(CustomItemType.DRY_BATCH, CustomItemType.CURED_BATCH,
                        CustomItemType.PACKAGE),
                policy.acceptedTypes(List.of("SEED", "WATERING_CAN")));
    }

    @Test
    void signedTypeMustMatchDurableBatchState() {
        assertTrue(policy.matchesBatchState(CustomItemType.FRESH_BATCH, BatchState.FRESH));
        assertTrue(policy.matchesBatchState(CustomItemType.DRY_BATCH, BatchState.DRIED));
        assertTrue(policy.matchesBatchState(CustomItemType.CURED_BATCH, BatchState.CURED));
        assertTrue(policy.matchesBatchState(CustomItemType.PACKAGE, BatchState.DRIED));
        assertFalse(policy.matchesBatchState(CustomItemType.FRESH_BATCH, BatchState.DRIED));
        assertFalse(policy.matchesBatchState(CustomItemType.DRY_BATCH, BatchState.FRESH));
    }

    @Test
    void validFreshBatchUsesRequiresDryingWhenNotConfigured() {
        assertEquals(TradeFailureReason.FRESH_BATCH_REQUIRES_DRYING,
                policy.rejectionReason(CustomItemType.FRESH_BATCH, BatchState.FRESH,
                        policy.acceptedTypes(List.of())));
    }

    @Test
    void configuredFreshBatchPassesStatePolicy() {
        assertNull(policy.rejectionReason(
                CustomItemType.FRESH_BATCH,
                BatchState.FRESH,
                policy.acceptedTypes(List.of("FRESH_BATCH"))));
    }

    @Test
    void wrongItemStateIsNotMisclassifiedAsAuthenticityFailure() {
        assertEquals(TradeFailureReason.BATCH_STATE_MISMATCH,
                policy.rejectionReason(CustomItemType.DRY_BATCH, BatchState.FRESH,
                        policy.acceptedTypes(List.of("DRY_BATCH"))));
    }

    @Test
    void unsupportedValidStateGetsDedicatedFailure() {
        assertEquals(TradeFailureReason.UNSUPPORTED_SALE_STATE,
                policy.rejectionReason(CustomItemType.GROUND_PRODUCT, BatchState.GROUND,
                        policy.acceptedTypes(List.of())));
    }

    @Test
    void freshSaleUsesRealFreshWeight() {
        ProductBatch batch = batch(BatchState.FRESH, 18.75, 0.0);
        assertEquals(18.75, policy.saleWeight(CustomItemType.FRESH_BATCH, batch));
    }

    @Test
    void processedSaleUsesRealDryWeight() {
        ProductBatch batch = batch(BatchState.DRIED, 18.75, 5.25);
        assertEquals(5.25, policy.saleWeight(CustomItemType.DRY_BATCH, batch));
    }

    private static ProductBatch batch(BatchState state, double fresh, double dry) {
        return new ProductBatch(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                Map.of("berry_fog", 1.0),
                Set.of(UUID.fromString("33333333-3333-3333-3333-333333333333")),
                fresh,
                dry,
                58.4,
                QualityGrade.fromScore(58.4),
                0.0,
                100.0,
                HarvestTiming.OPTIMAL,
                Optional.empty(),
                Optional.empty(),
                Instant.parse("2026-07-28T12:00:00Z"),
                "signed",
                state
        );
    }
}
