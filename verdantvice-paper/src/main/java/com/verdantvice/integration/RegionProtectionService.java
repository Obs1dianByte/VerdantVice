package com.verdantvice.integration;

import com.verdantvice.config.ConfigManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Level;

/**
 * Version-specific integrations are isolated here so the rest of the plugin
 * depends only on stable Bukkit types.
 */
public final class RegionProtectionService {
    private final JavaPlugin plugin;
    private final ConfigManager configs;
    private final List<ProtectionAdapter> adapters = new ArrayList<>();

    public RegionProtectionService(JavaPlugin plugin, ConfigManager configs) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    public void detect() {
        adapters.clear();
        PluginManager manager = plugin.getServer().getPluginManager();
        addIfEnabled(manager, "WorldGuard", "worldguard", WorldGuardAdapter::new);
        addIfEnabled(manager, "GriefPrevention", "griefprevention", GriefPreventionAdapter::new);
        addIfEnabled(manager, "Lands", "lands", LandsAdapter::new);
    }

    public boolean canBuild(Player player, Location location) {
        boolean denyUnknown = "DENY_IF_UNKNOWN".equalsIgnoreCase(
                configs.get("integrations.yml")
                        .getString("region-protection-fallback", "DENY_IF_UNKNOWN")
        );
        for (ProtectionAdapter adapter : adapters) {
            Decision decision;
            try {
                decision = adapter.canBuild(player, location);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                plugin.getLogger().log(
                        Level.WARNING,
                        "Region integration " + adapter.name() + " failed safely",
                        exception
                );
                decision = Decision.UNKNOWN;
            }
            if (decision == Decision.DENY || decision == Decision.UNKNOWN && denyUnknown) {
                return false;
            }
        }
        return true;
    }

    public List<String> activeAdapters() {
        return adapters.stream().map(ProtectionAdapter::name).toList();
    }

    private void addIfEnabled(
            PluginManager manager,
            String pluginName,
            String configKey,
            AdapterFactory factory
    ) {
        if (!configs.get("integrations.yml").getBoolean(configKey, true)
                || !manager.isPluginEnabled(pluginName)) {
            return;
        }
        Plugin dependency = manager.getPlugin(pluginName);
        if (dependency != null) {
            adapters.add(factory.create(dependency));
        }
    }

    private interface AdapterFactory {
        ProtectionAdapter create(Plugin dependency);
    }

    private interface ProtectionAdapter {
        String name();

        Decision canBuild(Player player, Location location) throws ReflectiveOperationException;
    }

    private enum Decision {
        ALLOW,
        DENY,
        UNKNOWN
    }

    private static final class WorldGuardAdapter implements ProtectionAdapter {
        private final Plugin dependency;

        private WorldGuardAdapter(Plugin dependency) {
            this.dependency = dependency;
        }

        @Override
        public String name() {
            return dependency.getName();
        }

        @Override
        public Decision canBuild(Player player, Location location) throws ReflectiveOperationException {
            ClassLoader loader = dependency.getClass().getClassLoader();
            Class<?> worldGuard = Class.forName("com.sk89q.worldguard.WorldGuard", true, loader);
            Object instance = worldGuard.getMethod("getInstance").invoke(null);
            Object platform = worldGuard.getMethod("getPlatform").invoke(instance);
            Object regionContainer = platform.getClass().getMethod("getRegionContainer").invoke(platform);
            Object query = regionContainer.getClass().getMethod("createQuery").invoke(regionContainer);

            Class<?> bukkitAdapter = Class.forName(
                    "com.sk89q.worldedit.bukkit.BukkitAdapter",
                    true,
                    loader
            );
            Object adaptedLocation = bukkitAdapter
                    .getMethod("adapt", Location.class)
                    .invoke(null, location);

            Class<?> wgPluginClass = Class.forName(
                    "com.sk89q.worldguard.bukkit.WorldGuardPlugin",
                    true,
                    loader
            );
            Object wgPlugin = wgPluginClass.getMethod("inst").invoke(null);
            Object localPlayer = wgPluginClass.getMethod("wrapPlayer", Player.class)
                    .invoke(wgPlugin, player);

            Class<?> flags = Class.forName(
                    "com.sk89q.worldguard.protection.flags.Flags",
                    true,
                    loader
            );
            Object buildFlag = flags.getField("BUILD").get(null);
            Method testState = findMethod(query.getClass(), "testState", 3);
            Class<?> flagType = testState.getParameterTypes()[2].componentType();
            Object flagArray = Array.newInstance(flagType, 1);
            Array.set(flagArray, 0, buildFlag);
            boolean allowed = (boolean) testState.invoke(
                    query,
                    adaptedLocation,
                    localPlayer,
                    flagArray
            );
            return allowed ? Decision.ALLOW : Decision.DENY;
        }
    }

    private static final class GriefPreventionAdapter implements ProtectionAdapter {
        private final Plugin dependency;

        private GriefPreventionAdapter(Plugin dependency) {
            this.dependency = dependency;
        }

        @Override
        public String name() {
            return dependency.getName();
        }

        @Override
        public Decision canBuild(Player player, Location location) throws ReflectiveOperationException {
            Field dataStoreField = dependency.getClass().getField("dataStore");
            Object dataStore = dataStoreField.get(dependency);
            Method getClaimAt = findMethod(dataStore.getClass(), "getClaimAt", 3);
            Object claim = getClaimAt.invoke(dataStore, location, true, null);
            if (claim == null) {
                return Decision.ALLOW;
            }
            Method allowBuild = findMethod(claim.getClass(), "allowBuild", 2);
            Object denial = allowBuild.invoke(claim, player, location.getBlock().getType());
            return denial == null ? Decision.ALLOW : Decision.DENY;
        }
    }

    private static final class LandsAdapter implements ProtectionAdapter {
        private final Plugin dependency;
        private Object integration;

        private LandsAdapter(Plugin dependency) {
            this.dependency = dependency;
        }

        @Override
        public String name() {
            return dependency.getName();
        }

        @Override
        public Decision canBuild(Player player, Location location) throws ReflectiveOperationException {
            if (integration == null) {
                Class<?> integrationClass = Class.forName(
                        "me.angeschossen.lands.api.LandsIntegration",
                        true,
                        dependency.getClass().getClassLoader()
                );
                integration = integrationClass.getMethod("of", Plugin.class)
                        .invoke(null, dependency);
            }
            Method getLand = findMethod(integration.getClass(), "getLandByChunk", 3);
            Object land = getLand.invoke(
                    integration,
                    location.getWorld(),
                    location.getBlockX() >> 4,
                    location.getBlockZ() >> 4
            );
            if (land == null) {
                return Decision.ALLOW;
            }
            Method trusted = findCompatibleMethod(land.getClass(), "isTrusted", player);
            if (trusted == null) {
                return Decision.UNKNOWN;
            }
            return (boolean) trusted.invoke(land, player)
                    ? Decision.ALLOW
                    : Decision.DENY;
        }
    }

    private static Method findMethod(Class<?> type, String name, int parameters)
            throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameters) {
                return method;
            }
        }
        throw new NoSuchMethodException(type.getName() + "#" + name + "/" + parameters);
    }

    private static Method findCompatibleMethod(Class<?> type, String name, Object argument) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name)
                    && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isAssignableFrom(argument.getClass())) {
                return method;
            }
        }
        return null;
    }
}
