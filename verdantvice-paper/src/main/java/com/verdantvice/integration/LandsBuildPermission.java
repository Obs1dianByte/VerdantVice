package com.verdantvice.integration;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

/** Isolates the Lands role-flag query without linking its optional API. */
final class LandsBuildPermission {
    private LandsBuildPermission() { }

    static boolean test(Object integration, Object buildFlag, Class<?> roleFlagType,
                        Player player, Location location, Material material)
            throws ReflectiveOperationException {
        Object world = integration.getClass().getMethod("getWorld", World.class)
                .invoke(integration, location.getWorld());
        if (world == null) {
            return true; // Lands is not enabled in this world.
        }
        // LandWorld includes subareas, visitor roles and wilderness restrictions.
        // Land-level trust alone is neither necessary nor sufficient for building.
        return (boolean) world.getClass().getMethod("hasRoleFlag", Player.class,
                        Location.class, roleFlagType, Material.class, boolean.class)
                .invoke(world, player, location, buildFlag, material, false);
    }
}
