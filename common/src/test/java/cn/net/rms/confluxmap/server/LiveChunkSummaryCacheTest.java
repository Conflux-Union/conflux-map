package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.core.util.ChunkRegionSlice;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class LiveChunkSummaryCacheTest {
    @Test
    void revisionAdvancesOnlyWhenTheLiveSurfaceChanges() {
        final LiveChunkSummaryCache cache = new LiveChunkSummaryCache();
        assertTrue(cache.put("minecraft:overworld", 1, 2, chunk(10L, 60)));

        assertFalse(cache.put("minecraft:overworld", 1, 2, chunk(20L, 60)));
        assertEquals(10L, cache.get("minecraft:overworld", 1, 2).revision());

        assertTrue(cache.put("minecraft:overworld", 1, 2, chunk(5L, 61)));
        assertEquals(11L, cache.get("minecraft:overworld", 1, 2).revision());
        assertEquals(61, cache.get("minecraft:overworld", 1, 2).columns()[0].surfaceY());
    }

    @Test
    void liveChunkOverridesOnlyItsSlotInTheDiskRegion() {
        final LiveChunkSummaryCache cache = new LiveChunkSummaryCache();
        final SummaryCodec.Region disk = region(2, -3, chunk(10L, 60));
        cache.put("minecraft:overworld", 35, -46, chunk(20L, 90));

        final SummaryCodec.Region merged = cache.overlay("minecraft:overworld", disk);

        assertEquals(90, merged.chunks()[2 * 16 + 3].columns()[0].surfaceY());
        assertEquals(20L, merged.chunks()[2 * 16 + 3].revision());
        assertEquals(60, merged.chunks()[0].columns()[0].surfaceY());
        assertEquals(10L, merged.chunks()[0].revision());
    }

    @Test
    void regionEpochChangesOnlyWhenReusableLiveDataChanges() {
        final LiveChunkSummaryCache cache = new LiveChunkSummaryCache();
        assertEquals(0L, cache.regionEpoch("minecraft:overworld", 2, -3));

        cache.put("minecraft:overworld", 35, -46, chunk(10L, 60));
        final long first = cache.regionEpoch("minecraft:overworld", 2, -3);
        cache.put("minecraft:overworld", 35, -46, chunk(20L, 60));
        assertEquals(first, cache.regionEpoch("minecraft:overworld", 2, -3));

        cache.put("minecraft:overworld", 35, -46, chunk(5L, 61));
        final long changed = cache.regionEpoch("minecraft:overworld", 2, -3);
        assertTrue(changed > first);

        cache.remove("minecraft:overworld", 35, -46);
        assertTrue(
            cache.regionEpoch("minecraft:overworld", 2, -3) > changed
        );
    }

    @Test
    void sampledPageOverlayDoesNotTouchLiveChunksOutsideItsCrop() {
        final LiveChunkSummaryCache cache = new LiveChunkSummaryCache();
        final SummaryCodec.SampledChunk diskChunk = SummaryCodec.sample(chunk(10L, 60), 16);
        final SummaryCodec.SampledChunk[] diskChunks = new SummaryCodec.SampledChunk[SummaryCodec.CHUNKS];
        Arrays.fill(diskChunks, diskChunk);
        final SummaryCodec.SampledRegion disk = new SummaryCodec.SampledRegion(
            2, -3, 123L, 16, diskChunks
        );
        cache.put("minecraft:overworld", 32, -48, chunk(20L, 90));
        cache.put("minecraft:overworld", 47, -33, chunk(30L, 110));

        final SummaryCodec.SampledRegion merged = cache.overlay(
            "minecraft:overworld", disk,
            new ChunkRegionSlice(2, -3, 0, 0, 0, 0)
        );

        assertEquals(90, merged.chunks()[0].column(0, 0).surfaceY());
        assertEquals(
            60, merged.chunks()[15 * 16 + 15].column(0, 0).surfaceY(),
            "a one-chunk page must not sample or replace an adjacent live chunk"
        );
    }

    private static SummaryCodec.Region region(
        final int regionX,
        final int regionZ,
        final SummaryCodec.Chunk chunk
    ) {
        final SummaryCodec.Chunk[] chunks = new SummaryCodec.Chunk[SummaryCodec.CHUNKS];
        Arrays.fill(chunks, chunk);
        return new SummaryCodec.Region(regionX, regionZ, 123L, chunks);
    }

    private static SummaryCodec.Chunk chunk(final long revision, final int surfaceY) {
        final SummaryCodec.Column[] columns = new SummaryCodec.Column[SummaryCodec.COLUMNS];
        Arrays.fill(columns, new SummaryCodec.Column(1, surfaceY, 1, 1, 0));
        return new SummaryCodec.Chunk(true, revision, columns);
    }
}
