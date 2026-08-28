package cn.net.rms.confluxmap.mc.radar;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * A portrait that could not be bound must leave the marker's shaped category dot on screen. The
 * dot is the only thing standing between a failed bake and an entity that silently disappears
 * from the radar.
 */
final class RadarMarkerFallbackTest {
    @Test
    void doesNotTreatAnUnboundDynamicIconAsDrawn() throws IOException {
        final String renderer = Files.readString(findProjectRoot().resolve(
            "src/main/java/cn/net/rms/confluxmap/mc/radar/RadarMarkerRenderer.java"
        ));

        assertTrue(renderer.contains("icon != null && drawIcon("));
        assertTrue(renderer.contains("private static boolean drawIcon("));
        assertTrue(renderer.matches(
            "(?s).*if \\(!iconManager\\.bindDynamicColor\\(\\)\\) \\{\\s+return false;.*"
        ));
    }

    @Test
    void compactFallbackUsesOneDiamondShapeForEveryNonPlayerCategory() throws IOException {
        final Path root = findProjectRoot();
        final String renderer = Files.readString(root.resolve(
            "src/main/java/cn/net/rms/confluxmap/mc/radar/RadarMarkerRenderer.java"
        ));
        final String renderUtil = Files.readString(root.resolve(
            "src/main/java/cn/net/rms/confluxmap/mc/render/RenderUtil.java"
        ));

        assertTrue(renderer.contains("private static final float DIAMOND_RADIUS = 2f;"));
        assertTrue(renderer.contains(
            "RenderUtil.fillBeveledDiamond(matrices, x, y, DIAMOND_RADIUS, color);"
        ));
        assertTrue(renderUtil.contains("public static void fillBeveledDiamond("));
        assertTrue(renderUtil.contains("final Mesh mesh = Mesh.beginGui(Mesh.Mode.QUADS"));
        assertFalse(renderer.contains("RenderUtil.fillDiamond("));
        assertFalse(renderer.contains(
            "RenderUtil.fillTriangle(matrices, x, y - 3.5f"
        ));
        assertFalse(renderer.contains("RenderUtil.drawRing(matrices, x, y, 2.5f"));
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
