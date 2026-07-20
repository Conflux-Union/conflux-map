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
}
