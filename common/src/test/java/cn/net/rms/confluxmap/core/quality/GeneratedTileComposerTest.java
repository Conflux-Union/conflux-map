package cn.net.rms.confluxmap.core.quality;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.predict.PredictionPalette;
import cn.net.rms.confluxmap.core.util.Argb;
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

    @Test
    void visibleSnowMapColorPromotesCoveredLandToSnowSemantics() {
        final GeneratedTileComposer.Grid grid = new GeneratedTileComposer.Grid(
            1,
            1,
            new short[] {70},
            new byte[] {(byte) SurfaceKind.LAND.ordinal()},
            new byte[] {0},
            new byte[] {8},
            new int[] {12}
        );

        final PredictionQualityEvaluator.TileData rendered = GeneratedTileComposer.compose(
            grid,
            PredictionPalette.defaults()
        );

        assertEquals(SurfaceKind.SNOW.ordinal(), rendered.kind()[0]);
    }

    /** Seam guard: the quality view must paint void exactly like the captured and predicted maps. */
    @Test
    void voidPaintsTheConfiguredColorWhileUnknownStaysTransparent() {
        final GeneratedTileComposer.Grid grid = new GeneratedTileComposer.Grid(
            2,
            1,
            new short[] {80, 80},
            new byte[] {(byte) SurfaceKind.VOID.ordinal(), (byte) SurfaceKind.UNKNOWN.ordinal()},
            new byte[] {0, 0},
            new byte[] {0, 0},
            new int[] {1, 1}
        );

        final PredictionQualityEvaluator.TileData rendered = GeneratedTileComposer.compose(
            grid,
            PredictionPalette.defaults(),
            ConfluxConfig.VoidColumnColor.DARK_INDIGO.argb()
        );

        assertEquals(ConfluxConfig.VoidColumnColor.DARK_INDIGO.argb(), rendered.pixels()[0]);
        assertEquals(Argb.TRANSPARENT, rendered.pixels()[1]);
    }
}
