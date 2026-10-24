package com.verdantvice.gui;

import com.verdantvice.config.ConfigManager;
import com.verdantvice.farm.Farm;
import com.verdantvice.farm.FarmManager;
import com.verdantvice.farm.StorageLinkResult;
import com.verdantvice.farm.StorageType;
import com.verdantvice.i18n.TranslationService;
import com.verdantvice.notification.NotificationService;
import com.verdantvice.notification.NotificationType;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

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
    private final FarmManager farms;
    private final NotificationService notifications;

    public GuiManager(
            JavaPlugin plugin,
            ConfigManager configs,
            TranslationService translations,
            GuiDataProvider data,
            FarmManager farms,
            NotificationService notifications
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.translations = Objects.requireNonNull(translations, "translations");
        this.data = Objects.requireNonNull(data, "data");
        this.farms = Objects.requireNonNull(farms, "farms");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
    }

    public void openMain(Player player) {
        YamlConfiguration gui = configs.get("gui.yml");
        int size = normalizedSize(gui.getInt("main.size", 54));
        Map<Integer, String> actions = new HashMap<>();
        VerdantGuiHolder holder = new VerdantGuiHolder(
                player.getUniqueId(),
                MenuType.MAIN,
                0,
                GuiContext.empty(),
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
        open(player, type, requestedPage, GuiContext.empty());
    }

    public void openFarm(Player player, UUID farmId) {
        open(player, MenuType.FARM, 0, GuiContext.farm(farmId));
    }

    public void open(
            Player player,
            MenuType type,
            int requestedPage,
            GuiContext requestedContext
    ) {
        if (type == MenuType.MAIN) {
            openMain(player);
            return;
        }
        GuiContext context = data.resolveContext(player, type, requestedContext);
        data.accessDeniedKey(player, type, context).ifPresentOrElse(
                key -> player.sendMessage(translations.component(player, key)),
                () -> openResolved(player, type, requestedPage, context)
        );
    }

    private void openResolved(
            Player player,
            MenuType type,
            int requestedPage,
            GuiContext context
    ) {
        List<GuiDataProvider.MenuEntry> entries = data.entries(player, type, context);
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
                context,
                actions
        );
        Inventory inventory = plugin.getServer().createInventory(
                holder,
                54,
                translations.component(
                        player,
                        titleKey(type),
                        data.titlePlaceholders(player, type, context)
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
                            translations.component(
                                    player,
                                    data.emptyMessageKey(type, context)
                            ),
                            List.of(),
                            false
                    )
            );
        }

        YamlConfiguration gui = configs.get("gui.yml");
        int previous = gui.getInt("navigation.previous", 45);
        int back = gui.getInt("navigation.back", 49);
        int next = gui.getInt("navigation.next", 53);
        int refresh = gui.getInt("navigation.refresh", 50);
        if (page > 0) {
            inventory.setItem(previous, navigation(player, "previous"));
            actions.put(previous, "page:" + (page - 1));
        }
        inventory.setItem(back, navigation(player, "back"));
        actions.put(back, backAction(type, context));
        if (refreshable(type)
                && refresh >= 0
                && refresh < inventory.getSize()
                && refresh != previous
                && refresh != back
                && refresh != next) {
            inventory.setItem(refresh, navigation(player, "refresh"));
            actions.put(refresh, "refresh");
        }
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
            open(player, type, 0, holder.context().withoutSubject());
            return;
        }
        if (action.startsWith("page:")) {
            int page = Integer.parseInt(action.substring("page:".length()));
            open(player, holder.type(), page, holder.context());
            return;
        }
        if (action.equals("refresh")) {
            open(player, holder.type(), holder.page(), holder.context());
            return;
        }
        if (action.startsWith("confirm-storage:")) {
            UUID storageId = UUID.fromString(action.substring("confirm-storage:".length()));
            open(
                    player,
                    MenuType.STORAGE_UNLINK_CONFIRM,
                    0,
                    holder.context().withSubject(storageId)
            );
            return;
        }
        if (action.startsWith("unlink-storage:")) {
            UUID storageId = UUID.fromString(action.substring("unlink-storage:".length()));
            UUID farmId = holder.context().farmId().orElse(null);
            if (farmId == null) {
                player.sendMessage(translations.component(player, "gui.farm-panel.errors.no-context"));
                return;
            }
            farms.unlinkStorage(player, farmId, storageId)
                    .whenComplete((result, failure) -> plugin.getServer().getScheduler().runTask(
                            plugin,
                            () -> {
                                if (failure != null) {
                                    plugin.getLogger().log(
                                            Level.SEVERE,
                                            "Could not unlink farm storage from GUI",
                                            failure
                                    );
                                    player.sendMessage(translations.component(
                                            player,
                                            "errors.database-unavailable"
                                    ));
                                    return;
                                }
                                sendStorageResult(player, result);
                                open(
                                        player,
                                        MenuType.STORAGES,
                                        0,
                                        GuiContext.farm(farmId)
                                );
                            }
                    ));
            return;
        }
        if (action.startsWith("delete-farm:")) {
            UUID farmId = UUID.fromString(action.substring("delete-farm:".length()));
            Farm farm = farms.get(farmId).orElse(null);
            if (farm == null || !FarmMenuPolicy.mayDelete(
                    farm,
                    player.getUniqueId(),
                    player.hasPermission("verdantvice.admin")
            )) {
                player.sendMessage(translations.component(
                        player,
                        "gui.farm-panel.errors.no-access"
                ));
                return;
            }
            player.closeInventory();
            farms.delete(player, farmId).whenComplete((deleted, failure) ->
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (failure != null) {
                            plugin.getLogger().log(
                                    Level.SEVERE,
                                    "Could not delete farm " + farmId + " from GUI",
                                    failure
                            );
                            player.sendMessage(translations.component(
                                    player,
                                    "errors.internal",
                                    Map.of("error_id", farmId.toString().substring(0, 8))
                            ));
                            return;
                        }
                        if (!Boolean.TRUE.equals(deleted)) {
                            player.sendMessage(translations.component(
                                    player,
                                    "gui.farm-panel.errors.no-access"
                            ));
                            return;
                        }
                        player.sendMessage(translations.component(
                                player,
                                "success.farm-deleted",
                                Map.of("farm", farm.name())
                        ));
                        openMain(player);
                    }));
            return;
        }
        if (action.startsWith("notification:")) {
            String[] split = action.split(":", 3);
            NotificationType type = NotificationType.valueOf(split[1]);
            boolean enabled = Boolean.parseBoolean(split[2]);
            notifications.setEnabled(player, type, enabled);
            open(player, MenuType.NOTIFICATIONS, holder.page(), holder.context());
            return;
        }
        if (action.startsWith("command:")) {
            player.closeInventory();
            player.performCommand(action.substring("command:".length()));
        }
    }

    private void sendStorageResult(Player player, StorageLinkResult result) {
        String key = switch (result.status()) {
            case LINKED -> "farm.storage.linked";
            case UNLINKED -> "farm.storage.unlinked";
            case NO_OWNED_FARM -> "farm.storage.no-owned-farm";
            case NO_TARGET -> "farm.storage.no-target";
            case NOT_A_CONTAINER -> "farm.storage.not-container";
            case OUTSIDE_FARM -> "farm.storage.outside-farm";
            case ALREADY_LINKED -> "farm.storage.already-linked";
            case NOT_LINKED -> "farm.storage.not-linked";
            case SALARY_STORAGE_ALREADY_EXISTS -> "farm.storage.salary-exists";
            case NO_PERMISSION -> "errors.no-permission";
            case PROTECTED_REGION -> "errors.protected-region";
            case INVALID_STORAGE_TYPE -> "farm.storage.invalid-type";
            case OTHER_FARM -> "farm.storage.other-farm";
            case DATABASE_FAILURE -> "errors.database-unavailable";
        };
        Map<String, Object> placeholders = new HashMap<>();
        result.storage().ifPresent(storage -> placeholders.put("type", storage.type().argument()));
        placeholders.put("types", String.join(", ", StorageType.arguments()));
        player.sendMessage(translations.component(player, key, placeholders));
    }

    private String backAction(MenuType type, GuiContext context) {
        if (type == MenuType.STORAGE_UNLINK_CONFIRM) {
            return "open:storages";
        }
        if (context.farmId().isPresent() && switch (type) {
            case PLANTS, WORKERS, STORAGES, DRYING, CURING, PRIORITIES,
                 PERMISSIONS, SALARY, STATISTICS, NOTIFICATIONS, UPGRADES -> true;
            default -> false;
        }) {
            return "open:farm";
        }
        return "open:main";
    }

    private boolean refreshable(MenuType type) {
        return switch (type) {
            case FARM, PLANTS, WORKERS, STORAGES, DRYING, CURING, PRIORITIES,
                 PERMISSIONS, SALARY, STATISTICS, NOTIFICATIONS -> true;
            default -> false;
        };
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
            case "previous", "next" -> Material.ARROW;
            case "refresh" -> Material.SUNFLOWER;
            default -> Material.BARRIER;
        };
        String translation = switch (key) {
            case "previous" -> "common.previous-page";
            case "next" -> "common.next-page";
            case "refresh" -> "gui.navigation.refresh";
            default -> "common.back";
        };
        return item(
                material,
                translations.component(player, translation),
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
        return type.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private String titleKey(MenuType type) {
        return switch (type) {
            case DRYING, DRYING_RACK -> "gui.processing.drying-title";
            case CURING, CURING_CONTAINER -> "gui.processing.curing-title";
            case GRINDER -> "gui.processing.grinder-title";
            case MIXER -> "gui.processing.mixing-title";
            case ROLLING -> "gui.processing.rolling-title";
            case GUIDE -> "book.basics.title";
            case STORAGES -> "gui.farm-panel.storages.title";
            case STORAGE_UNLINK_CONFIRM -> "gui.farm-panel.storage-confirm.title";
            case PRIORITIES -> "gui.farm-panel.priorities.title";
            case PERMISSIONS -> "gui.farm-panel.permissions.title";
            case SALARY -> "gui.farm-panel.salary.title";
            case STATISTICS -> "gui.farm-panel.statistics.title";
            case NOTIFICATIONS -> "gui.farm-panel.notifications.title";
            case UPGRADES -> "gui.farm-panel.upgrades.title";
            default -> "gui." + slug(type) + ".title";
        };
    }
}
