package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class FullscreenMapTileLayerOrderTest {
    @Test
    void drawTilesRendersCrossLodUnderlaysBeforeExactRealAndPredictedOverlay() throws IOException {
        final String source = Files.readString(
            findProjectRoot().resolve(
                "src/main/java/cn/net/rms/confluxmap/mc/ui/screen/FullscreenMapScreen.java"
            )
        );
        final String drawTilesBody = between(
            source,
            "private void drawTiles(final MatrixStack matrices, final boolean updateViewport)",
            "    private boolean resolveFallbackPreference("
        );

        final int predictedUnderlay = drawTilesBody.indexOf("drawPredictedUnderlays(");
        final int realShaderReset = drawTilesBody.indexOf("TextureBinds.beginTexturedQuads();", predictedUnderlay);
        final int realUnderlay = drawTilesBody.indexOf("drawRealUnderlays(");
        final int exactReal = drawTilesBody.indexOf("drawExactRealTiles(");
        final int exactPredicted = drawTilesBody.indexOf("drawExactPredictedOverlay(");
        final int preloadRing = drawTilesBody.indexOf("predictionTiles.preloadViewportPaddedRing(");

        assertTrue(predictedUnderlay >= 0, "predicted cross-LOD underlays must be drawn");
        assertTrue(
            realShaderReset > predictedUnderlay,
            "plain textured-quad shader must be re-bound after tinted underlay pass on MC < 1.21.5"
        );
        assertTrue(realUnderlay > realShaderReset, "real cross-LOD underlays must follow shader reset");
        assertTrue(exactReal > realUnderlay, "exact real tiles must paint over all cross-LOD underlays");
        assertTrue(
            exactPredicted > exactReal,
            "exact predicted/synchronized tiles must paint last to preserve syncWins over local tiles"
        );
        assertTrue(preloadRing > exactPredicted, "padded ring prefetch must trail all visible draw passes");
    }

    private static String between(final String source, final String start, final String end) {
        final int startIndex = source.indexOf(start);
        final int endIndex = source.indexOf(end, startIndex);
        assertTrue(startIndex >= 0 && endIndex > startIndex, "FullscreenMapScreen method bounds must exist");
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
