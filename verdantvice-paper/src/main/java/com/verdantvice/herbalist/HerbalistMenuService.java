package com.verdantvice.herbalist;

import com.verdantvice.contract.ContractRequirement;
import com.verdantvice.contract.ContractStatus;
import com.verdantvice.contract.ContractType;
import com.verdantvice.contract.StoredContract;
import com.verdantvice.i18n.TranslationService;
import com.verdantvice.market.MarketEventType;
import com.verdantvice.market.PaperMarketRecord;
import com.verdantvice.market.PaperMarketService;
import com.verdantvice.strain.StrainRegistry;
import com.verdantvice.trade.PreparedSale;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class HerbalistMenuService {
    private static final List<Integer> CONTENT = List.of(
            0, 1, 2, 3, 4, 5, 6, 7, 8,
            9, 10, 11, 12, 13, 14, 15, 16, 17,
            18, 19, 20, 21, 22, 23, 24, 25, 26,
            27, 28, 29, 30, 31, 32, 33, 34, 35,
            36, 37, 38, 39, 40, 41, 42, 43, 44);

    private final JavaPlugin plugin;
    private final TranslationService translations;
    private final StrainRegistry strains;
    private final PaperMarketService markets;

    public HerbalistMenuService(
            JavaPlugin plugin,
            TranslationService translations,
            StrainRegistry strains,
            PaperMarketService markets
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.translations = Objects.requireNonNull(translations, "translations");
        this.strains = Objects.requireNonNull(strains, "strains");
        this.markets = Objects.requireNonNull(markets, "markets");
    }

    public void openRoot(Player player, HerbalistRecord herbalist) {
        Map<Integer, String> actions = new HashMap<>();
        Inventory inventory = inventory(
                player, herbalist, HerbalistMenuType.ROOT,
                "gui.trade.title",
                Map.of("herbalist", translations.plain(player, herbalist.tier().translationKey())),
                actions);
        inventory.setItem(20, item(
                Material.WHEAT_SEEDS,
                translations.component(player, "gui.trade.buy"),
                List.of(translations.component(player, "common.level",
                        Map.of("level", translations.plain(player, herbalist.tier().translationKey()))))));
        actions.put(20, "open:buy");
        inventory.setItem(22, item(
                Material.EMERALD,
                translations.component(player, "gui.trade.sell"),
                List.of()));
        actions.put(22, "sale:prepare");
        inventory.setItem(24, item(
                Material.WRITABLE_BOOK,
                translations.component(player, "gui.contracts.title"),
                List.of()));
        actions.put(24, "open:contracts");
        inventory.setItem(31, item(
                Material.SUNFLOWER,
                translations.component(player, "gui.market.title"),
                List.of()));
        actions.put(31, "open:market");
        player.openInventory(inventory);
    }

    public void openBuy(Player player, HerbalistRecord herbalist, List<HerbalistOffer> offers) {
        Map<Integer, String> actions = new HashMap<>();
        Inventory inventory = inventory(
                player, herbalist, HerbalistMenuType.BUY,
                "gui.trade.title",
                Map.of("herbalist", translations.plain(player, herbalist.tier().translationKey())),
                actions);
        for (int index = 0; index < Math.min(CONTENT.size(), offers.size()); index++) {
            HerbalistOffer offer = offers.get(index);
            inventory.setItem(CONTENT.get(index), offerItem(player, offer));
            actions.put(CONTENT.get(index), "purchase:" + offer.id());
        }
        addBack(player, inventory, actions);
        player.openInventory(inventory);
    }

    public void openMarket(
            Player player,
            HerbalistRecord herbalist,
            List<PaperMarketRecord> records
    ) {
        Map<Integer, String> actions = new HashMap<>();
        Inventory inventory = inventory(
                player, herbalist, HerbalistMenuType.MARKET,
                "gui.market.title", Map.of(), actions);
        for (int index = 0; index < Math.min(CONTENT.size(), records.size()); index++) {
            PaperMarketRecord record = records.get(index);
            String name = strains.find(record.strainId())
                    .map(com.verdantvice.strain.Strain::displayName)
                    .orElse(record.strainId());
            String event = eventName(player, record.event().type(), record.strainId());
            inventory.setItem(CONTENT.get(index), item(
                    Material.EMERALD,
                    Component.text(name),
                    List.of(
                            translations.component(player, "gui.market.current-price",
                                    Map.of("price", format(record.currentPrice()))),
                            translations.component(player, "gui.market.supply",
                                    Map.of("supply", format(record.state().supplyIndex()))),
                            translations.component(player, "gui.market.demand",
                                    Map.of("demand", format(record.state().demandIndex()))),
                            translations.component(player, "gui.market.active-event",
                                    Map.of("event", event)))));
        }
        addBack(player, inventory, actions);
        player.openInventory(inventory);
    }

    public void openContracts(
            Player player,
            HerbalistRecord herbalist,
            List<StoredContract> contracts
    ) {
        Map<Integer, String> actions = new HashMap<>();
        Inventory inventory = inventory(
                player, herbalist, HerbalistMenuType.CONTRACTS,
                "gui.contracts.title", Map.of(), actions);
        int index = 0;
        for (StoredContract stored : contracts) {
            if (index >= CONTENT.size() - 1) break;
            int slot = CONTENT.get(index++);
            inventory.setItem(slot, contractItem(player, stored));
            if (stored.contract().status() == ContractStatus.COMPLETED && !stored.rewardClaimed()) {
                actions.put(slot, "contract:claim:" + stored.contract().contractId());
            } else if (stored.contract().status() == ContractStatus.ACTIVE) {
                actions.put(slot, "contract:deliver:" + stored.contract().contractId());
            }
        }
        if (index < CONTENT.size()) {
            int slot = CONTENT.get(index);
            inventory.setItem(slot, item(
                    Material.WRITABLE_BOOK,
                    translations.component(player, "gui.contracts.available"),
                    List.of(translations.component(player, "gui.contracts.accept"))));
            actions.put(slot, "contract:new");
        }
        addBack(player, inventory, actions);
        player.openInventory(inventory);
    }

    public void openSaleConfirm(
            Player player,
            HerbalistRecord herbalist,
            PreparedSale sale
    ) {
        Map<Integer, String> actions = new HashMap<>();
        Inventory inventory = inventory(
                player, herbalist, HerbalistMenuType.SALE_CONFIRM,
                "gui.trade.title",
                Map.of("herbalist", translations.plain(player, herbalist.tier().translationKey())),
                actions);
        inventory.setItem(13, item(
                Material.DRIED_KELP,
                Component.text(strains.find(sale.batch().strainComposition().keySet().iterator().next())
                        .map(com.verdantvice.strain.Strain::displayName)
                        .orElse(sale.batch().strainComposition().keySet().iterator().next())),
                List.of(
                        translations.component(player, "gui.trade.final-price",
                                Map.of("price", sale.payout())),
                        translations.component(player, "common.amount",
                                Map.of("amount", format(sale.quantity()))))));
        inventory.setItem(21, item(
                Material.LIME_CONCRETE,
                translations.component(player, "gui.trade.confirm-sell"),
                List.of()));
        actions.put(21, "sale:confirm:" + sale.quoteId());
        inventory.setItem(23, item(
                Material.RED_CONCRETE,
                translations.component(player, "common.cancel"),
                List.of()));
        actions.put(23, "open:root");
        player.openInventory(inventory);
    }

    private Inventory inventory(
            Player player,
            HerbalistRecord herbalist,
            HerbalistMenuType type,
            String titleKey,
            Map<String, ?> placeholders,
            Map<Integer, String> actions
    ) {
        HerbalistMenuHolder holder = new HerbalistMenuHolder(
                player.getUniqueId(), herbalist.herbalistUuid(), type, actions);
        Inventory inventory = plugin.getServer().createInventory(
                holder, 54, translations.component(player, titleKey, placeholders));
        holder.attach(inventory);
        ItemStack decoration = item(Material.BLACK_STAINED_GLASS_PANE, Component.empty(), List.of());
        for (int slot = 45; slot < 54; slot++) inventory.setItem(slot, decoration.clone());
        return inventory;
    }

    private ItemStack offerItem(Player player, HerbalistOffer offer) {
        Component name;
        if (offer.kind() == HerbalistOfferKind.SEED) {
            String strain = strains.require(offer.strainId().orElseThrow()).displayName();
            name = translations.component(player, "item.seed.name", Map.of("strain", strain));
        } else if (offer.kind() == HerbalistOfferKind.BOOK) {
            name = translations.component(player,
                    "book." + offer.bookType().orElseThrow().slug() + ".title");
        } else if (offer.kind() == HerbalistOfferKind.PLANTATION_CONTRACT) {
            name = translations.component(player, "item.plantation_contract.name");
        } else {
            name = translations.component(player,
                    "item." + offer.id().replace('-', '_') + ".name");
        }
        return item(
                offer.previewMaterial(), name,
                List.of(
                        translations.component(player, "common.price",
                                Map.of("price", offer.emeraldPrice())),
                        translations.component(player, "common.level",
                                Map.of("level", translations.plain(
                                        player, offer.requiredTier().translationKey())))));
    }

    private ItemStack contractItem(Player player, StoredContract stored) {
        ContractType type = stored.contract().type();
        String typeKey = switch (type) {
            case STRAIN_DELIVERY -> "strain-delivery";
            case QUALITY_DELIVERY -> "quality-delivery";
            case MULTI_STRAIN_DELIVERY -> "mixed-delivery";
            case EXPRESS_DELIVERY -> "timed-delivery";
            case CURED_PRODUCT -> "cured-product";
            case GENETIC_DELIVERY -> "genetic-delivery";
            case WHOLESALE -> "wholesale";
        };
        String requirementText = stored.contract().requirements().stream()
                .map(requirement -> requirementText(player, requirement))
                .collect(java.util.stream.Collectors.joining(" | "));
        long minutes = Math.max(0L,
                Duration.between(Instant.now(), stored.contract().deadline()).toMinutes());
        List<Component> lore = new ArrayList<>(List.of(
                translations.component(player, "gui.contracts.requirements",
                        Map.of("requirements", requirementText)),
                translations.component(player, "gui.contracts.deadline",
                        Map.of("deadline", minutes)),
                translations.component(player, "gui.contracts.reward",
                        Map.of("reward", stored.contract().reward().emeralds())),
                translations.component(player, "common.status",
                        Map.of("status", translations.plain(
                                player, contractStatusKey(stored))))));
        if (stored.contract().status() == ContractStatus.ACTIVE) {
            lore.add(translations.component(player, "gui.contracts.deliver"));
        } else if (stored.contract().status() == ContractStatus.COMPLETED
                && !stored.rewardClaimed()) {
            lore.add(translations.component(player, "gui.contracts.claim"));
        }
        return item(
                stored.rewardClaimed() ? Material.BOOK : Material.WRITABLE_BOOK,
                translations.component(player, "contracts.types." + typeKey),
                lore);
    }

    private String requirementText(Player player, ContractRequirement requirement) {
        String strain = requirement.strainId()
                .flatMap(strains::find)
                .map(com.verdantvice.strain.Strain::displayName)
                .orElseGet(() -> requirement.strainId()
                        .orElse(translations.plain(player, "common.none")));
        String grade = translations.plain(
                player,
                "quality.grades." + requirement.minimumQuality().name()
                        .toLowerCase(Locale.ROOT).replace('_', '-'));
        return strain + " · " + format(requirement.requiredWeight()) + " · " + grade;
    }

    private static String contractStatusKey(StoredContract stored) {
        if (stored.rewardClaimed()) return "contracts.statuses.claimed";
        return "contracts.statuses." + stored.contract().status().name()
                .toLowerCase(Locale.ROOT);
    }

    private void addBack(Player player, Inventory inventory, Map<Integer, String> actions) {
        inventory.setItem(49, item(
                Material.ARROW, translations.component(player, "common.back"), List.of()));
        actions.put(49, "open:root");
    }

    private String eventName(Player player, MarketEventType event, String strainId) {
        String key = markets.eventTranslationKey(event, strainId);
        if (event == MarketEventType.NONE) return translations.plain(player, key);
        return translations.plain(player, key + ".name");
    }

    private static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        meta.lore(new ArrayList<>(lore));
        item.setItemMeta(meta);
        return item;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
