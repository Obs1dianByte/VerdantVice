package com.verdantvice.integrationprobe;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.verdantvice.VerdantVicePlugin;
import com.verdantvice.integration.RegionProtectionService;
import me.clip.placeholderapi.PlaceholderAPI;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/** Uses actual providers and an actual connected CraftPlayer, not mock plugins. */
public final class IntegrationSmoke extends JavaPlugin implements Listener {
    private final List<String> checks = new ArrayList<>();
    private boolean running;
    private boolean passed;

    @Override public void onEnable() {
        if (!Bukkit.getIp().equals("127.0.0.1") || !getServer().getWorldContainer()
                .getAbsolutePath().replace('\\', '/').contains("/build/minecraft-client/")) {
            throw new IllegalStateException("Integration probe is restricted to isolated loopback tests");
        }
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Waiting for the actual VVClientTest Minecraft client");
        for (Player player : Bukkit.getOnlinePlayers()) schedule(player);
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) { schedule(event.getPlayer()); }

    private void schedule(Player player) {
        if (player.getName().equals("VVClientTest")) {
            Bukkit.getScheduler().runTaskLater(this, () -> run(player), 80L);
        }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label,
                                       String[] arguments) {
        if (sender instanceof Player player && player.getName().equals("VVClientTest")) run(player);
        return true;
    }

    private void run(Player player) {
        if (running || passed || !player.isOnline()) return;
        running = true;
        checks.clear();
        try {
            Files.deleteIfExists(getDataFolder().toPath().resolve("integration.pass"));
            VerdantVicePlugin vv = (VerdantVicePlugin) Bukkit.getPluginManager().getPlugin("VerdantVice");
            check(vv != null && vv.ready(), "Production plugin is ready");
            check(player.getClass().getName().contains("CraftPlayer"), "Actual connected Minecraft player");
            for (String name : List.of("Vault", "PlaceholderAPI", "Essentials", "WorldGuard", "WorldEdit", "GriefPrevention")) {
                check(Bukkit.getPluginManager().isPluginEnabled(name), name + " is an actual enabled plugin");
            }
            check(vv.profiles().get(player.getUniqueId()).isPresent(), "Player profile is loaded");
            economy(vv, player);
            placeholders(vv, player);
            protections(vv, player);
            Path folder = getDataFolder().toPath();
            Files.createDirectories(folder);
            Files.write(folder.resolve("integration.pass"), checks, StandardCharsets.UTF_8);
            writeReport(null);
            passed = true;
            player.sendMessage("Integration verification passed: " + checks.size() + " checks.");
            getLogger().info("INTEGRATION PASS " + checks.size() + " checks");
        } catch (Throwable failure) {
            getLogger().log(Level.SEVERE, "INTEGRATION FAIL", failure);
            player.sendMessage("Integration verification failed; see integration-results.json.");
            try { writeReport(failure.toString()); } catch (Exception ignored) { }
        } finally { running = false; }
    }

    private void economy(VerdantVicePlugin vv, Player player) throws Exception {
        var economyConfig = vv.configs().get("economy.yml");
        var integrations = vv.configs().get("integrations.yml");
        Object previousProvider = economyConfig.get("provider");
        Object previousEnabled = economyConfig.get("vault.enabled");
        Object previousIntegration = integrations.get("vault");
        Economy provider = Bukkit.getServicesManager().getRegistration(Economy.class).getProvider();
        check(provider.getName().equals("Essentials Economy"), "Vault uses actual EssentialsX economy");
        double original = provider.getBalance(player);
        try {
            economyConfig.set("provider", "VAULT");
            economyConfig.set("vault.enabled", true);
            integrations.set("vault", true);
            vv.economy().detect();
            equal("Vault", vv.economy().providerName(), "Requested Vault provider detected");
            vv.economy().deposit(player, 100);
            near(original + 100, provider.getBalance(player), "Deposit reaches actual Essentials account");
            check(vv.economy().has(player, 100), "Vault balance check succeeds");
            check(vv.economy().withdraw(player, 37), "Vault withdrawal succeeds");
            near(original + 63, provider.getBalance(player), "Withdrawal debits exactly once");
            check(!vv.economy().withdraw(player, Integer.MAX_VALUE), "Insufficient funds rejected");
            near(original + 63, provider.getBalance(player), "Rejected withdrawal does not debit");
            vv.economy().deposit(player, 0);
            check(vv.economy().withdraw(player, 0), "Zero transaction is harmless");
            expectIllegalArgument(() -> vv.economy().has(player, -1), "Negative balance query rejected");
            expectIllegalArgument(() -> vv.economy().withdraw(player, -1), "Negative withdrawal rejected");
            expectIllegalArgument(() -> vv.economy().deposit(player, -1), "Negative deposit rejected");
            integrations.set("vault", false);
            vv.economy().detect();
            equal("Emerald", vv.economy().providerName(), "Disabled Vault integration respects configuration");
            integrations.set("vault", true);
            vv.economy().detect();
            equal("Vault", vv.economy().providerName(), "Vault detection recovers after re-enabling");
        } finally {
            double delta = provider.getBalance(player) - original;
            if (delta > 0) provider.withdrawPlayer(player, delta);
            else if (delta < 0) provider.depositPlayer(player, -delta);
            economyConfig.set("provider", previousProvider);
            economyConfig.set("vault.enabled", previousEnabled);
            integrations.set("vault", previousIntegration);
            vv.economy().detect();
        }
        near(original, provider.getBalance(player), "Original Essentials balance restored");
    }

    private void placeholders(VerdantVicePlugin vv, Player player) {
        var farm = vv.farms().owned(player.getUniqueId());
        equal(farm.map(f -> f.name()).orElse(""), parse(player, "farm_name"), "PAPI farm name");
        equal(Integer.toString(farm.map(f -> f.level()).orElse(0)), parse(player, "farm_level"), "PAPI farm level");
        equal(Long.toString(vv.plants().all().stream().filter(p -> p.ownerUuid().equals(player.getUniqueId())).count()),
                parse(player, "plants"), "PAPI plant count");
        equal(Long.toString(vv.plants().all().stream().filter(p -> p.ownerUuid().equals(player.getUniqueId()))
                .filter(p -> p.growthStage().name().equals("READY")).count()), parse(player, "ready_plants"), "PAPI ready count");
        equal(Integer.toString(farm.map(f -> vv.workers().workers(f.farmId()).size()).orElse(0)),
                parse(player, "workers"), "PAPI worker count");
        equal(Integer.toString((int) Math.round(vv.profiles().require(player.getUniqueId()).reputation())),
                parse(player, "reputation"), "PAPI reputation");
        check(parse(player, "tolerance").matches("[0-9]+\\.[0-9]"), "PAPI tolerance is locale-independent numeric output");
        check(parse(player, "market_berry_fog").matches("[0-9]+\\.[0-9]{2}"), "PAPI market is locale-independent numeric output");
        equal(parse(player, "plants"), parse(player, "PLANTS"), "PAPI parameter case normalization");
        equal("%verdantvice_unknown_value%", parse(player, "unknown_value"), "PAPI unknown parameter remains unresolved");
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "papi reload");
        equal(parse(player, "plants"), Long.toString(vv.plants().all().stream()
                .filter(p -> p.ownerUuid().equals(player.getUniqueId())).count()), "Internal expansion persists after PAPI reload");
    }

    private String parse(Player player, String parameter) {
        return PlaceholderAPI.setPlaceholders(player, "%verdantvice_" + parameter + "%");
    }

    private void protections(VerdantVicePlugin vv, Player player) throws Exception {
        var configuration = vv.configs().get("integrations.yml");
        Object wg = configuration.get("worldguard");
        Object gp = configuration.get("griefprevention");
        Object lands = configuration.get("lands");
        boolean op = player.isOp();
        String bypass = "worldguard.region.bypass." + player.getWorld().getName();
        var wgPlatform = WorldGuard.getInstance().getPlatform();
        var wgSettings = wgPlatform.getGlobalStateManager();
        boolean permissionCacheDisabled = wgSettings.disablePermissionCache;
        var regionManager = wgPlatform.getRegionContainer().get(BukkitAdapter.adapt(player.getWorld()));
        check(regionManager != null, "Actual WorldGuard region manager is available");
        String id = "vv_integration_" + UUID.randomUUID().toString().replace("-", "");
        ProtectedCuboidRegion region = new ProtectedCuboidRegion(id, BlockVector3.at(2000, -64, 2000),
                BlockVector3.at(2015, 319, 2015));
        PermissionAttachment permission = player.addAttachment(this);
        boolean ignoreClaims = GriefPrevention.instance.dataStore.getPlayerData(player.getUniqueId()).ignoreClaims;
        Claim claim = null;
        try {
            permission.setPermission(bypass, false);
            player.setOp(false);
            // Test immediate permission changes without waiting for the provider's
            // normal permission cache expiry. Restore the provider's default later.
            wgSettings.disablePermissionCache = true;
            configuration.set("worldguard", true);
            configuration.set("griefprevention", false);
            configuration.set("lands", false);
            RegionProtectionService protection = new RegionProtectionService(vv, vv.configs());
            protection.detect();
            equal(List.of("WorldGuard"), protection.activeAdapters(), "WorldGuard adapter detected");
            regionManager.addRegion(region);
            Location protectedLocation = new Location(player.getWorld(), 2005, 64, 2005);
            check(!protection.canBuild(player, protectedLocation), "WorldGuard denies non-member in protected region");
            region.getMembers().addPlayer(player.getUniqueId());
            check(protection.canBuild(player, protectedLocation), "WorldGuard permits region member");
            region.getMembers().removePlayer(player.getUniqueId());
            permission.setPermission(bypass, true);
            wgPlatform.getSessionManager().resetAllStates();
            check(protection.canBuild(player, protectedLocation), "WorldGuard explicit bypass is honored");
            permission.setPermission(bypass, false);
            wgPlatform.getSessionManager().resetAllStates();
            check(!protection.canBuild(player, protectedLocation), "Removing WorldGuard bypass restores protection");
            check(protection.canBuild(player, new Location(player.getWorld(), 1990, 64, 1990)), "WorldGuard permits unclaimed wilderness");

            configuration.set("worldguard", false);
            configuration.set("griefprevention", true);
            protection.detect();
            equal(List.of("GriefPrevention"), protection.activeAdapters(), "GriefPrevention adapter detected");
            var data = GriefPrevention.instance.dataStore;
            data.getPlayerData(player.getUniqueId()).ignoreClaims = false;
            var created = data.createClaim(player.getWorld(), 2100, 2115, player.getWorld().getMinHeight(),
                    player.getWorld().getMaxHeight() - 1, 2100, 2115, UUID.randomUUID(), null, null, null);
            check(created.succeeded && created.claim != null, "Actual GriefPrevention claim created");
            claim = created.claim;
            Location claimLocation = new Location(player.getWorld(), 2105, 64, 2105);
            check(!protection.canBuild(player, claimLocation), "GriefPrevention denies untrusted player");
            claim.setPermission(player.getUniqueId().toString(), ClaimPermission.Build);
            check(protection.canBuild(player, claimLocation), "GriefPrevention permits build-trusted player");
            check(protection.canBuild(player, new Location(player.getWorld(), 2090, 64, 2090)), "GriefPrevention permits unclaimed wilderness");
            configuration.set("worldguard", true);
            protection.detect();
            check(protection.activeAdapters().size() == 2, "Both real protection providers can be active");
            check(!protection.canBuild(player, protectedLocation), "Combined adapters preserve WorldGuard denial");
            configuration.set("worldguard", false);
            configuration.set("griefprevention", false);
            protection.detect();
            check(protection.activeAdapters().isEmpty(), "Disabled integrations do not leave stale adapters");
            check(protection.canBuild(player, protectedLocation), "Explicitly disabled providers no longer block");
        } finally {
            if (claim != null) GriefPrevention.instance.dataStore.deleteClaim(claim);
            GriefPrevention.instance.dataStore.getPlayerData(player.getUniqueId()).ignoreClaims = ignoreClaims;
            regionManager.removeRegion(id);
            configuration.set("worldguard", wg);
            configuration.set("griefprevention", gp);
            configuration.set("lands", lands);
            player.removeAttachment(permission);
            player.setOp(op);
            wgPlatform.getSessionManager().resetAllStates();
            wgSettings.disablePermissionCache = permissionCacheDisabled;
        }
        check(regionManager.getRegion(id) == null, "Temporary WorldGuard region removed");
    }

    private void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        checks.add(description);
    }

    private void equal(Object expected, Object actual, String description) {
        check(java.util.Objects.equals(expected, actual), description + " (expected=" + expected + ", actual=" + actual + ")");
    }

    private void near(double expected, double actual, String description) {
        check(Math.abs(expected - actual) < 0.001, description);
    }

    private void expectIllegalArgument(Runnable operation, String description) {
        try { operation.run(); } catch (IllegalArgumentException expected) { check(true, description); return; }
        throw new AssertionError(description);
    }

    private void writeReport(String failure) throws Exception {
        Path folder = getDataFolder().toPath();
        Files.createDirectories(folder);
        StringBuilder json = new StringBuilder("{\"time\":\"" + Instant.now() + "\",\"status\":\""
                + (failure == null ? "PASS" : "FAIL") + "\",\"checks\": [");
        for (int i = 0; i < checks.size(); i++) {
            if (i > 0) json.append(',');
            json.append('"').append(escape(checks.get(i))).append('"');
        }
        json.append("],\"failure\":").append(failure == null ? "null" : "\"" + escape(failure) + "\"")
                .append(",\"limitations\":[\"Lands runtime not tested: licensed plugin unavailable\","
                        + "\"ItemsAdder and Oraxen runtime not tested: licensed plugins unavailable\","
                        + "\"LuckPerms-specific permission setup not tested\"]}");
        Files.writeString(folder.resolve("integration-results.json"), json, StandardCharsets.UTF_8);
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><testsuite name=\"VerdantVice.RealIntegrations\" tests=\""
                + (checks.size() + (failure == null ? 0 : 1)) + "\" failures=\"" + (failure == null ? 0 : 1) + "\">");
        for (String check : checks) xml.append("<testcase name=\"").append(xml(check)).append("\"/>");
        if (failure != null) xml.append("<testcase name=\"Provider runtime\"><failure>")
                .append(xml(failure)).append("</failure></testcase>");
        xml.append("</testsuite>");
        Files.writeString(folder.resolve("junit.xml"), xml, StandardCharsets.UTF_8);
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
    private static String xml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;");
    }
}
