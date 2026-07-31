package cn.net.rms.confluxmap.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class PaperSampledSummaryTileTest {
    @Test
    void mapsLodTwoPixelsAcrossAllFourByFourRegions() {
        final int lod = 2;
        final int stride = 1 << lod;
        final SummaryCodec.SampledRegion first = region(-4, 8, stride, 0, 0, 5);
        final SummaryCodec.SampledRegion last = region(-1, 11, stride, 15, 15, 9);
        final PaperSampledSummaryTile tile = new PaperSampledSummaryTile(
            lod, -1, 2, List.of(first, last)
        );

        assertEquals(5, tile.pixel(0, 0).column().biomeId());
        assertEquals(9, tile.pixel(255, 255).column().biomeId());
        assertTrue((tile.presence()[0] & 1) != 0);
        assertTrue((tile.presence()[31] & 0x80) != 0);
        assertFalse(tile.pixel(128, 128) != null && tile.pixel(128, 128).generated());
    }

    private static SummaryCodec.SampledRegion region(
        final int regionX,
        final int regionZ,
        final int stride,
        final int chunkX,
        final int chunkZ,
        final int biome
    ) {
        final SummaryCodec.SampledChunk[] chunks = new SummaryCodec.SampledChunk[256];
        Arrays.fill(chunks, SummaryCodec.SampledChunk.empty(stride));
        final int side = 16 / stride;
        final SummaryCodec.Column[] columns = new SummaryCodec.Column[side * side];
        Arrays.fill(columns, new SummaryCodec.Column(
            biome, 64, SurfaceKind.LAND.ordinal(), 1, 0
        ));
        chunks[chunkZ * 16 + chunkX] = new SummaryCodec.SampledChunk(
            true, 1L, stride, columns
        );
        return new SummaryCodec.SampledRegion(regionX, regionZ, 0L, stride, chunks);
    }
}
