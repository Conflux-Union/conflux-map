package cn.net.rms.confluxmap.core.export;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.util.Argb;
import org.junit.jupiter.api.Test;

final class MapExportCompositorTest {
    private static final int BACKGROUND = 0xFF101018;

    @Test
    void realPixelCoversTintedPredictionOverMapBackground() {
        final int predicted = 0xFF80A060;
        final int tint = 0xFF808080;
        final int translucentReal = 0x800000FF;

        final int result = MapExportCompositor.compose(
            BACKGROUND, predicted, translucentReal, tint, 0x00000000
        );

        final int expectedPrediction = 0xFF405030;
        assertEquals(Argb.over(translucentReal, expectedPrediction), result);
    }

    @Test
    void hiddenPredictionAndUnknownRealLeaveOpaqueBackground() {
        assertEquals(
            BACKGROUND,
            MapExportCompositor.compose(
                BACKGROUND, 0x00000000, 0x00000000, 0xFFFFFFFF, 0x00000000
            )
        );
    }

    @Test
    void chunkLoadColorIsLastTranslucentLayer() {
        final int real = 0xFF204060;
        final int overlay = 0x7048B85E;

        assertEquals(
            Argb.over(overlay, real),
            MapExportCompositor.compose(
                BACKGROUND, 0x00000000, real, 0xFFFFFFFF, overlay
            )
        );
    }

    @Test
    void drawingIsCompositedAfterTheVisibleMapLayers() {
        final int real = 0xFF204060;
        final int loadState = 0x7048B85E;
        final int drawing = 0x80E74C3C;
        final int visibleMap = Argb.over(loadState, real);

        assertEquals(
            Argb.over(drawing, visibleMap),
            MapExportCompositor.compose(
                BACKGROUND, 0x00000000, real, 0xFFFFFFFF, loadState, drawing
            )
        );
    }
}
