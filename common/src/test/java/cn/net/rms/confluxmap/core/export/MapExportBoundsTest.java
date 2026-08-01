package cn.net.rms.confluxmap.core.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class MapExportBoundsTest {
    @Test
    void normalizesCornerOrderAndIncludesBothBlocks() {
        final MapExportBounds bounds = MapExportBounds.between(12, 8, -3, -7);

        assertEquals(-3, bounds.minX());
        assertEquals(-7, bounds.minZ());
        assertEquals(12, bounds.maxX());
        assertEquals(8, bounds.maxZ());
        assertEquals(16L, bounds.blockWidth());
        assertEquals(16L, bounds.blockHeight());
    }

    @Test
    void derivesOutputSizeAtEachSupportedResolution() {
        final MapExportBounds bounds = MapExportBounds.between(-5, -3, 5, 6);

        assertEquals(11, bounds.pixelWidth(MapExportResolution.ONE_BLOCK));
        assertEquals(6, bounds.pixelWidth(MapExportResolution.TWO_BLOCKS));
        assertEquals(3, bounds.pixelWidth(MapExportResolution.FOUR_BLOCKS));
        assertEquals(10, bounds.pixelHeight(MapExportResolution.ONE_BLOCK));
        assertEquals(5, bounds.pixelHeight(MapExportResolution.TWO_BLOCKS));
        assertEquals(0, MapExportResolution.ONE_BLOCK.lod());
    }

    @Test
    void rejectsAnEdgeLongerThanPngCanRepresent() {
        final MapExportBounds bounds = MapExportBounds.between(
            Integer.MIN_VALUE, 0, Integer.MAX_VALUE, 0
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> bounds.pixelWidth(MapExportResolution.ONE_BLOCK)
        );
    }
}
