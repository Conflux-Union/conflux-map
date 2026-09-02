package cn.net.rms.confluxmap.mc.radar;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class RadarIconBorderPolicyTest {
    @Test
    void entityPortraitsHaveAConfigurableShapeFollowingOutline() throws IOException {
        final Path root = findProjectRoot();
        final Path radar = root.resolve("src/main/java/cn/net/rms/confluxmap/mc/radar");
        final String renderer = Files.readString(radar.resolve("RadarMarkerRenderer.java"));
        final String manager = Files.readString(radar.resolve("EntityIconManager.java"));

        assertTrue(renderer.contains("config.radarPlayerIconOutlineEnabled"
            + " ? config.radarIconOutlineThickness : 0"));
        assertTrue(renderer.contains("if (outlineThickness > 0)"));
        assertTrue(renderer.contains("drawIconOutline("));
        assertTrue(renderer.contains("RenderUtil.drawDarkTextureOutline("));
        assertFalse(renderer.contains("RenderUtil.drawTintedOutline("));
        assertFalse(renderer.contains("outerWidth"));
        assertFalse(renderer.contains("outerHeight"));
        assertTrue(renderer.contains("final float iconWidth = iconSize * icon.widthScale()"));
        assertTrue(renderer.contains("final float iconHeight = iconSize * icon.heightScale()"));
        assertFalse(renderer.contains("bindItemOutlineTexture"));
        assertFalse(renderer.contains("bindOutlineTexture"));
        assertFalse(renderer.contains("contourBase("));
        assertFalse(renderer.contains("outlineHalfSize("));
        assertFalse(manager.contains("ItemIconOutlineTexture"));
        assertFalse(manager.contains("EntityIconOutlineTexture"));
        assertFalse(Files.exists(radar.resolve("ItemIconOutlineTexture.java")));
        assertFalse(Files.exists(radar.resolve("EntityIconOutlineTexture.java")));
        assertFalse(Files.exists(radar.resolve("RadarBackdrop.java")));
        assertFalse(Files.exists(root.resolve(
            "common/src/main/java/cn/net/rms/confluxmap/core/radar/IconOutliner.java"
        )));
    }

    private static Path findProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("common.gradle"))
                && Files.isDirectory(current.resolve("src/main/java"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate the Conflux Map project root");
    }
}
