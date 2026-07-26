package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class FullscreenMapLoadStateRenderTest {
    private static final Pattern LOADED_FILL_COLOR = Pattern.compile(
        "LOAD_STATE_(?:ENTITY|BLOCK|BORDER)_COLOR\\s*=\\s*0x([0-9A-Fa-f]{2})[0-9A-Fa-f]{6}"
    );

    @Test
    void loadStateRendersAsAnOverlayAfterTerrainWithoutClearingItsViewport() throws IOException {
        final String source = Files.readString(
            findProjectRoot().resolve(
                "src/main/java/cn/net/rms/confluxmap/mc/ui/screen/FullscreenMapScreen.java"
            )
        );
        final String renderContents = between(
            source,
            "protected void renderContents(",
            "    private boolean loadStateMode()"
        );
        final int terrain = renderContents.indexOf("drawTiles(matrices);");
        final int overlay = renderContents.indexOf("drawChunkLoadStateOverlay(draw);");

        assertTrue(terrain >= 0, "the terrain map must render in every fullscreen display mode");
        assertTrue(overlay > terrain, "the load-state plane must render after the terrain map");
        assertFalse(
            renderContents.contains("tiles.clearViewport()")
                || renderContents.contains("predictionTiles.clearViewport()"),
            "load-state mode must keep the normal map viewport active"
        );
    }

    @Test
    void loadedChunkFillsRemainSemiTransparentOverTheMap() throws IOException {
        final String source = Files.readString(
            findProjectRoot().resolve(
                "src/main/java/cn/net/rms/confluxmap/mc/ui/screen/FullscreenMapScreen.java"
            )
        );
        final Matcher colors = LOADED_FILL_COLOR.matcher(source);
        int matched = 0;
        while (colors.find()) {
            final int alpha = Integer.parseInt(colors.group(1), 16);
            assertTrue(alpha > 0 && alpha < 0x80, "load-state fills must reveal the terrain below");
            matched++;
        }
        assertTrue(matched == 3, "all three loaded-state fill colors must be covered");
    }

    private static String between(final String source, final String start, final String end) {
        final int startIndex = source.indexOf(start);
        final int endIndex = source.indexOf(end, startIndex);
        assertTrue(startIndex >= 0 && endIndex > startIndex, "fullscreen render method must be present");
        return source.substring(startIndex, endIndex);
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
