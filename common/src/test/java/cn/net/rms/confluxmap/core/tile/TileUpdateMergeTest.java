package cn.net.rms.confluxmap.core.tile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.net.rms.confluxmap.core.color.MapColorStyle;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class TileUpdateMergeTest {
    private static final TileKey KEY = new TileKey(
        WorldIdentity.singleplayer("full-upload-merge"), DimensionId.OVERWORLD, "surface!pred", 2, -1, 0
    );

    @Test
    void completeReplacementDiscardsOlderRectanglesAndRetainsNewLighting() {
        final TileUpdate older = TileUpdate.patch(KEY, new int[] {0xFF112233},
            new TileUpdate.Rect(7, 9, 1, 1),
            new TileUpdate.Relight(1f, 0f, new byte[] {15}, MapColorStyle.CONFLUX));
        final int[] pixels = new int[256 * 256];
        Arrays.fill(pixels, 0xFF556677);
        final byte[] light = new byte[pixels.length];
        Arrays.fill(light, (byte) 3);
        final TileUpdate.Relight relight = new TileUpdate.Relight(0.5f, 0.7f, light, MapColorStyle.XAERO);
        final TileUpdate newer = TileUpdate.fullTile(KEY, pixels, relight);

        final TileUpdate merged = TileUpdate.merge(older, newer);

        assertSame(newer, merged, "a complete update already owns every pixel and light value");
        assertEquals(newer.changed(), merged.changed());
        assertEquals(0xFF556677, merged.pixelAt(7, 9));
        assertEquals(0xFF556677, merged.pixelAt(255, 255));
        assertEquals(3, merged.lightLevelAt(7, 9));
        assertSame(relight, merged.relight());
    }

    @Test
    void fullPackedReplacementRetainsItsPayloadAndCanClearRelighting() {
        final TileUpdate older = TileUpdate.fullTile(KEY, new int[256 * 256],
            new TileUpdate.Relight(1f, new byte[256 * 256]));
        final int[] pixels = new int[256 * 256];
        pixels[pixels.length - 1] = 0xFFAABBCC;
        final TileUpdate.Rect bounds = new TileUpdate.Rect(0, 0, 256, 256);
        final TileUpdate newer = TileUpdate.patch(KEY, pixels, bounds, null);

        final TileUpdate merged = TileUpdate.merge(older, newer);

        assertSame(newer, merged);
        assertSame(bounds, merged.payloadBounds());
        assertNull(merged.relight());
        assertEquals(0xFFAABBCC, merged.pixelAt(255, 255));
    }

    @Test
    void fullReplacementStillRejectsAnUnrelatedTile() {
        final TileKey other = new TileKey(KEY.world(), KEY.dimension(), KEY.layerId(), KEY.lod(), 0, 0);
        assertThrows(IllegalArgumentException.class, () -> TileUpdate.merge(
            TileUpdate.fullTile(KEY, new int[256 * 256]),
            TileUpdate.fullTile(other, new int[256 * 256])));
    }
}
