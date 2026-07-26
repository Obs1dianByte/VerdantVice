package com.verdantvice.herbalist;

import com.verdantvice.i18n.TranslationService;
import com.verdantvice.security.CustomItemType;
import com.verdantvice.security.ItemSigner;
import com.verdantvice.security.SignedItemData;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class TutorialBookService {
    private final TranslationService translations;
    private final ItemSigner signer;

    public TutorialBookService(TranslationService translations, ItemSigner signer) {
        this.translations = Objects.requireNonNull(translations, "translations");
        this.signer = Objects.requireNonNull(signer, "signer");
    }

    public PreparedBook basics(Player player) {
        return create(player, BookType.BASICS);
    }

    public PreparedBook advanced(Player player, BookType type) {
        if (type == BookType.BASICS) {
            throw new IllegalArgumentException("Use basics() for the main tutorial");
        }
        return create(player, type);
    }

    public List<BookType> advancedTypes() {
        return List.of(
                BookType.PLANT_HEALTH,
                BookType.STRAIN_GUIDE,
                BookType.DRYING_CURING,
                BookType.ADVANCED_GENETICS,
                BookType.FARM_MANAGEMENT,
                BookType.PERFECT_BATCH
        );
    }

    private PreparedBook create(Player player, BookType type) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        String base = "book." + type.slug;
        meta.title(translations.component(player, base + ".title"));
        meta.author(translations.component(player, base + ".author"));
        meta.setGeneration(BookMeta.Generation.ORIGINAL);
        List<Component> pages = new ArrayList<>(type.pages);
        for (int page = 1; page <= type.pages; page++) {
            pages.add(translations.component(player, base + ".page_" + page));
        }
        meta.addPages(pages.toArray(Component[]::new));
        book.setItemMeta(meta);

        SignedItemData data = new SignedItemData(
                type == BookType.BASICS
                        ? CustomItemType.GUIDE_BOOK
                        : CustomItemType.ADVANCED_BOOK,
                "book:" + type.slug,
                UUID.randomUUID(),
                null,
                player.getUniqueId(),
                0.0,
                "",
                Instant.now(),
                1
        );
        signer.sign(book, data);
        return new PreparedBook(book, data, signer.payloadHash(data));
    }

    public enum BookType {
        BASICS("basics", 16),
        PLANT_HEALTH("plant_health", 3),
        STRAIN_GUIDE("strain_guide", 3),
        DRYING_CURING("drying_curing", 3),
        ADVANCED_GENETICS("advanced_genetics", 3),
        FARM_MANAGEMENT("farm_management", 3),
        PERFECT_BATCH("perfect_batch", 3);

        private final String slug;
        private final int pages;

        BookType(String slug, int pages) {
            this.slug = slug;
            this.pages = pages;
        }

        public String slug() {
            return slug;
        }
    }

    public record PreparedBook(ItemStack stack, SignedItemData data, String payloadHash) {
        public PreparedBook {
            stack = stack.clone();
            Objects.requireNonNull(data, "data");
            Objects.requireNonNull(payloadHash, "payloadHash");
        }

        @Override
        public ItemStack stack() {
            return stack.clone();
        }
    }
}
