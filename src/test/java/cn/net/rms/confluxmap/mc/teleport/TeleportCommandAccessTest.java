package cn.net.rms.confluxmap.mc.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TeleportCommandAccessTest {
    @Test
    void acceptsAConfiguredCommandExposedByTheServer() {
        final TeleportCommandAccess.Result result = TeleportCommandAccess.evaluate(
            "warp {x} {y} {z}", true, true, true, true,
            names -> names.equals(List.of("warp"))
        );

        assertTrue(result.available());
        assertEquals(null, result.reasonKey());
    }

    @Test
    void checksBothVanillaTeleportAliases() {
        final TeleportCommandAccess.Result result = TeleportCommandAccess.evaluate(
            "tp {x} {y} {z}", true, true, true, true,
            names -> names.equals(List.of("teleport", "tp"))
        );

        assertTrue(result.available());
    }

    @Test
    void explainsWhenTheConfiguredCommandIsNotAvailable() {
        final TeleportCommandAccess.Result result = TeleportCommandAccess.evaluate(
            "warp {x} {y} {z}", true, true, true, true, names -> false
        );

        assertFalse(result.available());
        assertEquals("confluxmap.teleport.unavailable.command", result.reasonKey());
    }

    @Test
    void requiresTheMatchingPlaceholderForCrossDimensionTeleport() {
        final TeleportCommandAccess.Result result = TeleportCommandAccess.evaluate(
            "tp {x} {y} {z}", true, true, false, true, names -> true
        );

        assertFalse(result.available());
        assertEquals("confluxmap.teleport.unavailable.dimension", result.reasonKey());
    }

    @Test
    void requiresKnownCoordinates() {
        final TeleportCommandAccess.Result result = TeleportCommandAccess.evaluate(
            "tp {x} {y} {z}", true, false, true, true, names -> true
        );

        assertFalse(result.available());
        assertEquals("confluxmap.teleport.unavailable.position", result.reasonKey());
    }
}
