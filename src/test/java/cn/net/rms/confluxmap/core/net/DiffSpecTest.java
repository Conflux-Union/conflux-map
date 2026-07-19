package cn.net.rms.confluxmap.core.net;

import static org.junit.jupiter.api.Assertions.assertFalse;

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
}
