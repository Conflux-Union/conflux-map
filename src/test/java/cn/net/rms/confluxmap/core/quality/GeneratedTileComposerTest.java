package cn.net.rms.confluxmap.core.quality;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.predict.PredictionPalette;
import org.junit.jupiter.api.Test;

class GeneratedTileComposerTest {
    @Test
    void flatGeneratedStoneUsesTheNormalMapColorWithoutSlopeOrHeightTint() {
        final GeneratedTileComposer.Grid grid = new GeneratedTileComposer.Grid(
            2,
            2,
            new short[] {80, 80, 80, 80},
            new byte[] {
                (byte) SurfaceKind.LAND.ordinal(),
                (byte) SurfaceKind.LAND.ordinal(),
                (byte) SurfaceKind.LAND.ordinal(),
                (byte) SurfaceKind.LAND.ordinal()
            },
            new byte[] {0, 0, 0, 0},
            new byte[] {11, 11, 11, 11},
            new int[] {1, 1, 1, 1}
        );

        final PredictionQualityEvaluator.TileData rendered = GeneratedTileComposer.compose(
            grid,
            PredictionPalette.defaults()
        );

        assertArrayEquals(
            new int[] {0xFF707070, 0xFF707070, 0xFF707070, 0xFF707070},
            rendered.pixels()
        );
    }
}
