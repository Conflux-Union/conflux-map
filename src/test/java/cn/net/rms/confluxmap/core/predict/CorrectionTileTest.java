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

    @Test
    void partialScanReplacesItsOverlayWithoutAdvancingCommittedRevision() {
        final byte[] committedPresence = new byte[Proto.PATCH_PRESENCE_BYTES];
        committedPresence[0] = 1;
        final PatchCodec.Sample committed = new PatchCodec.Sample(1, 1, 80, 1, 11, 0);
        final CorrectionTile tile = new CorrectionTile();
        tile.applyPatch(20L, committedPresence, new PatchCodec.Patch(java.util.List.of(committed)));

        final byte[] firstPresence = new byte[Proto.PATCH_PRESENCE_BYTES];
        firstPresence[0] = 2;
        final PatchCodec.Sample progress = new PatchCodec.Sample(2, 1, 95, 1, 11, 0);
        tile.applyPartial(firstPresence, new PatchCodec.Patch(java.util.List.of(
            PatchCodec.removal(1), progress
        )));

        assertEquals(20L, tile.revision(), "a partial scan must not become the sinceRevision watermark");
        assertNull(tile.sampleAt(1), "a partial removal temporarily suppresses the committed correction");
        assertEquals(progress, tile.sampleAt(2));
        assertTrue(tile.hasGeneratedChunk(0, 0), "committed presence remains visible");
        assertTrue(tile.hasGeneratedChunk(1, 0), "partial presence is overlaid");

        tile.applyPartial(new byte[Proto.PATCH_PRESENCE_BYTES], new PatchCodec.Patch(java.util.List.of()));
        assertEquals(committed, tile.sampleAt(1), "restarting a scan drops stale progressive state");
        assertNull(tile.sampleAt(2));
    }

    @Test
    void finalPatchClearsProgressAndCommitsTheNewWatermark() {
        final CorrectionTile tile = new CorrectionTile();
        final PatchCodec.Sample partial = new PatchCodec.Sample(7, 1, 90, 1, 11, 0);
        tile.applyPartial(new byte[Proto.PATCH_PRESENCE_BYTES], new PatchCodec.Patch(java.util.List.of(partial)));

        final PatchCodec.Sample committed = new PatchCodec.Sample(8, 1, 91, 1, 11, 0);
        tile.applyPatch(30L, new byte[Proto.PATCH_PRESENCE_BYTES], new PatchCodec.Patch(java.util.List.of(committed)));

        assertNull(tile.sampleAt(7));
        assertEquals(committed, tile.sampleAt(8));
        assertEquals(30L, tile.revision());
    }
}
