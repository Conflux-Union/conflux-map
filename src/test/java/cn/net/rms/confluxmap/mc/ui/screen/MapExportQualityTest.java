package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.export.MapExportResolution;
import org.junit.jupiter.api.Test;

final class MapExportQualityTest {
    @Test
    void expressesBlocksPerPixelAsRelativeQuality() {
        assertEquals("1", MapExportQuality.fraction(MapExportResolution.ONE_BLOCK));
        assertEquals("1/2", MapExportQuality.fraction(MapExportResolution.TWO_BLOCKS));
        assertEquals("1/4", MapExportQuality.fraction(MapExportResolution.FOUR_BLOCKS));
        assertEquals("1/8", MapExportQuality.fraction(MapExportResolution.EIGHT_BLOCKS));
        assertEquals("1/16", MapExportQuality.fraction(MapExportResolution.SIXTEEN_BLOCKS));
    }
}
