package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorrectionTileTest {
    @Test
    void highLodPresenceUsesOutputCellsWithoutClippingAtTheSixteenthChunk() {
        final byte[] presence = new byte[Proto.PATCH_PRESENCE_BYTES];
        final int cell = 4;
        presence[cell >>> 3] |= (byte) (1 << (cell & 7));
        final CorrectionTile tile = new CorrectionTile();
        tile.applyPatch(1L, presence, new PatchCodec.Patch(java.util.List.of()));

        assertTrue(tile.hasGeneratedChunkForPixel(64, 2));
        assertFalse(tile.hasGeneratedChunkForPixel(0, 2));
    }
}
