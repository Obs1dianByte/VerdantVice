package com.verdantvice.gui;

import com.verdantvice.config.ConfigManager;
import com.verdantvice.i18n.TranslationService;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class GuiManager {
    private static final List<Integer> CONTENT_SLOTS = List.of(
            0, 1, 2, 3, 4, 5, 6, 7, 8,
            9, 10, 11, 12, 13, 14, 15, 16, 17,
            18, 19, 20, 21, 22, 23, 24, 25, 26,
            27, 28, 29, 30, 31, 32, 33, 34, 35
    );

    private final JavaPlugin plugin;
    private final ConfigManager configs;
    private final TranslationService translations;
    private final GuiDataProvider data;

    public GuiManager(
            JavaPlugin plugin,
            ConfigManager configs,
            TranslationService translations,
            GuiDataProvider data
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.translations = Objects.requireNonNull(translations, "translations");
        this.data = Objects.requireNonNull(data, "data");
    }

    public void openMain(Player player) {
        YamlConfiguration gui = configs.get("gui.yml");
        int size = normalizedSize(gui.getInt("main.size", 54));
        Map<Integer, String> actions = new HashMap<>();
        VerdantGuiHolder holder = new VerdantGuiHolder(
                player.getUniqueId(),
                MenuType.MAIN,
                0,
                actions
        );
        Inventory inventory = plugin.getServer().createInventory(
                holder,
                size,
                translations.component(player, "gui.main.title")
        );
        holder.attach(inventory);
        fill(inventory, decoration(player));

        addMainButton(player, inventory, actions, gui, "my-farm", Material.LECTERN, MenuType.FARM);
        addMainButton(player, inventory, actions, gui, "plants", Material.FLOWERING_AZALEA, MenuType.PLANTS);
        addMainButton(player, inventory, actions, gui, "workers", Material.VILLAGER_SPAWN_EGG, MenuType.WORKERS);
        addMainButton(player, inventory, actions, gui, "strains", Material.WHEAT_SEEDS, MenuType.STRAINS);
        addMainButton(player, inventory, actions, gui, "genetics", Material.EXPERIENCE_BOTTLE, MenuType.GENETICS);
        addMainButton(player, inventory, actions, gui, "contracts", Material.WRITTEN_BOOK, MenuType.CONTRACTS);
        addMainButton(player, inventory, actions, gui, "market", Material.EMERALD, MenuType.MARKET);
        addMainButton(player, inventory, actions, gui, "skills", Material.ENCHANTED_BOOK, MenuType.SKILLS);
        addMainButton(player, inventory, actions, gui, "guide", Material.KNOWLEDGE_BOOK, MenuType.GUIDE);
        addMainButton(player, inventory, actions, gui, "settings", Material.COMPARATOR, MenuType.SETTINGS);
        player.openInventory(inventory);
    }

    public void open(Player player, MenuType type, int requestedPage) {
        if (type == MenuType.MAIN) {
            openMain(player);
            return;
        }
        List<GuiDataProvider.MenuEntry> entries = data.entries(player, type);
        int pageSize = Math.min(
                CONTENT_SLOTS.size(),
                Math.max(1, configs.get("gui.yml").getInt("page-size", 36))
        );
        int maximumPage = entries.isEmpty() ? 0 : (entries.size() - 1) / pageSize;
        int page = Math.max(0, Math.min(requestedPage, maximumPage));
        Map<Integer, String> actions = new HashMap<>();
        VerdantGuiHolder holder = new VerdantGuiHolder(
                player.getUniqueId(),
                type,
                page,
                actions
        );
        Inventory inventory = plugin.getServer().createInventory(
                holder,
                54,
                translations.component(
                        player,
                        titleKey(type),
                        data.titlePlaceholders(player, type)
                )
        );
        holder.attach(inventory);
        fill(inventory, decoration(player));

        int from = page * pageSize;
        int to = Math.min(entries.size(), from + pageSize);
        for (int index = from; index < to; index++) {
            int slot = CONTENT_SLOTS.get(index - from);
            GuiDataProvider.MenuEntry entry = entries.get(index);
            inventory.setItem(slot, item(
                    entry.icon(),
                    entry.name(),
                    entry.lore(),
                    entry.glowing()
            ));
            if (entry.action() != null && !entry.action().isBlank()) {
                actions.put(slot, entry.action());
            }
        }
        if (entries.isEmpty()) {
            inventory.setItem(
                    22,
                    item(
                            Material.GRAY_DYE,
                            translations.component(player, "common.none"),
                            List.of(),
                            false
                    )
            );
        }

        int previous = configs.get("gui.yml").getInt("navigation.previous", 45);
        int back = configs.get("gui.yml").getInt("navigation.back", 49);
        int next = configs.get("gui.yml").getInt("navigation.next", 53);
        if (page > 0) {
            inventory.setItem(previous, navigation(player, "previous"));
            actions.put(previous, "page:" + (page - 1));
        }
        inventory.setItem(back, navigation(player, "back"));
        actions.put(back, "open:main");
        if (page < maximumPage) {
            inventory.setItem(next, navigation(player, "next"));
            actions.put(next, "page:" + (page + 1));
        }
        player.openInventory(inventory);
    }

    public void execute(Player player, VerdantGuiHolder holder, String action) {
        if (action == null || action.isBlank()) {
            return;
        }
        if (action.startsWith("open:")) {
            MenuType type = MenuType.valueOf(
                    action.substring("open:".length()).toUpperCase(Locale.ROOT)
            );
            open(player, type, 0);
            return;
        }
        if (action.startsWith("page:")) {
            int page = Integer.parseInt(action.substring("page:".length()));
            open(player, holder.type(), page);
            return;
        }
        if (action.startsWith("command:")) {
            player.closeInventory();
            player.performCommand(action.substring("command:".length()));
        }
    }

    private void addMainButton(
            Player player,
            Inventory inventory,
            Map<Integer, String> actions,
            YamlConfiguration gui,
            String key,
            Material material,
            MenuType target
    ) {
        int slot = gui.getInt("main." + key, -1);
        if (slot < 0 || slot >= inventory.getSize()) {
            throw new IllegalStateException("Invalid GUI slot main." + key + ": " + slot);
        }
        inventory.setItem(
                slot,
                item(
                        material,
                        translations.component(player, "gui.main." + key + ".name"),
                        List.of(translations.component(
                                player,
                                "gui.main." + key + ".description"
                        )),
                        false
                )
        );
        actions.put(slot, "open:" + target.name().toLowerCase(Locale.ROOT));
    }

    private ItemStack navigation(Player player, String key) {
        Material material = switch (key) {
            case "previous" -> Material.ARROW;
            case "next" -> Material.ARROW;
            default -> Material.BARRIER;
        };
        return item(
                material,
                translations.component(
                        player,
                        switch (key) {
                            case "previous" -> "common.previous-page";
                            case "next" -> "common.next-page";
                            default -> "common.back";
                        }
                ),
                List.of(),
                false
        );
    }

    private ItemStack decoration(Player player) {
        String configured = configs.get("gui.yml")
                .getString("decoration-material", "BLACK_STAINED_GLASS_PANE");
        Material material = Material.matchMaterial(configured);
        if (material == null) {
            material = Material.BLACK_STAINED_GLASS_PANE;
        }
        return item(material, Component.empty(), List.of(), false);
    }

    private ItemStack item(
            Material material,
            Component name,
            List<Component> lore,
            boolean glowing
    ) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        if (!lore.isEmpty()) {
            meta.lore(new ArrayList<>(lore));
        }
        if (glowing) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        item.setItemMeta(meta);
        return item;
    }

    private void fill(Inventory inventory, ItemStack decoration) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, decoration.clone());
        }
    }

    private int normalizedSize(int value) {
        if (value < 9 || value > 54 || value % 9 != 0) {
            throw new IllegalStateException("GUI size must be a multiple of 9 from 9 to 54");
        }
        return value;
    }

    private String slug(MenuType type) {
        return type.name().toLowerCase(Locale.ROOT);
    }

    private String titleKey(MenuType type) {
        return switch (type) {
            case DRYING, DRYING_RACK -> "gui.processing.drying-title";
            case CURING, CURING_CONTAINER -> "gui.processing.curing-title";
            case GRINDER -> "gui.processing.grinder-title";
            case MIXER -> "gui.processing.mixing-title";
            case ROLLING -> "gui.processing.rolling-title";
            case GUIDE -> "book.basics.title";
            case STORAGES -> "gui.farm.storages.name";
            case PRIORITIES -> "gui.farm.priorities.name";
            case PERMISSIONS -> "gui.farm.permissions.name";
            case SALARY -> "gui.farm.salary.name";
            case STATISTICS -> "gui.farm.statistics.name";
            case NOTIFICATIONS -> "gui.farm.notifications.name";
            case UPGRADES -> "gui.farm.upgrade.name";
            default -> "gui." + slug(type) + ".title";
        };
    }
}
