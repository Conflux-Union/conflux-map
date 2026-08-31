package cn.net.rms.confluxmap.mc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
//#if MC<11900
import static org.junit.jupiter.api.Assertions.assertTrue;
//#endif

import cn.net.rms.confluxmap.core.config.ConfluxConfig;
//#if MC<11900
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
//#endif
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

    //#if MC<11900
    @Test
    void playerMarkerIgnoresLegacyGuiItemDepth() throws Exception {
        final String source = Files.readString(preprocessedSource());

        assertTrue(source.contains("RenderSystem.disableDepthTest();"));
        assertTrue(source.contains("RenderSystem.depthMask(false);"));
        assertTrue(source.contains("RenderSystem.depthMask(true);"));
        assertTrue(source.contains("RenderSystem.enableDepthTest();"));
    }
    //#endif

    @Test
    void opacityScalesEveryMarkerColor() {
        assertEquals(0x7FFFFFFF, PlayerMarkerRenderer.colorAtOpacity(0xFFFFFFFF, 0.5f));
        assertEquals(0x7F101010, PlayerMarkerRenderer.colorAtOpacity(0xFF101010, 0.5f));
        assertEquals(0x26101010, PlayerMarkerRenderer.colorAtOpacity(0x4D101010, 0.5f));
    }

    //#if MC<11900
    private static Path preprocessedSource() throws URISyntaxException {
        Path current = Path.of(
            PlayerMarkerRendererTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()
        );
        while (current != null && !"build".equals(current.getFileName().toString())) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Could not locate the version build directory");
        }
        final Path preprocessed = current.resolve(
            "preprocessed/main/java/cn/net/rms/confluxmap/mc/ui/PlayerMarkerRenderer.java"
        );
        if (Files.exists(preprocessed)) {
            return preprocessed;
        }
        return current.getParent().getParent().getParent().resolve(
            "src/main/java/cn/net/rms/confluxmap/mc/ui/PlayerMarkerRenderer.java"
        );
    }
    //#endif
}
