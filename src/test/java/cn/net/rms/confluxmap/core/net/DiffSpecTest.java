package cn.net.rms.confluxmap.core.net;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import org.junit.jupiter.api.Test;

class DiffSpecTest {
    @Test
    void ordinaryOceanMapColorDoesNotBecomeACorrection() {
        final DiffSpec.Sample baseline = new DiffSpec.Sample(
            0, 62, SurfaceKind.WATER.ordinal(), Proto.MAP_COLOR_NONE, 12
        );
        final DiffSpec.Sample actual = new DiffSpec.Sample(
            0, 62, SurfaceKind.WATER.ordinal(), 12, 12
        );

        assertFalse(DiffSpec.differs(baseline, actual));
    }

    @Test
    void naturalFoliageDoesNotBecomeAChunkScopedCorrection() {
        final DiffSpec.Sample baseline = new DiffSpec.Sample(
            7, 64, SurfaceKind.LAND.ordinal(), Proto.MAP_COLOR_NONE, 0
        );
        final DiffSpec.Sample actual = new DiffSpec.Sample(
            7, 68, SurfaceKind.FOLIAGE.ordinal(), 7, 0
        );

        assertFalse(DiffSpec.differs(baseline, actual));
    }

    @Test
    void stoneStillCorrectsAFalsePredictedCanopy() {
        final DiffSpec.Sample baseline = new DiffSpec.Sample(
            35, 73, SurfaceKind.FOLIAGE.ordinal(), Proto.MAP_COLOR_NONE, 0
        );
        final DiffSpec.Sample actual = new DiffSpec.Sample(
            35, 79, SurfaceKind.LAND.ordinal(), 11, 0
        );

        assertTrue(DiffSpec.differs(baseline, actual));
    }

    @Test
    void baselineDeclaredMapColorAcceptsAnExactMatch() {
        // A stone-topped superflat baseline: observed untouched stone must NOT be a diff, even
        // though stone is deliberately outside the per-biome tolerant color set.
        final DiffSpec.Sample baseline = new DiffSpec.Sample(1, 3, SurfaceKind.LAND.ordinal(), 11, 0);
        final DiffSpec.Sample observed = new DiffSpec.Sample(1, 3, SurfaceKind.LAND.ordinal(), 11, 0);
        assertFalse(DiffSpec.differs(baseline, observed));
    }

    @Test
    void baselineDeclaredMapColorStillFlagsForeignBlocks() {
        final DiffSpec.Sample baseline = new DiffSpec.Sample(1, 3, SurfaceKind.LAND.ordinal(), 11, 0);
        final DiffSpec.Sample observed = new DiffSpec.Sample(1, 3, SurfaceKind.LAND.ordinal(), 29, 0);
        assertTrue(DiffSpec.differs(baseline, observed), "a player build on the flat surface must correct");
    }

    @Test
    void noneDeclaringBaselineKeepsTheTolerantSetBehavior() {
        final DiffSpec.Sample baseline = new DiffSpec.Sample(1, 3, SurfaceKind.LAND.ordinal(), Proto.MAP_COLOR_NONE, 0);
        final DiffSpec.Sample grass = new DiffSpec.Sample(1, 3, SurfaceKind.LAND.ordinal(), 1, 0);
        final DiffSpec.Sample stone = new DiffSpec.Sample(1, 3, SurfaceKind.LAND.ordinal(), 11, 0);
        assertFalse(DiffSpec.differs(baseline, grass), "grass is in the plains tolerant set");
        assertTrue(DiffSpec.differs(baseline, stone), "stone must correct on a normal baseline");
    }
}
