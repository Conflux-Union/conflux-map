package cn.net.rms.confluxmap.core.loadstate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChunkLoadOverlayStyleTest {
    @Test
    void zoomAndDetailModeChooseOnlyLegibleDecorations() {
        assertFalse(ChunkLoadOverlayStyle.forChunkWidth(3.9, ChunkLoadDetailMode.EXACT).drawOutline());
        assertTrue(ChunkLoadOverlayStyle.forChunkWidth(4.0, ChunkLoadDetailMode.BANDS).drawOutline());
        assertFalse(ChunkLoadOverlayStyle.forChunkWidth(23.9, ChunkLoadDetailMode.EXACT).drawLevelLabel());
        assertTrue(ChunkLoadOverlayStyle.forChunkWidth(24.0, ChunkLoadDetailMode.EXACT).drawLevelLabel());
        assertFalse(ChunkLoadOverlayStyle.forChunkWidth(100.0, ChunkLoadDetailMode.BANDS).drawLevelLabel());
    }
}
