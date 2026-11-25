package cn.net.rms.confluxmap.core.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Array order and the {@code surfaceY} override of {@link ChunkSnapshotArrays}. */
class ChunkSnapshotArraysTest {

    @Test
    void toSnapshotCarriesEveryArray() {
        final ChunkSnapshotArrays arrays = new ChunkSnapshotArrays();
        arrays.surfaceY[5] = 1001;
        arrays.biomeId[5] = "minecraft:plains";
        arrays.fluidDepth[5] = 3;
        arrays.baseArgb[5] = 0xFF000001;
        arrays.xaeroBaseArgb[5] = 0xFF000002;
        arrays.tintArgb[5] = 0xFF000003;
        arrays.overlayArgb[5] = 0xFF000004;
        arrays.xaeroOverlayArgb[5] = 0xFF000005;
        arrays.kind[5] = 6;
        arrays.light[5] = 7;

        final ChunkSnapshot snapshot = arrays.toSnapshot(10, 20, 42L, 77L);

        assertEquals(10, snapshot.chunkX);
        assertEquals(20, snapshot.chunkZ);
        assertEquals(42L, snapshot.sessionToken);
        assertEquals(77L, snapshot.sourceRevision);
        assertEquals(1001, snapshot.surfaceY[5]);
        assertEquals("minecraft:plains", snapshot.biomeId[5]);
        assertEquals(3, snapshot.fluidDepth[5]);
        assertEquals(0xFF000001, snapshot.baseArgb[5]);
        assertEquals(0xFF000002, snapshot.xaeroBaseArgb[5]);
        assertEquals(0xFF000003, snapshot.tintArgb[5]);
        assertEquals(0xFF000004, snapshot.overlayArgb[5]);
        assertEquals(0xFF000005, snapshot.xaeroOverlayArgb[5]);
        assertEquals(6, snapshot.kind[5]);
        assertEquals(7, snapshot.light[5]);
    }

    @Test
    void surfaceYOverrideReplacesTheBufferedOne() {
        final ChunkSnapshotArrays arrays = new ChunkSnapshotArrays();
        arrays.surfaceY[5] = 42;
        final short[] override = new short[ChunkSnapshot.COLUMNS];
        java.util.Arrays.fill(override, (short) 7);

        final ChunkSnapshot snapshot = arrays.toSnapshot(0, 0, 1L, 2L, override);

        assertArrayEquals(override, snapshot.surfaceY);
        assertEquals(7, snapshot.surfaceY[200]);
    }
}
