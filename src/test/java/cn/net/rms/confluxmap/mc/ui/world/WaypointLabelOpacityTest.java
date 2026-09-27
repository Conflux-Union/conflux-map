package cn.net.rms.confluxmap.mc.ui.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import org.junit.jupiter.api.Test;

final class WaypointLabelOpacityTest {
    @Test
    void labelOpacityFactorFollowsTheConfiguredPercentage() {
        final ConfluxConfig config = new ConfluxConfig();

        config.waypointIconOpacity = ConfluxConfig.MAX_WAYPOINT_ICON_OPACITY;
        assertEquals(1f, WaypointWorldRenderer.labelOpacityFactor(config));

        config.waypointIconOpacity = 50;
        assertEquals(0.5f, WaypointWorldRenderer.labelOpacityFactor(config));

        config.waypointIconOpacity = ConfluxConfig.MIN_WAYPOINT_ICON_OPACITY;
        assertEquals(0f, WaypointWorldRenderer.labelOpacityFactor(config));
    }
}
