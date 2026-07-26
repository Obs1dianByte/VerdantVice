package com.verdantvice.herbalist;

import com.verdantvice.security.CustomItemType;
import org.bukkit.Material;

import java.util.Objects;
import java.util.Optional;

public record HerbalistOffer(
        String id,
        HerbalistOfferKind kind,
        Material previewMaterial,
        CustomItemType itemType,
        Optional<String> strainId,
        Optional<TutorialBookService.BookType> bookType,
        int emeraldPrice,
        HerbalistTier requiredTier
) {
    public HerbalistOffer {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(previewMaterial, "previewMaterial");
        Objects.requireNonNull(itemType, "itemType");
        strainId = Objects.requireNonNull(strainId, "strainId");
        bookType = Objects.requireNonNull(bookType, "bookType");
        if (emeraldPrice < 0) throw new IllegalArgumentException("emeraldPrice cannot be negative");
        Objects.requireNonNull(requiredTier, "requiredTier");
    }
}
