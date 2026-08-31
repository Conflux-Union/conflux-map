package cn.net.rms.confluxmap.mc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import org.junit.jupiter.api.Test;

final class PlayerMarkerRendererTest {
    @Test
    void builtInMarkerTextureFollowsTheConfiguredStyle() {
        assertEquals(
            "confluxmap:textures/gui/markers/player_marker_modern.png",
            PlayerMarkerRenderer.builtInMarker(ConfluxConfig.PlayerMarkerStyle.MODERN)
                .texture().texture().toString()
        );
        assertEquals(
            "confluxmap:textures/gui/markers/player_marker_traditional.png",
            PlayerMarkerRenderer.builtInMarker(ConfluxConfig.PlayerMarkerStyle.TRADITIONAL)
                .texture().texture().toString()
        );
    }

    @Test
    void opacityScalesEveryMarkerColor() {
        assertEquals(0x7FFFFFFF, PlayerMarkerRenderer.colorAtOpacity(0xFFFFFFFF, 0.5f));
        assertEquals(0x7F101010, PlayerMarkerRenderer.colorAtOpacity(0xFF101010, 0.5f));
        assertEquals(0x26101010, PlayerMarkerRenderer.colorAtOpacity(0x4D101010, 0.5f));
    }

}
