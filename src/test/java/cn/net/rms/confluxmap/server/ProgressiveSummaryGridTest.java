package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ProgressiveSummaryGridTest {
    @Test
    void lodFourMapsOneChunkCentreToOneOutputPixel() {
        final ProgressiveSummaryGrid grid = new ProgressiveSummaryGrid(4, 0, 0);
        grid.acceptChunk(5, 7, chunk(41L, 80));

        assertNull(grid.pixel(4, 7));
        assertEquals(80, grid.pixel(5, 7).column().surfaceY());
        assertEquals(41L, grid.pixel(5, 7).revision());
        assertTrue(grid.pixel(5, 7).generated());
        assertEquals(41L, grid.revision());
    }

    @Test
    void lodThreeMapsFourCentredSamplesPerChunk() {
        final ProgressiveSummaryGrid grid = new ProgressiveSummaryGrid(3, -1, -1);
        grid.acceptChunk(-128, -128, chunk(10L, 70));

        assertEquals(70, grid.pixel(0, 0).column().surfaceY());
        assertEquals(70, grid.pixel(1, 0).column().surfaceY());
        assertEquals(70, grid.pixel(0, 1).column().surfaceY());
        assertEquals(70, grid.pixel(1, 1).column().surfaceY());
        assertNull(grid.pixel(2, 0));
    }

    @Test
    void incompleteSnapshotsKeepTheCorrectionWatermarkAtZero() {
        final ProgressiveSummaryGrid grid = new ProgressiveSummaryGrid(4, 0, 0);
        grid.acceptChunk(0, 0, chunk(99L, 90));

        final SummaryView incomplete = grid.snapshot(false);
        final SummaryView complete = grid.snapshot(true);

        assertEquals(0L, incomplete.revision());
        assertEquals(99L, complete.revision());
        assertTrue(bit(incomplete.presence(), 0));
        assertEquals(Proto.PATCH_PRESENCE_BYTES, incomplete.presence().length);
        assertFalse(bit(incomplete.presence(), 1));
    }

    private static boolean bit(final byte[] values, final int index) {
        return (values[index >>> 3] & (1 << (index & 7))) != 0;
    }

    private static SummaryCodec.Chunk chunk(final long revision, final int surfaceY) {
        final SummaryCodec.Column[] columns = new SummaryCodec.Column[SummaryCodec.COLUMNS];
        Arrays.fill(columns, new SummaryCodec.Column(1, surfaceY, 1, 1, 0));
        return new SummaryCodec.Chunk(true, revision, columns);
    }
}
