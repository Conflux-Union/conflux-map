package cn.net.rms.confluxmap.mc.ui.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The waypoint visibility distance ({@code config.waypointRenderDistance})
 * is an in-world HUD cutoff only. The minimap must keep every waypoint in the
 * dimension addressable through its edge indicator, so the minimap renderer
 * must not consume that setting at all.
 */
final class MinimapWaypointDistanceTest {
    private static final float EPS = 1e-4f;

    @Test
    void minimapRendererDoesNotConsumeTheWorldRenderCutoff() throws IOException {
        final Path root = findProjectRoot();
        final String minimap = Files.readString(root.resolve(
            "src/main/java/cn/net/rms/confluxmap/mc/ui/hud/MinimapHudRenderer.java"
        ));

        assertFalse(minimap.contains("waypointRenderDistance"));
    }

    @Test
    void farWaypointClampsToTheSquareFrameEdgeInsteadOfVanishing() {
        final MinimapHudRenderer.WaypointMarkerOffset offset = MinimapHudRenderer.waypointMarkerOffset(
            5000.0, 0.0, 0.5f, 1f, 0f, 60f, false, true
        );

        // Any distance the world cutoff could hide must still land on the frame.
        assertEquals(60f, offset.x(), EPS);
        assertEquals(0f, offset.y(), EPS);
    }

    @Test
    void farWaypointClampsToTheCircleFrameRadius() {
        final MinimapHudRenderer.WaypointMarkerOffset offset = MinimapHudRenderer.waypointMarkerOffset(
            5000.0, 5000.0, 0.5f, 1f, 0f, 60f, true, true
        );

        assertEquals(60f / (float) Math.sqrt(2), offset.x(), EPS);
        assertEquals(60f / (float) Math.sqrt(2), offset.y(), EPS);
    }

    @Test
    void offFrameWaypointIsSkippedWhenEdgeIndicatorsAreDisabled() {
        assertNull(MinimapHudRenderer.waypointMarkerOffset(
            5000.0, 0.0, 0.5f, 1f, 0f, 60f, false, false
        ));
    }

    @Test
    void inFrameWaypointKeepsItsExactRotatedOffset() {
        final MinimapHudRenderer.WaypointMarkerOffset offset = MinimapHudRenderer.waypointMarkerOffset(
            40.0, -20.0, 0.5f, 1f, 0f, 60f, false, true
        );

        assertEquals(20f, offset.x(), EPS);
        assertEquals(-10f, offset.y(), EPS);
    }

    @Test
    void rotationMovesTheOffsetBeforeClamping() {
        final float cos = (float) Math.cos(Math.PI / 4);
        final float sin = (float) Math.sin(Math.PI / 4);
        final MinimapHudRenderer.WaypointMarkerOffset inFrame = MinimapHudRenderer.waypointMarkerOffset(
            80.0, 0.0, 0.5f, cos, sin, 60f, false, true
        );

        assertTrue(Math.hypot(inFrame.x(), inFrame.y()) < 60f + EPS);
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
