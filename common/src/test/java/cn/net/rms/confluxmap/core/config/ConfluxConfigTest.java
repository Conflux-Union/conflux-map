package cn.net.rms.confluxmap.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class ConfluxConfigTest {
    @Test
    void minimapZoomCyclesThroughEveryLevelAndWraps() {
        final ConfluxConfig config = new ConfluxConfig();
        config.minimapZoomIndex = 0;

        config.cycleMinimapZoom();
        assertEquals(1, config.minimapZoomIndex);
        config.cycleMinimapZoom();
        assertEquals(2, config.minimapZoomIndex);
        config.cycleMinimapZoom();
        assertEquals(3, config.minimapZoomIndex);
        config.cycleMinimapZoom();
        assertEquals(0, config.minimapZoomIndex);
    }
}
