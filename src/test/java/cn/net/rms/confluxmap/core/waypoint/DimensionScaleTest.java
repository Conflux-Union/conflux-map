package cn.net.rms.confluxmap.core.waypoint;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import org.junit.jupiter.api.Test;

final class DimensionScaleTest {
    @Test
    void waypointVisibilityRequiresAnExactDimensionMatch() {
        assertTrue(DimensionScale.isVisibleFrom(DimensionId.NETHER, DimensionId.NETHER));
        assertFalse(DimensionScale.isVisibleFrom(DimensionId.OVERWORLD, DimensionId.NETHER));
        assertFalse(DimensionScale.isVisibleFrom(DimensionId.NETHER, DimensionId.OVERWORLD));
        assertFalse(DimensionScale.isVisibleFrom(DimensionId.END, DimensionId.OVERWORLD));
    }
}
