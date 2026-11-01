package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.color.BiomeColorPalette;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.util.Argb;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PredictedBiomeComposerTest {
    @Test
    void paintsFlatBiomeColorsAndAppliesUsableServerBiomeCorrections() {
        final BaselineGrid grid = new BaselineGrid();
        final DerivedGrid derived = new DerivedGrid();
        Arrays.fill(grid.biomeId, 1);
        Arrays.fill(derived.kind, (byte) SurfaceKind.LAND.ordinal());
        derived.kind[BaselineGrid.index(2, 0)] = (byte) SurfaceKind.VOID.ordinal();

        final CorrectionTile corrections = new CorrectionTile();
        corrections.applyPatch(
            1L,
            new byte[Proto.PATCH_PRESENCE_BYTES],
            new PatchCodec.Patch(java.util.List.of(
                new PatchCodec.Sample(
                    0, 4, 90, SurfaceKind.LAND.ordinal(), Proto.MAP_COLOR_NONE, 0
                ),
                new PatchCodec.Sample(
                    1, 4, 90, SurfaceKind.UNKNOWN.ordinal(), Proto.MAP_COLOR_NONE, 0
                )
            ))
        );

        final int[] pixels = PredictedBiomeComposer.compose(
            derived, grid, corrections, PredictionViewMode.EVERYWHERE, 0
        );

        assertEquals(BiomeColorPalette.colorForCubiomes(4), pixels[0]);
        assertEquals(
            BiomeColorPalette.colorForCubiomes(1),
            pixels[1],
            "an unusable correction must not erase or recolor the deterministic baseline"
        );
        assertEquals(Argb.TRANSPARENT, pixels[2], "void prediction remains transparent");
        assertEquals(BiomeColorPalette.colorForCubiomes(1), pixels[3]);
    }

    /** Seam guard: the biome view must paint void exactly like the terrain composers do. */
    @Test
    void voidPaintsTheConfiguredColorWhileUnknownStaysTransparent() {
        final BaselineGrid grid = new BaselineGrid();
        final DerivedGrid derived = new DerivedGrid();
        Arrays.fill(grid.biomeId, 1);
        Arrays.fill(derived.kind, (byte) SurfaceKind.LAND.ordinal());
        derived.kind[BaselineGrid.index(2, 0)] = (byte) SurfaceKind.VOID.ordinal();
        derived.kind[BaselineGrid.index(3, 0)] = (byte) SurfaceKind.UNKNOWN.ordinal();

        final int[] pixels = PredictedBiomeComposer.compose(
            derived, grid, null, PredictionViewMode.EVERYWHERE, 0, derived, grid,
            ConfluxConfig.VoidColumnColor.DARK_INDIGO.argb()
        );

        assertEquals(ConfluxConfig.VoidColumnColor.DARK_INDIGO.argb(), pixels[2]);
        assertEquals(Argb.TRANSPARENT, pixels[3]);
    }
}
