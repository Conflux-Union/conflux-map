package cn.net.rms.confluxmap.mc.radar;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class EntityIconManagerBundledTest {
    @Test
    void routesReleaseIconsBeforeDynamicPortraits() {
        assertTrue(EntityIconManager.hasBundledIcon("minecraft:creeper"));
        assertTrue(EntityIconManager.hasBundledIcon("minecraft:zombie"));
        assertTrue(EntityIconManager.hasBundledIcon("minecraft:ghast"));

        assertFalse(EntityIconManager.hasBundledIcon("minecraft:camel"));
        assertFalse(EntityIconManager.hasBundledIcon("minecraft:warden"));
        assertFalse(EntityIconManager.hasBundledIcon("example:modded_mob"));
    }
}
