package com.verdantvice.item;

import com.verdantvice.processing.ProductBatch;
import com.verdantvice.security.CustomItemType;
import com.verdantvice.strain.Strain;
import com.verdantvice.strain.StrainRegistry;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable display-only data for localized item names and lore.
 *
 * <p>This context is deliberately not part of the HMAC payload. ProductBatch
 * and SignedItemData remain the sources of truth; localization can therefore
 * be changed without invalidating previously issued items.</p>
 */
public record ItemDisplayContext(
        UUID batchId,
        String strainId,
        String strainName,
        double freshWeight,
        double dryWeight,
        double quality,
        String qualityGrade,
        String state,
        int components,
        Duration duration
) {
    public ItemDisplayContext {
        strainId = strainId == null ? "" : strainId;
        strainName = strainName == null || strainName.isBlank() ? strainId : strainName;
        qualityGrade = qualityGrade == null ? "" : qualityGrade;
        state = state == null ? "" : state;
        components = Math.max(0, components);
        duration = duration == null ? Duration.ZERO : duration;
    }

    public static ItemDisplayContext simple(String strainId, double quality, StrainRegistry strains) {
        String id = strainId == null ? "" : strainId;
        Strain definition = id.isBlank() ? null : strains.find(id).orElse(null);
        if (definition != null) id = definition.id();
        String name = definition == null ? id : definition.displayName();
        return new ItemDisplayContext(
                null, id, name, 0.0, 0.0, quality, "", "", id.isBlank() ? 0 : 1,
                Duration.ZERO);
    }

    public static ItemDisplayContext batch(ProductBatch batch, StrainRegistry strains) {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(strains, "strains");
        String dominant = batch.strainComposition().entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElseThrow()
                .getKey();
        String names = batch.strainComposition().keySet().stream()
                .sorted()
                .map(id -> strains.find(id).map(Strain::displayName).orElse(id))
                .reduce((left, right) -> left + ", " + right)
                .orElse(dominant);
        Duration duration = batch.curingData().map(data -> data.targetDuration())
                .or(() -> batch.dryingData().map(data -> data.targetDuration()))
                .orElse(Duration.ZERO);
        return new ItemDisplayContext(
                batch.batchId(), dominant, names, batch.freshWeight(), batch.dryWeight(),
                batch.qualityScore(), batch.qualityGrade().name(), batch.state().name(),
                batch.strainComposition().size(), duration);
    }

    public Map<String, ?> placeholders(CustomItemType type) {
        Objects.requireNonNull(type, "type");
        Map<String, Object> values = new LinkedHashMap<>();
        String formattedQuality = format(quality);
        String formattedFresh = format(freshWeight);
        String formattedDry = format(dryWeight);
        double selectedWeight = type == CustomItemType.FRESH_BATCH ? freshWeight : dryWeight;
        if (selectedWeight <= 0.0 && freshWeight > 0.0) selectedWeight = freshWeight;
        values.put("strain", strainName);
        values.put("strain_id", strainId);
        values.put("strain_name", strainName);
        values.put("weight", format(selectedWeight));
        values.put("fresh_weight", formattedFresh);
        values.put("dry_weight", formattedDry);
        values.put("quality", formattedQuality);
        values.put("quality_grade", humanize(qualityGrade));
        values.put("state", humanize(state));
        values.put("batch_id", batchId == null ? "" : batchId.toString());
        values.put("components", components);
        values.put("duration", Math.max(0L, duration.toMinutes()));
        return Map.copyOf(values);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String humanize(String value) {
        if (value == null || value.isBlank()) return "";
        String lower = value.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

}
