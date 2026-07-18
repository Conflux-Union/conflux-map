package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummaryTileTest {
    @Test
    void lodTwoUsesAllCoveredRegionsAcrossNegativeCoordinates() {
        final List<SummaryCodec.Region> regions = regions(-4, -4, 4);
        final SummaryTile tile = new SummaryTile(2, -1, -1, regions);

        assertEquals(-1024L, tile.originBlockX());
        assertEquals(-1024L, tile.originBlockZ());
        assertEquals(1000, tile.pixel(0, 0).column().surfaceY());
        assertEquals(1100, tile.pixel(64, 0).column().surfaceY());
        assertEquals(1200, tile.pixel(128, 0).column().surfaceY());
        assertEquals(1300, tile.pixel(192, 0).column().surfaceY());
    }

    @Test
    void presenceUsesOutputCellsAtEveryLod() {
        final List<SummaryCodec.Region> regions = new ArrayList<>();
        regions.add(region(-4, -4, true, 1000));
        final SummaryTile tile = new SummaryTile(2, -1, -1, regions);
        final byte[] presence = tile.presence();

        for (int z = 0; z < 4; z++) {
            for (int x = 0; x < 4; x++) {
                final int bit = z * 16 + x;
                assertTrue((presence[bit >>> 3] & (1 << (bit & 7))) != 0, "missing cell " + x + "," + z);
            }
        }
        final int outside = 4 * 16;
        assertFalse((presence[outside >>> 3] & (1 << (outside & 7))) != 0);
        assertEquals(Proto.PATCH_PRESENCE_BYTES, presence.length);
    }

    private static List<SummaryCodec.Region> regions(final int baseX, final int baseZ, final int side) {
        final List<SummaryCodec.Region> result = new ArrayList<>();
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                result.add(region(baseX + x, baseZ + z, true, 1000 + x * 100 + z));
            }
        }
        return result;
    }

    private static SummaryCodec.Region region(final int rx, final int rz, final boolean generated, final int surfaceY) {
        final SummaryCodec.Column[] columns = new SummaryCodec.Column[SummaryCodec.COLUMNS];
        for (int i = 0; i < columns.length; i++) {
            columns[i] = new SummaryCodec.Column(1, surfaceY, 1, 255, 0);
        }
        final SummaryCodec.Chunk chunk = generated
            ? new SummaryCodec.Chunk(true, surfaceY, columns)
            : SummaryCodec.Chunk.empty();
        final SummaryCodec.Chunk[] chunks = new SummaryCodec.Chunk[SummaryCodec.CHUNKS];
        java.util.Arrays.fill(chunks, chunk);
        return new SummaryCodec.Region(rx, rz, 0L, chunks);
    }
}
