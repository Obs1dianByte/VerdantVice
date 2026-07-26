package com.verdantvice.strain;

import com.verdantvice.test.TestFixtures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StrainRegistryTest {
    @Test
    void registryRejectsDuplicatesButAllowsExplicitReplacement() {
        StrainRegistry registry = new StrainRegistry();
        registry.register(TestFixtures.strain());
        assertThrows(IllegalArgumentException.class, () -> registry.register(TestFixtures.strain()));
        registry.replace(TestFixtures.strain());
        assertEquals(1, registry.size());
        assertEquals("Berry Fog", registry.require("BERRY_FOG").displayName());
        assertTrue(registry.find("missing").isEmpty());
    }
}
