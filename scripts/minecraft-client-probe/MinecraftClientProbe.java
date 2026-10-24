package com.verdantvice.clientprobe;

import com.verdantvice.VerdantVicePlugin;
import com.verdantvice.gui.MenuType;
import com.verdantvice.gui.VerdantGuiHolder;
import com.verdantvice.security.CustomItemType;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;

/** Uses a connected Minecraft client; never creates a fake player or synthetic inventory event. */
public final class MinecraftClientProbe extends JavaPlugin implements Listener {
    private static final String PLAYER_NAME = "VVClientTest";
    private static final Set<MenuType> SAFE_MENUS = EnumSet.of(
            MenuType.MAIN, MenuType.STRAINS, MenuType.SKILLS, MenuType.MARKET,
            MenuType.SETTINGS, MenuType.GUIDE, MenuType.GENETICS,
            MenuType.CONTRACTS, MenuType.NOTIFICATIONS);
    private final Instant started = Instant.now();
    private final List<String> checks = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final Map<CustomItemType, ItemStack> issued = new LinkedHashMap<>();
    private final Set<MenuType> openedMenus = EnumSet.noneOf(MenuType.class);
    private VerdantVicePlugin target;
    private UUID playerUuid;
    private String playerClass = "not connected";
    private String clientLocale = "unknown";
    private String targetHash = "unknown";
    private int menuOpens;
    private int menuClicks;
    private int actionClicks;
    private int validatedItems;
    private boolean checking;
    private boolean checked;
    private boolean finished;
    private GameplayScenario gameplay;

    @Override
    public void onEnable() {
        try {
            require("127.0.0.1".equals(Bukkit.getIp()), "server binds exclusively to IPv4 loopback");
            require(Bukkit.getPluginManager().getPlugin("VerdantVice") instanceof VerdantVicePlugin,
                    "production VerdantVice plugin is loaded");
            target = (VerdantVicePlugin) Bukkit.getPluginManager().getPlugin("VerdantVice");
            gameplay = new GameplayScenario(this, target, this::require, this::fail,
                    description -> { events.add(description); writeReport("IN_PROGRESS"); });
            Path targetJar = Path.of(target.getClass().getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            targetHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(targetJar)));
            getServer().getPluginManager().registerEvents(this, this);
            archivePreviousSession();
            writeReport("IN_PROGRESS");
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (PLAYER_NAME.equals(player.getName())) attach(player);
            }
            getLogger().info("Real-client probe ready. Connect as VVClientTest, then use /vvclient check.");
        } catch (Throwable failure) {
            fail("probe initialization", failure);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (!finished) writeReport(failures.isEmpty() ? "INCOMPLETE" : "FAIL");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (PLAYER_NAME.equals(event.getPlayer().getName())) attach(event.getPlayer());
    }

    private void attach(Player player) {
        try {
            require(player.getAddress() != null && player.getAddress().getAddress().isLoopbackAddress(),
                    "test player connects over loopback");
            playerClass = player.getClass().getName();
            require(playerClass.contains(".craftbukkit.") && playerClass.endsWith(".CraftPlayer"),
                    "connected player is the actual CraftPlayer implementation");
            playerUuid = player.getUniqueId();
            clientLocale = player.locale().toString();
            player.setOp(true);
            player.setGameMode(GameMode.CREATIVE);
            events.add("JOIN " + playerUuid + " class=" + playerClass + " locale=" + clientLocale);
            player.sendMessage(Component.text("Client probe ready. Use /vvclient check, then /vvclient menu MAIN."));
            writeReport("IN_PROGRESS");
        } catch (Throwable failure) {
            fail("real player connection", failure);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player player = sender instanceof Player connected ? connected
                : sender instanceof org.bukkit.command.ConsoleCommandSender
                ? Bukkit.getPlayerExact(PLAYER_NAME) : null;
        if (player == null || !isParticipant(player)) {
            sender.sendMessage(Component.text("This test command is restricted to VVClientTest on loopback."));
            return true;
        }
        if (finished) return true;
        if (sender instanceof org.bukkit.command.ConsoleCommandSender) {
            events.add("SERVER_API_TEST_CONTROL " + String.join(" ", args));
        }
        try {
            String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
            switch (action) {
                case "check" -> checkItems(player);
                case "gallery" -> gallery(player);
                case "scenario" -> gameplay.command(player, args.length > 1 ? args[1] : "status");
                case "menu" -> {
                    if (!checked) {
                        player.sendMessage(Component.text("Run /vvclient check and wait for completion first."));
                    } else if (args.length != 2) {
                        player.sendMessage(Component.text("Usage: /vvclient menu MAIN (or STRAINS, SKILLS, MARKET, SETTINGS, GUIDE, GENETICS, CONTRACTS, NOTIFICATIONS)"));
                    } else {
                        MenuType type = MenuType.valueOf(args[1].toUpperCase(Locale.ROOT));
                        if (!SAFE_MENUS.contains(type)) throw new IllegalArgumentException("Menu requires scoped gameplay data: " + type);
                        target.gui().open(player, type, 0);
                    }
                }
                case "finish" -> finish(player);
                case "checkpoint" -> checkpoint(player);
                case "status" -> player.sendMessage(Component.text("Client checks: " + checks.size()
                        + "; items: " + validatedItems + "; GUI opens: " + menuOpens
                        + "; action clicks: " + actionClicks + "; failures: " + failures.size()));
                default -> player.sendMessage(Component.text("Usage: /vvclient check | gallery | menu MAIN | status | finish"));
            }
        } catch (Throwable failure) {
            fail("client command", failure);
            player.sendMessage(Component.text("Client probe failure recorded: " + failure));
        }
        return true;
    }

    private void checkItems(Player player) {
        if (checking || checked) {
            player.sendMessage(Component.text(checking ? "Item checks are still running." : "Item checks already completed."));
            return;
        }
        require(target.ready(), "production plugin reached READY with a connected client");
        checking = true;
        player.getInventory().clear();
        player.sendMessage(Component.text("Issuing and validating every non-batch item against the real database..."));
        CompletableFuture<Void> chain = target.profiles().load(player).thenApply(profile -> null);
        for (CustomItemType type : CustomItemType.values()) {
            if (type.batchBound()) continue;
            String strain = type == CustomItemType.SEED ? "berry_fog" : "";
            chain = chain.thenCompose(ignored -> onMain(() -> {
                        if (!player.isOnline()) throw new IllegalStateException("Client disconnected during item checks");
                        return target.secureItems().issue(player, type, player.getUniqueId(), strain);
                    }).thenCompose(issuedFuture -> issuedFuture))
                    .thenCompose(stack -> target.secureItems().validateActive(
                            stack, Set.of(type), player.getUniqueId()).thenCompose(validated -> onMain(() -> {
                        require(validated.data().itemType() == type,
                                "signed item type and database registry match: " + type);
                        require(player.getUniqueId().equals(validated.data().ownerUuid()),
                                "signed item ownership matches connected player: " + type);
                        require(player.getInventory().addItem(stack).isEmpty(),
                                "actual client inventory receives signed item: " + type);
                        issued.put(type, stack.clone());
                        validatedItems++;
                        return null;
                    })));
        }
        chain.whenComplete((ignored, failure) -> onMain(() -> {
            checking = false;
            if (failure != null) {
                fail("signed item checks", failure);
                player.sendMessage(Component.text("Item verification FAILED. See the English probe report."));
            } else {
                checked = true;
                player.saveData();
                require(validatedItems == CustomItemType.values().length - 7,
                        "all 18 non-batch item types were issued and validated");
                player.sendMessage(Component.text("PASS: " + validatedItems
                        + " signed items. Use /vvclient gallery or /vvclient menu MAIN and click a production button."));
                writeReport("IN_PROGRESS");
            }
            return null;
        }).exceptionally(callbackFailure -> {
            getLogger().log(Level.SEVERE, "Could not finish client item checks", callbackFailure);
            return null;
        }));
    }

    private void gallery(Player player) {
        if (!checked) {
            player.sendMessage(Component.text("Run /vvclient check and wait for completion first."));
            return;
        }
        GalleryHolder holder = new GalleryHolder();
        Inventory inventory = Bukkit.createInventory(holder, 54, Component.text("VerdantVice / Actual Signed Items"));
        holder.inventory = inventory;
        issued.values().forEach(stack -> inventory.addItem(stack.clone()));
        player.openInventory(inventory);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player) || !isParticipant(player)) return;
        if (event.getInventory().getHolder(false) instanceof VerdantGuiHolder holder) {
            menuOpens++;
            openedMenus.add(holder.type());
            events.add("GUI_OPEN type=" + holder.type() + " size=" + event.getInventory().getSize());
            writeReport("IN_PROGRESS");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !isParticipant(player)) return;
        try { gameplay.actualInventoryClick(event); }
        catch (Throwable failure) { fail("actual gameplay inventory click", failure); }
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof VerdantGuiHolder holder)
                || event.getRawSlot() < 0 || event.getRawSlot() >= top.getSize()) return;
        try {
            require(holder.ownerUuid().equals(player.getUniqueId()), "production GUI session belongs to the real client");
            require(event.isCancelled(), "production GUI prevents signed-item extraction on client click");
            menuClicks++;
            String action = holder.action(event.getRawSlot());
            if (action != null && !action.isBlank()) actionClicks++;
            events.add("GUI_CLICK type=" + holder.type() + " slot=" + event.getRawSlot()
                    + " action=" + action + " click=" + event.getClick() + " protected=" + event.isCancelled());
            writeReport("IN_PROGRESS");
        } catch (Throwable failure) {
            fail("real production GUI click", failure);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onGameplayBlockInteraction(PlayerInteractEvent event) {
        if (isParticipant(event.getPlayer())) gameplay.actualInteraction(event.getPlayer(), event.getAction().name());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onGameplayEntityInteraction(PlayerInteractEntityEvent event) {
        if (isParticipant(event.getPlayer())) gameplay.actualInteraction(event.getPlayer(), "ENTITY_" + event.getRightClicked().getType());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void protectGalleryClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof GalleryHolder) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void protectGalleryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof GalleryHolder) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPackStatus(PlayerResourcePackStatusEvent event) {
        if (isParticipant(event.getPlayer())) {
            events.add("RESOURCE_PACK " + event.getStatus());
            writeReport("IN_PROGRESS");
        }
    }

    private void finish(Player player) {
        if (checking) {
            player.sendMessage(Component.text("Wait for asynchronous signed-item checks to finish first."));
            return;
        }
        try {
            require(checked && validatedItems == 18, "all real-client signed item checks completed");
            require(menuOpens > 0 && !openedMenus.isEmpty(), "actual client opened a production VerdantVice inventory");
            require(menuClicks > 0 && actionClicks > 0, "actual client clicked an actionable production GUI button");
            require(failures.isEmpty(), "no probe assertion or asynchronous operation failed");
            if (gameplay.started()) require(gameplay.completed(), "complete gameplay acceptance scenario finished");
            player.saveData();
        } catch (Throwable failure) {
            fail("final real-client acceptance", failure);
        }
        finished = true;
        String status = failures.isEmpty() ? "PASS" : "FAIL";
        writeReport(status);
        getLogger().info("REAL CLIENT " + status + ": checks=" + checks.size() + ", signed items="
                + validatedItems + ", production opens=" + menuOpens + ", action clicks=" + actionClicks);
        player.sendMessage(Component.text("Real-client verification " + status + ". Report saved; isolated server is stopping."));
        Bukkit.getScheduler().runTaskLater(this, Bukkit::shutdown, 40L);
    }

    private void checkpoint(Player player) {
        require(gameplay.readyForRestart(), "all first-boot gameplay operations reached the durable restart checkpoint");
        require(checked && validatedItems == 18, "first-boot signed item checks completed before restart checkpoint");
        require(menuClicks > 0 && actionClicks > 0, "real client clicked a production GUI before the restart checkpoint");
        require(failures.isEmpty(), "first-boot gameplay checkpoint contains no failures");
        player.saveData();
        finished = true;
        writeReport("CHECKPOINT");
        player.sendMessage(Component.text("Gameplay checkpoint saved. Restart the same isolated server and run /vvclient scenario restart BEFORE /vvclient check."));
        Bukkit.getScheduler().runTaskLater(this, Bukkit::shutdown, 40L);
    }

    private void archivePreviousSession() throws java.io.IOException {
        Path directory = getDataFolder().toPath();
        if (!Files.isRegularFile(directory.resolve("summary.json"))) return;
        Path archive = directory.resolve("session-reports").resolve(Long.toString(System.currentTimeMillis()));
        Files.createDirectories(archive);
        for (String report : List.of("summary.json", "junit.xml", "checks.txt")) {
            Path source = directory.resolve(report);
            if (Files.isRegularFile(source)) Files.copy(source, archive.resolve(report));
        }
        events.add("PREVIOUS_SESSION_REPORTS " + archive.toAbsolutePath());
    }

    private boolean isParticipant(Player player) {
        return PLAYER_NAME.equals(player.getName()) && player.getAddress() != null
                && player.getAddress().getAddress().isLoopbackAddress()
                && (playerUuid == null || playerUuid.equals(player.getUniqueId()));
    }

    private void require(boolean condition, String description) {
        if (!condition) throw new IllegalStateException(description);
        checks.add(description);
    }

    private void fail(String context, Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        failures.add(context + ": " + cause);
        getLogger().log(Level.SEVERE, context, failure);
        writeReport("FAIL");
    }

    private <T> CompletableFuture<T> onMain(Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Runnable runnable = () -> {
            try { result.complete(action.get()); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        };
        if (Bukkit.isPrimaryThread()) runnable.run();
        else {
            try { Bukkit.getScheduler().runTask(this, runnable); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        }
        return result;
    }

    private void writeReport(String status) {
        try {
            Path directory = getDataFolder().toPath();
            Files.createDirectories(directory);
            List<String> reportFailures = new ArrayList<>(failures);
            if (!status.equals("PASS") && !status.equals("CHECKPOINT") && reportFailures.isEmpty()) {
                reportFailures.add("Real-client session has not completed successfully: " + status);
            }
            String json = "{\n  \"status\": " + quote(status)
                    + ",\n  \"startedUtc\": " + quote(started.toString())
                    + ",\n  \"updatedUtc\": " + quote(Instant.now().toString())
                    + ",\n  \"serverVersion\": " + quote(Bukkit.getVersion())
                    + ",\n  \"testedJarSha256\": " + quote(targetHash)
                    + ",\n  \"playerName\": " + quote(PLAYER_NAME)
                    + ",\n  \"playerUuid\": " + quote(String.valueOf(playerUuid))
                    + ",\n  \"playerClass\": " + quote(playerClass)
                    + ",\n  \"clientLocale\": " + quote(clientLocale)
                    + ",\n  \"signedItemsValidated\": " + validatedItems
                    + ",\n  \"productionGuiOpens\": " + menuOpens
                    + ",\n  \"productionGuiClicks\": " + menuClicks
                    + ",\n  \"productionActionClicks\": " + actionClicks
                    + ",\n  \"gameplayPhase\": " + (gameplay == null ? 0 : gameplay.phase())
                    + ",\n  \"visualRenderingAutomaticallyAsserted\": false"
                    + ",\n  \"checks\": " + array(checks)
                    + ",\n  \"events\": " + array(events)
                    + ",\n  \"failures\": " + array(reportFailures) + "\n}\n";
            Files.writeString(directory.resolve("summary.json"), json, StandardCharsets.UTF_8);
            StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite name=\"VerdantVice.RealMinecraftClient\" tests=\"")
                    .append(checks.size() + reportFailures.size()).append("\" failures=\"")
                    .append(reportFailures.size()).append("\" time=\"")
                    .append(Duration.between(started, Instant.now()).toMillis() / 1000.0).append("\">\n");
            for (String check : checks) xml.append("  <testcase name=\"").append(xml(check)).append("\"/>\n");
            for (String failure : reportFailures) xml.append("  <testcase name=\"real client acceptance\"><failure>")
                    .append(xml(failure)).append("</failure></testcase>\n");
            xml.append("</testsuite>\n");
            Files.writeString(directory.resolve("junit.xml"), xml, StandardCharsets.UTF_8);
            Files.writeString(directory.resolve("checks.txt"), "Status: " + status + "\n"
                    + String.join("\n", checks) + "\n\nEvents:\n" + String.join("\n", events)
                    + "\n\nFailures:\n" + String.join("\n", reportFailures) + "\n", StandardCharsets.UTF_8);
        } catch (Throwable reportFailure) {
            getLogger().log(Level.SEVERE, "Cannot persist real-client verification report", reportFailure);
            if (status.equals("PASS")) {
                failures.add("Report persistence failed: " + reportFailure);
                finished = false;
            }
        }
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        value.codePoints().forEach(codePoint -> {
            switch (codePoint) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (codePoint < 0x20) result.append(String.format(Locale.ROOT, "\\u%04x", codePoint));
                    else result.appendCodePoint(codePoint);
                }
            }
        });
        return result.append('"').toString();
    }

    private static String array(List<String> values) {
        return '[' + String.join(", ", values.stream().map(MinecraftClientProbe::quote).toList()) + ']';
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static final class GalleryHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() { return inventory; }
    }
}
