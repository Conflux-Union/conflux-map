package cn.net.rms.confluxmap.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.net.Proto;
import org.junit.jupiter.api.Test;

final class WebMapConfigTest {
    @Test
    void defaultsAreEnabledOnLoopbackWithoutPlayerLocations() {
        final WebMapConfig config = new WebMapConfig();

        assertTrue(config.enabled);
        assertEquals("127.0.0.1", config.bindAddress);
        assertEquals(8123, config.port);
        assertFalse(config.sharePlayers);
        assertEquals(20, config.maxConnections);
        assertEquals(Proto.DEFAULT_MAX_BYTES_PER_SEC, config.maxBytesPerSecondPerAddress);
    }

    @Test
    void normalizeClampsUntrustedOperatorInput() {
        final WebMapConfig config = new WebMapConfig();
        config.bindAddress = " ";
        config.port = 99_999;
        config.maxConnections = -4;
        config.maxBytesPerSecondPerAddress = 1;
        config.minRequestIntervalMs = -3;

        config.normalize();

        assertEquals("127.0.0.1", config.bindAddress);
        assertEquals(65_535, config.port);
        assertEquals(1, config.maxConnections);
        assertEquals(1024, config.maxBytesPerSecondPerAddress);
        assertEquals(0, config.minRequestIntervalMs);
    }
}
