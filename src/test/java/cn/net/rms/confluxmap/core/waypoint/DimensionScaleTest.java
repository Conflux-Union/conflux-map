package cn.net.rms.confluxmap.core.waypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import org.junit.jupiter.api.Test;

final class DimensionScaleTest {
    @Test
    void portalLinkedDimensionsAreVisibleFromEachOtherAndTheEndIsConfined() {
        assertTrue(DimensionScale.isVisibleFrom(DimensionId.NETHER, DimensionId.NETHER));
        assertTrue(DimensionScale.isVisibleFrom(DimensionId.OVERWORLD, DimensionId.NETHER));
        assertTrue(DimensionScale.isVisibleFrom(DimensionId.NETHER, DimensionId.OVERWORLD));
        assertFalse(DimensionScale.isVisibleFrom(DimensionId.END, DimensionId.OVERWORLD));
        assertFalse(DimensionScale.isVisibleFrom(DimensionId.OVERWORLD, DimensionId.END));
        assertFalse(DimensionScale.isVisibleFrom(DimensionId.END, DimensionId.NETHER));
        assertTrue(DimensionScale.isVisibleFrom(DimensionId.END, DimensionId.END));
    }

    @Test
    void convertsHorizontalCoordinatesWithTheEightToOnePortalRatio() {
        assertEquals(100.0, DimensionScale.convertHorizontal(800.0, DimensionId.OVERWORLD, DimensionId.NETHER));
        assertEquals(800.0, DimensionScale.convertHorizontal(100.0, DimensionId.NETHER, DimensionId.OVERWORLD));
        assertEquals(-16.0, DimensionScale.convertHorizontal(-128.0, DimensionId.OVERWORLD, DimensionId.NETHER));
        assertEquals(42.0, DimensionScale.convertHorizontal(42.0, DimensionId.OVERWORLD, DimensionId.OVERWORLD));
        assertEquals(42.0, DimensionScale.convertHorizontal(42.0, DimensionId.OVERWORLD, DimensionId.END));
    }

    @Test
    void scaleOfDeclaresTheVanillaCoordinateScalePerDimension() {
        assertEquals(1.0, DimensionScale.scaleOf(DimensionId.OVERWORLD));
        assertEquals(8.0, DimensionScale.scaleOf(DimensionId.NETHER));
        assertEquals(1.0, DimensionScale.scaleOf(DimensionId.END));
    }
}
