package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void explicitRemovalDropsAnOlderCorrectionWithoutTouchingOtherPixels() {
        final byte[] presence = new byte[Proto.PATCH_PRESENCE_BYTES];
        final PatchCodec.Sample removed = new PatchCodec.Sample(1, 1, 80, 1, 11, 0);
        final PatchCodec.Sample retained = new PatchCodec.Sample(2, 1, 79, 1, 11, 0);
        final CorrectionTile tile = new CorrectionTile();
        tile.applyPatch(1L, presence, new PatchCodec.Patch(java.util.List.of(removed, retained)));

        final PatchCodec.Sample lowered = new PatchCodec.Sample(2, 1, 72, 1, 1, 0);
        tile.applyPatch(2L, presence, new PatchCodec.Patch(java.util.List.of(PatchCodec.removal(1), lowered)));

        assertNull(tile.sampleAt(1));
        assertEquals(lowered, tile.sampleAt(2));
    }
}
