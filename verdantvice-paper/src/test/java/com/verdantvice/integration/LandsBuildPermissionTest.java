package com.verdantvice.integration;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class LandsBuildPermissionTest {
    private static final Location LOCATION = new Location(null, 37, 70, -22);

    @Test void permitsWorldsWhereLandsIsDisabled() throws ReflectiveOperationException {
        assertTrue(LandsBuildPermission.test(new Api(null), Flag.BLOCK_PLACE, Flag.class,
                null, LOCATION, Material.FARMLAND));
    }

    @Test void delegatesToLocationRoleFlagRatherThanLandLevelTrust() throws ReflectiveOperationException {
        LandWorld world = new LandWorld(true);
        assertTrue(LandsBuildPermission.test(new Api(world), Flag.BLOCK_PLACE, Flag.class,
                null, LOCATION, Material.FARMLAND));
        assertSame(LOCATION, world.location);
        assertEquals(Flag.BLOCK_PLACE, world.flag);
        assertEquals(Material.FARMLAND, world.material);
        assertFalse(world.message, "Permission checks must not spam denial messages");
    }

    @Test void deniesRestrictedSubareasAndWildernessAccordingToProvider() throws ReflectiveOperationException {
        assertFalse(LandsBuildPermission.test(new Api(new LandWorld(false)), Flag.BLOCK_PLACE,
                Flag.class, null, LOCATION, Material.AIR));
    }

    @Test void incompatibleApiDoesNotSilentlyAllowClaimedLocations() {
        assertThrows(NoSuchMethodException.class, () -> LandsBuildPermission.test(
                new Api(new Object()), Flag.BLOCK_PLACE, Flag.class, null, LOCATION, Material.AIR));
    }

    @Test void providerFailureIsPropagatedForConfiguredSafeFallback() {
        assertThrows(ReflectiveOperationException.class, () -> LandsBuildPermission.test(
                new BrokenApi(), Flag.BLOCK_PLACE, Flag.class, null, LOCATION, Material.AIR));
    }

    public enum Flag { BLOCK_PLACE }

    public static final class Api {
        private final Object world;
        public Api(Object world) { this.world = world; }
        public Object getWorld(World ignored) { return world; }
    }

    public static final class BrokenApi {
        public Object getWorld(World ignored) { throw new IllegalStateException("Provider unavailable"); }
    }

    public static final class LandWorld {
        private final boolean allowed;
        Location location;
        Flag flag;
        Material material;
        boolean message;
        public LandWorld(boolean allowed) { this.allowed = allowed; }
        public boolean hasRoleFlag(Player player, Location location, Flag flag,
                                   Material material, boolean message) {
            this.location = location;
            this.flag = flag;
            this.material = material;
            this.message = message;
            return allowed;
        }
    }
}
