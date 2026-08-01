package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.net.SummaryCodec;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SummaryDiskCacheTest {
    @Test
    void liveChunksMergeWithinOneRegionAndAChangedMcaInvalidatesOlderSlots(
        @TempDir final Path tempDir
    ) throws IOException {
        final SummaryDiskCache cache = new SummaryDiskCache(tempDir);
        cache.saveLiveChunk("minecraft:overworld", 35, -46, 123L, chunk(10L, 60));
        cache.saveLiveChunk("minecraft:overworld", 36, -46, 123L, chunk(11L, 61));

        SummaryCodec.Region region = cache.load("minecraft:overworld", 2, -3);
        assertEquals(-123L, region.sourceMcaMtimeMs());
        assertEquals(60, region.chunks()[2 * 16 + 3].columns()[0].surfaceY());
        assertEquals(61, region.chunks()[2 * 16 + 4].columns()[0].surfaceY());

        cache.saveLiveChunk("minecraft:overworld", 37, -46, 124L, chunk(12L, 62));

        region = cache.load("minecraft:overworld", 2, -3);
        assertEquals(-124L, region.sourceMcaMtimeMs());
        assertFalse(region.chunks()[2 * 16 + 3].generated());
        assertEquals(62, region.chunks()[2 * 16 + 5].columns()[0].surfaceY());
        assertNotNull(cache.loadCurrent("minecraft:overworld", 2, -3, 124L));
        assertNull(cache.loadCurrent("minecraft:overworld", 2, -3, 123L));
        final SummaryCodec.SampledRegion sampled = cache.loadCurrentSampled(
            "minecraft:overworld", 2, -3, 124L, 4
        );
        assertNotNull(sampled);
        assertEquals(1, sampled.chunks()[2 * 16 + 5].columns().length);
        assertEquals(62, sampled.chunks()[2 * 16 + 5].column(0, 0).surfaceY());
        assertNull(cache.loadCurrentSampled("minecraft:overworld", 2, -3, 123L, 4));
        assertTrue(cache.isStale("minecraft:overworld", 2, -3, 124L));
    }

    private static SummaryCodec.Chunk chunk(final long revision, final int surfaceY) {
        final SummaryCodec.Column[] columns = new SummaryCodec.Column[SummaryCodec.COLUMNS];
        Arrays.fill(columns, new SummaryCodec.Column(1, surfaceY, 1, 1, 0));
        return new SummaryCodec.Chunk(true, revision, columns);
    }
}
