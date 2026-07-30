package cn.net.rms.confluxmap.mc.ui.world;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class WaypointBeamBoundsTest {
    @Test
    void beamsSpanTheActiveWorldVerticalBounds() throws Exception {
        //#if MC>=12100
        //$$ final String source = Files.readString(preprocessedSource());
        //$$ final String renderBeams = methodBody(source, "\n    private void renderBeams(", "\n    private void renderHud(");
        //$$
        //$$ assertTrue(
        //$$     renderBeams.matches(
        //$$         "(?s).*final double bottomY = client\\.[^;]*(?:getBottom|getMinY)[^;]*;.*"
        //$$     ),
        //$$     "the beam must reach the active world's negative build height"
        //$$ );
        //$$ assertTrue(
        //$$     renderBeams.matches(
        //$$         "(?s).*final double topY = client\\.[^;]*(?:getTop|getMaxY)[^;]*;.*"
        //$$     ),
        //$$     "the beam must reach the active world's build limit"
        //$$ );
        //$$ assertTrue(
        //$$     renderBeams.contains("worldX, worldZ, bottomY, topY,"),
        //$$     "drawBeam must consume the active world's vertical bounds"
        //$$ );
        //#else
        // Minecraft 1.17.1 has no negative build height, so the 1.21+ regression does not apply.
        assertTrue(true);
        //#endif
    }

    private static Path preprocessedSource() throws URISyntaxException {
        Path current = Path.of(
            WaypointWorldRenderer.class.getProtectionDomain().getCodeSource().getLocation().toURI()
        );
        while (current != null && !"build".equals(current.getFileName().toString())) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Could not locate the version build directory");
        }
        return current.resolve(
            "preprocessed/main/java/cn/net/rms/confluxmap/mc/ui/world/WaypointWorldRenderer.java"
        );
    }

    private static String methodBody(final String source, final String startMarker, final String endMarker) {
        final int start = source.indexOf(startMarker);
        if (start < 0) {
            throw new IllegalStateException("Missing method " + startMarker.trim());
        }
        final int end = source.indexOf(endMarker, start + startMarker.length());
        if (end < 0) {
            throw new IllegalStateException("Missing method " + endMarker.trim());
        }
        return source.substring(start, end);
    }
}
