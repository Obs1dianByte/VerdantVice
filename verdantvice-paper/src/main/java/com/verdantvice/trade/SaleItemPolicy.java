package com.verdantvice.trade;

import com.verdantvice.processing.BatchState;
import com.verdantvice.processing.ProductBatch;
import com.verdantvice.security.CustomItemType;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Defines which signed product item represents which durable batch state and
 * which states may be offered to the herbalist.
 */
public final class SaleItemPolicy {
    private static final Set<CustomItemType> DEFAULT_ACCEPTED = Set.of(
            CustomItemType.DRY_BATCH,
            CustomItemType.CURED_BATCH,
            CustomItemType.PACKAGE
    );

    public Set<CustomItemType> acceptedTypes(List<String> configured) {
        Objects.requireNonNull(configured, "configured");
        if (configured.isEmpty()) return DEFAULT_ACCEPTED;
        EnumSet<CustomItemType> parsed = EnumSet.noneOf(CustomItemType.class);
        for (String value : configured) {
            if (value == null || value.isBlank()) continue;
            try {
                CustomItemType type = CustomItemType.valueOf(
                        value.trim().toUpperCase(Locale.ROOT));
                if (type.batchBound()) parsed.add(type);
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed entries and retain safe defaults when none remain.
            }
        }
        return parsed.isEmpty() ? DEFAULT_ACCEPTED : Set.copyOf(parsed);
    }

    public boolean matchesBatchState(CustomItemType type, BatchState state) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(state, "state");
        return switch (type) {
            case FRESH_BATCH -> state == BatchState.FRESH;
            case DRY_BATCH -> state == BatchState.DRIED;
            case CURED_BATCH -> state == BatchState.CURED;
            case GROUND_PRODUCT -> state == BatchState.GROUND;
            case MIXTURE -> state == BatchState.MIXED;
            case JOINT -> state == BatchState.ROLLED;
            case PACKAGE -> state == BatchState.DRIED || state == BatchState.CURED;
            default -> false;
        };
    }

    public TradeFailureReason rejectionReason(
            CustomItemType type,
            BatchState state,
            Set<CustomItemType> accepted
    ) {
        Objects.requireNonNull(accepted, "accepted");
        if (!matchesBatchState(type, state)) return TradeFailureReason.BATCH_STATE_MISMATCH;
        if (accepted.contains(type)) return null;
        if (type == CustomItemType.FRESH_BATCH && state == BatchState.FRESH) {
            return TradeFailureReason.FRESH_BATCH_REQUIRES_DRYING;
        }
        return TradeFailureReason.UNSUPPORTED_SALE_STATE;
    }

    public double saleWeight(CustomItemType type, ProductBatch batch) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(batch, "batch");
        return type == CustomItemType.FRESH_BATCH
                ? batch.freshWeight()
                : batch.dryWeight();
    }
}
