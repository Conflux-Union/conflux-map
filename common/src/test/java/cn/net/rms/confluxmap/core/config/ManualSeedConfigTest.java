package cn.net.rms.confluxmap.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.WorldIdentity;
import org.junit.jupiter.api.Test;

class ManualSeedConfigTest {
    @Test
    void settingsStayBoundToOneMultiplayerWorld() {
        final WorldIdentity first = WorldIdentity.multiplayer("play.example.net", "lobby");
        final WorldIdentity second = WorldIdentity.multiplayer("play.example.net", "survival");
        final ManualSeedConfig settings = new ManualSeedConfig();

        settings.set(first, "Conflux Map", "1.21.1");

        final ManualSeedConfig.Entry configured = settings.get(first).orElseThrow();
        assertEquals("Conflux Map", configured.seedInput());
        assertEquals(474293735L, configured.seed());
        assertEquals("1.21.1", configured.worldgenVersion());
        assertTrue(settings.get(second).isEmpty());
    }

    @Test
    void copiedSettingsCanBeClearedWithoutChangingTheOriginal() {
        final WorldIdentity world = WorldIdentity.multiplayer("play.example.net");
        final ManualSeedConfig settings = new ManualSeedConfig();
        settings.set(world, "42", "1.17.1");

        final ManualSeedConfig copy = settings.copy();
        assertTrue(copy.clear(world));

        assertEquals(42L, settings.get(world).orElseThrow().seed());
        assertTrue(copy.get(world).isEmpty());
    }
}
