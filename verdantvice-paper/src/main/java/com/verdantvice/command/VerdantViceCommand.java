package com.verdantvice.command;

import com.verdantvice.VerdantVicePlugin;
import com.verdantvice.database.DatabaseDiagnostics;
import com.verdantvice.farm.Farm;
import com.verdantvice.farm.FarmRole;
import com.verdantvice.farm.StorageType;
import com.verdantvice.gui.MenuType;
import com.verdantvice.herbalist.HerbalistEvaluation;
import com.verdantvice.herbalist.HerbalistTier;
import com.verdantvice.herbalist.TutorialBookService;
import com.verdantvice.i18n.TranslationService;
import com.verdantvice.notification.NotificationType;
import com.verdantvice.plant.GrowthStage;
import com.verdantvice.processing.QualityGrade;
import com.verdantvice.resourcepack.ResourcePackService;
import com.verdantvice.security.CustomItemType;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import java.time.Instant;

public final class VerdantViceCommand implements CommandExecutor, TabCompleter {
    private static final List<String> PLAYER_COMMANDS = List.of(
            "help", "guide", "profile", "skills", "strains", "recipes",
            "market", "contracts", "genetics", "farm", "workers", "language",
            "notifications", "resourcepack"
    );
    private static final List<String> ADMIN_COMMANDS = List.of(
            "reload", "save", "give", "seed", "plant", "setstage",
            "setquality", "spawnherbalist", "hireworker", "removeplant",
            "inspect", "market", "contract", "resetplayer", "migrate",
            "database", "debug", "performance"
    );

    private final VerdantVicePlugin plugin;

    public VerdantViceCommand(VerdantVicePlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (!plugin.ready()) {
            sender.sendMessage(translation().component(sender, "errors.not-ready"));
            return true;
        }
        if (args.length == 0) {
            Player player = player(sender);
            if (player != null) {
                plugin.gui().openMain(player);
            }
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "help" -> help(sender);
                case "guide" -> guide(sender);
                case "profile" -> open(sender, MenuType.SKILLS);
                case "skills" -> open(sender, MenuType.SKILLS);
                case "strains" -> open(sender, MenuType.STRAINS);
                case "recipes" -> recipes(sender);
                case "market" -> open(sender, MenuType.MARKET, "verdantvice.trade");
                case "contracts" -> open(
                        sender,
                        MenuType.CONTRACTS,
                        "verdantvice.trade"
                );
                case "genetics" -> genetics(sender, args);
                case "farm" -> farm(sender, args);
                case "workers" -> open(
                        sender,
                        MenuType.WORKERS,
                        "verdantvice.worker.manage"
                );
                case "language" -> language(sender, args);
                case "notifications" -> notifications(sender, args);
                case "resourcepack" -> resourcePack(sender);
                case "admin" -> admin(sender, args);
                default -> usage(sender, "commands.root.usage");
            }
        } catch (IllegalArgumentException failure) {
            sender.sendMessage(translation().component(sender, "errors.invalid-usage",
                    Map.of("usage", translation().plain(sender, "commands." + sub + ".usage"))));
        } catch (IllegalStateException failure) {
            sender.sendMessage(translation().component(sender, "errors.internal",
                    Map.of("error_id", shortErrorId())));
            plugin.getLogger().log(
                    Level.WARNING,
                    "Command failed: /" + label + " " + String.join(" ", args),
                    failure
            );
        }
        return true;
    }

    private void help(CommandSender sender) {
        sender.sendMessage(translation().component(sender, "prefix"));
        for (String name : PLAYER_COMMANDS) {
            sender.sendMessage(Component.text("• ")
                    .append(translation().component(sender, "commands." + name + ".usage"))
                    .append(Component.text(" — "))
                    .append(translation().component(sender, "commands." + name + ".description")));
        }
        if (sender.hasPermission("verdantvice.admin")) {
            sender.sendMessage(translation().component(sender, "commands.admin.usage"));
        }
    }

    private void guide(CommandSender sender) {
        if (!sender.hasPermission("verdantvice.guide")) {
            deny(sender);
            return;
        }
        Player player = player(sender);
        if (player == null) {
            return;
        }
        TutorialBookService.PreparedBook prepared = plugin.books().basics(player);
        plugin.secureItems().register(prepared.stack()).whenComplete((book, failure) ->
                onMain(() -> {
                    if (failure != null) {
                        asyncFailure(player, failure);
                        return;
                    }
                    player.openBook(book);
                }));
    }

    private void recipes(CommandSender sender) {
        if (!sender.hasPermission("verdantvice.process")) {
            deny(sender);
            return;
        }
        Player player = player(sender);
        if (player == null) {
            return;
        }
        TutorialBookService.PreparedBook prepared = plugin.books().advanced(
                player,
                TutorialBookService.BookType.PERFECT_BATCH
        );
        plugin.secureItems().register(prepared.stack()).whenComplete((book, failure) ->
                onMain(() -> {
                    if (failure != null) {
                        asyncFailure(player, failure);
                    } else {
                        player.openBook(book);
                    }
                }));
    }

    private void open(CommandSender sender, MenuType type) {
        Player player = player(sender);
        if (player != null) {
            plugin.gui().open(player, type, 0);
        }
    }

    private void open(CommandSender sender, MenuType type, String permission) {
        if (!sender.hasPermission(permission)) {
            deny(sender);
            return;
        }
        open(sender, type);
    }

    private void genetics(CommandSender sender, String[] args) {
        if (!sender.hasPermission("verdantvice.genetics")) {
            deny(sender);
            return;
        }
        Player player = player(sender);
        if (player == null) {
            return;
        }
        if (args.length == 1) {
            plugin.gui().open(player, MenuType.GENETICS, 0);
            return;
        }
        String requestedName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        ItemStack firstSeed = player.getInventory().getItemInMainHand().clone();
        ItemStack secondSeed = player.getInventory().getItemInOffHand().clone();
        plugin.genetics().cross(player, requestedName, firstSeed, secondSeed)
                .whenComplete((result, failure) -> onMain(() -> {
                    if (failure != null) {
                        asyncFailure(player, failure);
                        return;
                    }
                    removeInventoryInstance(player, result.firstSeedId());
                    removeInventoryInstance(player, result.secondSeedId());
                    give(player, result.seed());
                    player.sendMessage(translation().component(
                            player,
                            "genetics.crossing.success",
                            Map.of(
                                    "line", result.strain().displayName(),
                                    "parent_a", result.parentA(),
                                    "parent_b", result.parentB()
                            )
                    ));
                }));
    }

    private void farm(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) {
            return;
        }
        if (args.length == 1) {
            if (!player.hasPermission("verdantvice.farm.member")
                    && !player.hasPermission("verdantvice.farm.manage")) {
                deny(player);
                return;
            }
            plugin.gui().open(player, MenuType.FARM, 0);
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (!player.hasPermission("verdantvice.farm.create")) {
                    deny(player);
                    return;
                }
                if (args.length < 3) {
                    usage(player, "commands.farm.create.usage");
                    return;
                }
                String name = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                plugin.farms().create(player, name).whenComplete((created, failure) ->
                        onMain(() -> {
                            if (failure != null) {
                                asyncFailure(player, failure);
                                return;
                            }
                            player.sendMessage(translation().component(
                                    player,
                                    "success.farm-created",
                                    Map.of("farm", created.name())
                            ));
                        }));
            }
            case "delete" -> {
                Farm owned = plugin.farms().owned(player.getUniqueId()).orElse(null);
                if (owned == null) {
                    player.sendMessage(translation().component(player, "errors.target-not-found"));
                    return;
                }
                plugin.farms().delete(player, owned.farmId()).whenComplete((deleted, failure) ->
                        onMain(() -> {
                            if (failure != null || !Boolean.TRUE.equals(deleted)) {
                                asyncFailure(player, failure);
                                return;
                            }
                            player.sendMessage(translation().component(
                                    player,
                                    "success.farm-deleted",
                                    Map.of("farm", owned.name())
                            ));
                        }));
            }
            case "invite" -> {
                if (args.length != 3) {
                    usage(player, "commands.farm.invite.usage");
                    return;
                }
                Player target = Bukkit.getPlayerExact(args[2]);
                Farm owned = plugin.farms().owned(player.getUniqueId()).orElse(null);
                if (target == null || owned == null) {
                    player.sendMessage(translation().component(player, "errors.invalid-player",
                            Map.of("player", args[2])));
                    return;
                }
                plugin.farms().addMember(
                        player,
                        owned.farmId(),
                        target.getUniqueId(),
                        FarmRole.MEMBER
                ).whenComplete((updated, failure) -> onMain(() -> {
                    if (failure != null) {
                        asyncFailure(player, failure);
                        return;
                    }
                    player.sendMessage(translation().component(
                            player,
                            "success.invitation-sent",
                            Map.of("player", target.getName())
                    ));
                }));
            }
            case "remove" -> {
                if (args.length != 3) {
                    usage(player, "commands.farm.remove.usage");
                    return;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
                Farm owned = plugin.farms().owned(player.getUniqueId()).orElse(null);
                if (owned == null) {
                    player.sendMessage(translation().component(player, "errors.target-not-found"));
                    return;
                }
                plugin.farms().removeMember(
                        player,
                        owned.farmId(),
                        target.getUniqueId()
                ).whenComplete((removed, failure) -> onMain(() -> {
                    if (failure != null || !Boolean.TRUE.equals(removed)) {
                        asyncFailure(player, failure);
                        return;
                    }
                    player.sendMessage(translation().component(
                            player,
                            "success.member-removed",
                            Map.of("player", Objects.toString(target.getName(), args[2]))
                    ));
                }));
            }
            case "storage" -> {
                if (args.length != 3) {
                    usage(player, "commands.farm.usage");
                    return;
                }
                Farm owned = plugin.farms().owned(player.getUniqueId()).orElse(null);
                Block target = player.getTargetBlockExact(6);
                if (owned == null || target == null) {
                    player.sendMessage(
                            translation().component(player, "errors.target-not-found")
                    );
                    return;
                }
                StorageType type = StorageType.valueOf(
                        args[2].toUpperCase(Locale.ROOT).replace('-', '_')
                );
                plugin.farms().linkStorage(
                        player,
                        owned.farmId(),
                        target,
                        type
                ).whenComplete((storage, failure) -> onMain(() -> {
                    if (failure != null) {
                        asyncFailure(player, failure);
                        return;
                    }
                    player.sendMessage(translation().component(
                            player,
                            "farm.storage.linked",
                            Map.of(
                                    "type",
                                    type.name().toLowerCase(Locale.ROOT)
                                            .replace('_', '-')
                            )
                    ));
                }));
            }
            default -> usage(player, "commands.farm.usage");
        }
    }

    private void language(CommandSender sender, String[] args) {
        if (!sender.hasPermission("verdantvice.language")) {
            deny(sender);
            return;
        }
        Player player = player(sender);
        if (player == null) {
            return;
        }
        if (args.length != 2
                || !translation().supportedLocales().contains(args[1])) {
            usage(player, "commands.language.usage");
            return;
        }
        plugin.profiles().setLocale(player, args[1]).whenComplete((profile, failure) ->
                onMain(() -> {
                    if (failure != null) {
                        asyncFailure(player, failure);
                        return;
                    }
                    player.sendMessage(translation().component(
                            player,
                            "success.language",
                            Map.of("language", args[1])
                    ));
                }));
    }

    private void notifications(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) {
            return;
        }
        if (args.length == 1) {
            plugin.gui().open(player, MenuType.NOTIFICATIONS, 0);
            return;
        }
        if (args.length != 3) {
            usage(player, "commands.notifications.usage");
            return;
        }
        NotificationType type = NotificationType.valueOf(
                args[1].toUpperCase(Locale.ROOT).replace('-', '_')
        );
        boolean enabled = switch (args[2].toLowerCase(Locale.ROOT)) {
            case "on", "true", "enable" -> true;
            case "off", "false", "disable" -> false;
            default -> throw new IllegalArgumentException("invalid toggle");
        };
        plugin.notifications().setEnabled(player, type, enabled);
        player.sendMessage(translation().component(
                player,
                enabled ? "notifications.enabled" : "notifications.disabled",
                Map.of("type", type.name(), "channel", "configured")
        ));
    }

    private void resourcePack(CommandSender sender) {
        if (!sender.hasPermission("verdantvice.resourcepack")) {
            deny(sender);
            return;
        }
        Player player = player(sender);
        if (player == null) {
            return;
        }
        ResourcePackService.SendResult result = plugin.resourcePack().send(player);
        String key = switch (result) {
            case SENT -> "success.resourcepack-sent";
            case DISABLED -> "common.disabled";
            case INVALID_CONFIGURATION -> "errors.configuration-invalid";
        };
        player.sendMessage(translation().component(
                player,
                key,
                Map.of("file", "resourcepack.yml")
        ));
    }

    private void admin(CommandSender sender, String[] args) {
        if (args.length < 2) {
            deny(sender);
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        String specificPermission = switch (sub) {
            case "reload" -> "verdantvice.admin.reload";
            case "give", "seed" -> "verdantvice.admin.give";
            case "inspect" -> "verdantvice.admin.inspect";
            case "debug" -> "verdantvice.admin.debug";
            case "database", "migrate" -> "verdantvice.admin.database";
            default -> "verdantvice.admin";
        };
        if (!sender.hasPermission("verdantvice.admin")
                && !sender.hasPermission(specificPermission)) {
            deny(sender);
            return;
        }
        plugin.getLogger().info("Admin action " + sub + " by " + sender.getName());
        switch (sub) {
            case "reload" -> adminReload(sender);
            case "save" -> plugin.profiles().saveAll().whenComplete((ignored, failure) ->
                    onMain(() -> {
                        if (failure != null) {
                            asyncFailure(sender, failure);
                        } else {
                            sender.sendMessage(translation().component(sender, "success.save"));
                        }
                    }));
            case "give" -> adminGive(sender, args, false);
            case "seed" -> adminGive(sender, args, true);
            case "plant" -> adminPlant(sender, args);
            case "setstage" -> adminSetStage(sender, args);
            case "setquality" -> adminSetQuality(sender, args);
            case "spawnherbalist" -> adminSpawnHerbalist(sender, args);
            case "hireworker" -> adminHireWorker(sender);
            case "removeplant" -> adminRemovePlant(sender);
            case "inspect" -> adminInspect(sender);
            case "resetplayer" -> adminResetPlayer(sender, args);
            case "migrate" -> plugin.database().start().whenComplete((report, failure) ->
                    onMain(() -> {
                        if (failure != null) {
                            asyncFailure(sender, failure);
                        } else {
                            sender.sendMessage(translation().component(
                                    sender,
                                    "success.database-migrated",
                                    Map.of("version", report.currentVersion())
                            ));
                        }
                    }));
            case "database" -> adminDatabase(sender);
            case "debug" -> plugin.diagnostics().create().whenComplete((path, failure) ->
                    onMain(() -> {
                        if (failure != null) {
                            asyncFailure(sender, failure);
                        } else {
                            sender.sendMessage(translation().component(
                                    sender,
                                    "success.debug-created",
                                    Map.of("path", path)
                            ));
                        }
                    }));
            case "performance" -> adminPerformance(sender);
            case "market" -> open(sender, MenuType.MARKET);
            case "contract" -> open(sender, MenuType.CONTRACTS);
            default -> usage(sender, "commands.admin.usage");
        }
    }

    private void adminSpawnHerbalist(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) {
            return;
        }
        HerbalistTier tier = args.length >= 3
                ? HerbalistTier.valueOf(args[2].toUpperCase(Locale.ROOT))
                : HerbalistTier.NOVICE;
        Entity target = player.getTargetEntity(8);
        Villager villager;
        boolean spawned;
        if (target instanceof Villager existing) {
            villager = existing;
            spawned = false;
            villager.setProfession(Villager.Profession.FARMER);
        } else {
            var targetBlock = player.getTargetBlockExact(8);
            var location = targetBlock == null
                    ? player.getLocation()
                    : targetBlock.getLocation().add(0.5, 1.0, 0.5);
            villager = player.getWorld().spawn(
                    location,
                    Villager.class,
                    created -> created.setProfession(Villager.Profession.FARMER)
            );
            spawned = true;
        }
        plugin.herbalists().forceCreate(villager)
                .thenCompose(evaluation -> {
                    if (evaluation.herbalist().isEmpty()
                            || tier == HerbalistTier.NOVICE) {
                        return CompletableFuture.completedFuture(evaluation);
                    }
                    return plugin.herbalists().herbalistRepository().addProgress(
                            evaluation.herbalist().orElseThrow().herbalistUuid(),
                            0L,
                            0,
                            tier,
                            Instant.now()
                    ).thenApply(ignored -> evaluation);
                })
                .whenComplete((evaluation, failure) -> onMain(() -> {
                    if (failure != null) {
                        if (spawned && villager.isValid()) {
                            villager.remove();
                        }
                        asyncFailure(sender, failure);
                        return;
                    }
                    if (evaluation.status()
                            == HerbalistEvaluation.Status.VILLAGE_LIMIT) {
                        if (spawned && villager.isValid()) {
                            villager.remove();
                        }
                        sender.sendMessage(
                                translation().component(sender, "herbalist.village-limit")
                        );
                        return;
                    }
                    if (evaluation.herbalist().isEmpty()) {
                        if (spawned && villager.isValid()) {
                            villager.remove();
                        }
                        sender.sendMessage(
                                translation().component(sender, "errors.target-not-found")
                        );
                        return;
                    }
                    sender.sendMessage(
                            translation().component(sender, "herbalist.created")
                    );
                }));
    }

    private void adminHireWorker(CommandSender sender) {
        Player player = player(sender);
        if (player == null) {
            return;
        }
        Entity target = player.getTargetEntity(8);
        if (!(target instanceof Villager villager)) {
            sender.sendMessage(translation().component(sender, "errors.target-not-found"));
            return;
        }
        plugin.workers().hire(player, villager).exceptionally(failure -> {
            onMain(() -> asyncFailure(sender, failure));
            return null;
        });
    }

    private void adminReload(CommandSender sender) {
        if (!sender.hasPermission("verdantvice.admin.reload")) {
            deny(sender);
            return;
        }
        var report = plugin.reloadSafe();
        sender.sendMessage(translation().component(sender, "success.reload"));
        if (!report.restartRequired().isEmpty()) {
            sender.sendMessage(translation().component(sender, "errors.restart-required"));
        }
    }

    private void adminGive(CommandSender sender, String[] args, boolean seedShortcut) {
        if (!sender.hasPermission("verdantvice.admin.give")) {
            deny(sender);
            return;
        }
        int required = seedShortcut ? 4 : 4;
        if (args.length < required) {
            usage(sender, seedShortcut
                    ? "commands.admin.seed.usage"
                    : "commands.admin.give.usage");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            sender.sendMessage(translation().component(sender, "errors.invalid-player",
                    Map.of("player", args[2])));
            return;
        }
        CustomItemType type = seedShortcut
                ? CustomItemType.SEED
                : parseItemType(args[3]);
        String strain = seedShortcut
                ? args[3].toLowerCase(Locale.ROOT)
                : "";
        int amount = 1;
        if (!seedShortcut && args.length >= 5) {
            if (isInteger(args[4])) {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[4])));
            } else {
                strain = args[4].toLowerCase(Locale.ROOT);
                if (args.length >= 6) {
                    amount = Math.max(1, Math.min(64, Integer.parseInt(args[5])));
                }
            }
        }
        if (type == CustomItemType.SEED) {
            plugin.strains().require(strain);
        }
        final String issueStrain = strain;
        final int issueAmount = amount;
        List<CompletableFuture<ItemStack>> futures = new ArrayList<>();
        for (int count = 0; count < issueAmount; count++) {
            futures.add(plugin.secureItems().issue(
                    target,
                    type,
                    target.getUniqueId(),
                    issueStrain
            ));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, failure) -> onMain(() -> {
                    if (failure != null) {
                        asyncFailure(sender, failure);
                        return;
                    }
                    futures.forEach(future -> give(target, future.join()));
                    sender.sendMessage(translation().component(
                            sender,
                            seedShortcut ? "success.seed-given" : "success.item-given",
                            Map.of(
                                    "strain", issueStrain,
                                    "amount", issueAmount,
                                    "item", type.name(),
                                    "player", target.getName()
                            )
                    ));
                }));
    }

    private void adminPlant(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) {
            return;
        }
        if (args.length != 3) {
            usage(sender, "commands.admin.plant.usage");
            return;
        }
        String strain = args[2].toLowerCase(Locale.ROOT);
        plugin.strains().require(strain);
        Block target = player.getTargetBlockExact(6);
        if (target == null || !plugin.plants().isStation(target)) {
            sender.sendMessage(translation().component(sender, "errors.target-not-found"));
            return;
        }
        plugin.secureItems().issue(
                player,
                CustomItemType.SEED,
                player.getUniqueId(),
                strain
        ).thenCompose(seed -> onMainFuture(() ->
                plugin.plants().plant(player, target, seed)
        )).thenCompose(future -> future).whenComplete((plant, failure) -> onMain(() -> {
            if (failure != null) {
                asyncFailure(sender, failure);
                return;
            }
            sender.sendMessage(translation().component(
                    sender,
                    "success.plant-created",
                    Map.of("plant_id", plant.uniqueId())
            ));
        }));
    }

    private void adminSetStage(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) {
            return;
        }
        if (args.length != 3) {
            usage(sender, "commands.admin.setstage.usage");
            return;
        }
        GrowthStage stage = GrowthStage.valueOf(args[2].toUpperCase(Locale.ROOT));
        var plant = targetedPlant(player);
        if (plant == null) {
            sender.sendMessage(translation().component(sender, "errors.target-not-found"));
            return;
        }
        plugin.plants().setStage(plant.uniqueId(), stage)
                .whenComplete((updated, failure) -> onMain(() -> {
                    if (failure != null) {
                        asyncFailure(sender, failure);
                    } else {
                        sender.sendMessage(translation().component(
                                sender,
                                "success.stage-set",
                                Map.of("stage", stage.name())
                        ));
                    }
                }));
    }

    private void adminSetQuality(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) {
            return;
        }
        if (args.length != 3) {
            usage(sender, "commands.admin.setquality.usage");
            return;
        }
        QualityGrade grade = QualityGrade.valueOf(args[2].toUpperCase(Locale.ROOT));
        plugin.processing().setQuality(
                player,
                player.getInventory().getItemInMainHand().clone(),
                grade
        ).whenComplete((ignored, failure) -> onMain(() -> {
            if (failure != null) {
                asyncFailure(sender, failure);
            } else {
                sender.sendMessage(translation().component(
                        sender,
                        "success.quality-set",
                        Map.of("quality", grade.name())
                ));
            }
        }));
    }

    private void adminRemovePlant(CommandSender sender) {
        Player player = player(sender);
        if (player == null) {
            return;
        }
        var plant = targetedPlant(player);
        if (plant == null) {
            sender.sendMessage(translation().component(sender, "errors.target-not-found"));
            return;
        }
        plugin.plants().remove(
                plant.uniqueId(),
                player.getUniqueId(),
                "ADMIN_REMOVED"
        ).whenComplete((removed, failure) -> onMain(() -> {
            if (failure != null || !Boolean.TRUE.equals(removed)) {
                asyncFailure(sender, failure);
            } else {
                sender.sendMessage(translation().component(
                        sender,
                        "success.plant-removed",
                        Map.of("plant_id", plant.uniqueId())
                ));
            }
        }));
    }

    private void adminInspect(CommandSender sender) {
        Player player = player(sender);
        if (player == null) {
            return;
        }
        var plant = targetedPlant(player);
        if (plant == null) {
            sender.sendMessage(translation().component(sender, "errors.target-not-found"));
            return;
        }
        plugin.plants().inspect(player, plant.uniqueId(), true);
    }

    private void adminResetPlayer(CommandSender sender, String[] args) {
        if (args.length != 4 || !"confirm".equalsIgnoreCase(args[3])) {
            usage(sender, "commands.admin.resetplayer.usage");
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
        plugin.profiles().reset(target.getUniqueId()).whenComplete((ignored, failure) ->
                onMain(() -> {
                    if (failure != null) {
                        asyncFailure(sender, failure);
                    } else {
                        sender.sendMessage(translation().component(
                                sender,
                                "success.player-reset",
                                Map.of("player", Objects.toString(target.getName(), args[2]))
                        ));
                    }
                }));
    }

    private void adminDatabase(CommandSender sender) {
        DatabaseDiagnostics diagnostics = plugin.database().diagnostics();
        sender.sendMessage(translation().component(
                sender,
                "common.status",
                Map.of("status",
                        diagnostics.type() + "/" + diagnostics.lifecycleState()
                                + " active=" + diagnostics.activeExecutorThreads()
                                + " queued=" + diagnostics.queuedOperations())
        ));
    }

    private void adminPerformance(CommandSender sender) {
        sender.sendMessage(translation().component(
                sender,
                "common.status",
                Map.of("status",
                        "plants=" + plugin.plants().all().size()
                                + " farms=" + plugin.farms().all().size()
                                + " dbQueue=" + plugin.database().diagnostics().queuedOperations())
        ));
    }

    private com.verdantvice.plant.Plant targetedPlant(Player player) {
        Block block = player.getTargetBlockExact(7);
        return block == null
                ? null
                : plugin.plants().at(block.getLocation()).orElse(null);
    }

    private CustomItemType parseItemType(String value) {
        return CustomItemType.valueOf(
                value.toUpperCase(Locale.ROOT).replace('-', '_')
        );
    }

    private boolean isInteger(String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private void give(Player player, ItemStack item) {
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        overflow.values().forEach(stack ->
                player.getWorld().dropItemNaturally(player.getLocation(), stack));
    }

    private void removeInventoryInstance(Player player, UUID instanceId) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            var verification = plugin.secureItems().verify(item);
            if (verification.valid()
                    && verification.data().uniqueInstanceId().equals(instanceId)) {
                player.getInventory().setItem(slot, null);
            }
        }
    }

    private Player player(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(translation().component(sender, "errors.player-only"));
            return null;
        }
        return player;
    }

    private void deny(CommandSender sender) {
        sender.sendMessage(translation().component(sender, "errors.no-permission"));
    }

    private void usage(CommandSender sender, String key) {
        sender.sendMessage(translation().component(
                sender,
                "errors.invalid-usage",
                Map.of("usage", translation().plain(sender, key))
        ));
    }

    private void asyncFailure(CommandSender sender, Throwable failure) {
        CommandFailureMapper.FailureMapping mapping = CommandFailureMapper.map(failure);
        sender.sendMessage(translation().component(
                sender,
                mapping.translationKey(),
                Map.of("limit", "configured")
        ));
        if (mapping.logStackTrace()) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "Asynchronous command operation failed",
                    mapping.cause()
            );
        }
    }

    private TranslationService translation() {
        return plugin.translations();
    }

    private void onMain(Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, action);
        }
    }

    private <T> CompletableFuture<T> onMainFuture(java.util.concurrent.Callable<T> action) {
        CompletableFuture<T> future = new CompletableFuture<>();
        onMain(() -> {
            try {
                future.complete(action.call());
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        });
        return future;
    }

    private Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private String shortErrorId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        if (args.length == 1) {
            List<String> values = new ArrayList<>(PLAYER_COMMANDS);
            if (sender.hasPermission("verdantvice.admin")) {
                values.add("admin");
            }
            return filter(values, args[0]);
        }
        if (args[0].equalsIgnoreCase("farm") && args.length == 2) {
            return filter(
                    List.of("create", "delete", "invite", "remove", "storage"),
                    args[1]
            );
        }
        if (args[0].equalsIgnoreCase("farm")
                && args.length == 3
                && args[1].equalsIgnoreCase("storage")) {
            return filter(
                    Arrays.stream(StorageType.values())
                            .map(value -> value.name().toLowerCase(Locale.ROOT)
                                    .replace('_', '-'))
                            .toList(),
                    args[2]
            );
        }
        if (args[0].equalsIgnoreCase("language") && args.length == 2) {
            return filter(plugin.translations().supportedLocales(), args[1]);
        }
        if (args[0].equalsIgnoreCase("notifications")) {
            if (args.length == 2) {
                return filter(Arrays.stream(NotificationType.values())
                        .map(value -> value.name().toLowerCase(Locale.ROOT)
                                .replace('_', '-'))
                        .toList(), args[1]);
            }
            if (args.length == 3) {
                return filter(List.of("on", "off"), args[2]);
            }
        }
        if (args[0].equalsIgnoreCase("admin")) {
            return adminComplete(args);
        }
        return List.of();
    }

    private List<String> adminComplete(String[] args) {
        if (args.length == 2) {
            return filter(ADMIN_COMMANDS, args[1]);
        }
        if (args.length == 3 && List.of(
                "give", "seed", "resetplayer"
        ).contains(args[1].toLowerCase(Locale.ROOT))) {
            return filter(Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .toList(), args[2]);
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("setstage")) {
            return filter(Arrays.stream(GrowthStage.values()).map(Enum::name).toList(), args[2]);
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("setquality")) {
            return filter(Arrays.stream(QualityGrade.values()).map(Enum::name).toList(), args[2]);
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("spawnherbalist")) {
            return filter(
                    Arrays.stream(HerbalistTier.values()).map(Enum::name).toList(),
                    args[2]
            );
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("give")) {
            return filter(Arrays.stream(CustomItemType.values())
                    .map(value -> value.name().toLowerCase(Locale.ROOT).replace('_', '-'))
                    .toList(), args[3]);
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("seed")) {
            return filter(plugin.strains().all().stream().map(value -> value.id()).toList(), args[3]);
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("plant")) {
            return filter(plugin.strains().all().stream().map(value -> value.id()).toList(), args[2]);
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("resetplayer")) {
            return filter(List.of("confirm"), args[3]);
        }
        return List.of();
    }

    private List<String> filter(Collection<String> values, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized))
                .sorted()
                .toList();
    }
}
