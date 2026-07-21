package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.predict.BaselineGrid;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchBuilderTest {
    @Test
    void lodTwoPatchSamplesTheMatchingRegionInsteadOfRepeatingOneEdge() throws Exception {
        final List<SummaryCodec.Region> regions = new ArrayList<>();
        for (int x = 0; x < 4; x++) {
            regions.add(region(-4 + x, -4, 1000 + x * 100));
        }
        final SummaryTile summary = new SummaryTile(2, -1, -1, regions);
        final BaselineGrid baseline = new BaselineGrid();
        Arrays.fill(baseline.biomeId, 1);
        Arrays.fill(baseline.terrainY, 0);

        final PatchBuilder.Result result = new PatchBuilder().build(summary, 0L, baseline, true);
        assertEquals(Proto.PATCH_MODE_ABSOLUTE, result.mode());
        final PatchCodec.Patch patch = PatchCodec.decode(result.body());
        assertEquals(1000, patch.sampleAt(0).surfaceY());
        assertEquals(1100, patch.sampleAt(64).surfaceY());
        assertEquals(1200, patch.sampleAt(128).surfaceY());
        assertEquals(1300, patch.sampleAt(192).surfaceY());
    }

    @Test
    void unknownColumnsAreNotPublishedAsTransparentCorrections() {
        final SummaryCodec.Column[] columns = new SummaryCodec.Column[SummaryCodec.COLUMNS];
        Arrays.fill(columns, new SummaryCodec.Column(1, 0, SurfaceKind.UNKNOWN.ordinal(), Proto.MAP_COLOR_NONE, 0));
        final SummaryCodec.Chunk generated = new SummaryCodec.Chunk(true, 10L, columns);
        final SummaryCodec.Chunk[] chunks = new SummaryCodec.Chunk[SummaryCodec.CHUNKS];
        Arrays.fill(chunks, SummaryCodec.Chunk.empty());
        chunks[0] = generated;
        final SummaryTile summary = new SummaryTile(0, 0, 0, List.of(new SummaryCodec.Region(0, 0, 0L, chunks)));
        final BaselineGrid baseline = new BaselineGrid();
        Arrays.fill(baseline.biomeId, 1);
        Arrays.fill(baseline.terrainY, 70);

        final PatchBuilder.Result result = new PatchBuilder().build(summary, 0L, baseline, true);

        assertEquals(Proto.PATCH_MODE_UNCHANGED, result.mode());
        assertEquals(0, result.recordCount());
    }

    @Test
    void changedResidualChunkRemovesCorrectionThatReturnedToBaseline() throws Exception {
        final SummaryCodec.Chunk[] chunks = new SummaryCodec.Chunk[SummaryCodec.CHUNKS];
        Arrays.fill(chunks, SummaryCodec.Chunk.empty());
        chunks[0] = chunk(20L, 70, 1);
        chunks[1] = chunk(10L, 80, 11);
        final SummaryTile summary = new SummaryTile(
            0,
            0,
            0,
            List.of(new SummaryCodec.Region(0, 0, 0L, chunks))
        );
        final BaselineGrid baseline = new BaselineGrid();
        Arrays.fill(baseline.biomeId, 1);
        Arrays.fill(baseline.terrainY, 70);

        final PatchBuilder.Result result = new PatchBuilder().build(summary, 15L, baseline, false);

        assertEquals(Proto.PATCH_MODE_RESIDUAL, result.mode());
        final PatchCodec.Patch patch = PatchCodec.decode(result.body());
        assertEquals(256, patch.size());
        assertTrue(PatchCodec.isRemoval(patch.sampleAt(0)));
        assertNull(patch.sampleAt(16));
    }

    private static SummaryCodec.Region region(final int rx, final int rz, final int surfaceY) {
        final SummaryCodec.Chunk[] chunks = new SummaryCodec.Chunk[SummaryCodec.CHUNKS];
        Arrays.fill(chunks, SummaryCodec.Chunk.empty());
        chunks[0] = chunk(surfaceY, surfaceY);
        return new SummaryCodec.Region(rx, rz, 0L, chunks);
    }

    private static SummaryCodec.Chunk chunk(final long revision, final int surfaceY) {
        return chunk(revision, surfaceY, Proto.MAP_COLOR_NONE);
    }

    private static SummaryCodec.Chunk chunk(final long revision, final int surfaceY, final int mapColorId) {
        final SummaryCodec.Column[] columns = new SummaryCodec.Column[SummaryCodec.COLUMNS];
        Arrays.fill(columns, new SummaryCodec.Column(1, surfaceY, 1, mapColorId, 0));
        return new SummaryCodec.Chunk(true, revision, columns);
    }
}
