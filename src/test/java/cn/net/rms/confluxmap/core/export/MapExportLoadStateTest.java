package cn.net.rms.confluxmap.core.export;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.net.ChunkLoadBand;
import cn.net.rms.confluxmap.core.net.LoadStateDeltaS2C;
import cn.net.rms.confluxmap.core.util.Argb;
import java.util.List;
import org.junit.jupiter.api.Test;

final class MapExportLoadStateTest {
    @Test
    void followsNegativeChunkCoordinatesAndScreenOutlineThreshold() {
        final MapExportLoadState plane = new MapExportLoadState(List.of(
            new LoadStateDeltaS2C.Entry(-1, -1, 31, ChunkLoadBand.ENTITY_TICKING)
        ));
        final int fill = 0x7048B85E;
        final int outline = 0xA0101018;

        assertEquals(
            Argb.over(outline, fill),
            plane.overlayAt(-16, -16, MapExportResolution.ONE_BLOCK)
        );
        assertEquals(fill, plane.overlayAt(-15, -15, MapExportResolution.ONE_BLOCK));
        assertEquals(fill, plane.overlayAt(-16, -16, MapExportResolution.SIXTEEN_BLOCKS));
        assertEquals(Argb.TRANSPARENT, plane.overlayAt(0, 0, MapExportResolution.ONE_BLOCK));
    }

    @Test
    void unloadedEntriesRemainTransparent() {
        final MapExportLoadState plane = new MapExportLoadState(List.of(
            new LoadStateDeltaS2C.Entry(0, 0, 0, ChunkLoadBand.UNLOADED)
        ));

        assertEquals(Argb.TRANSPARENT, plane.overlayAt(0, 0, MapExportResolution.ONE_BLOCK));
    }
}
