package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.predict.BaselineGrid;
import cn.net.rms.confluxmap.core.predict.CorrectionTile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchBuilderTest {
    @Test
    void lodFourBuildsAFullCorrectionPatch() throws Exception {
        final List<SummaryCodec.Region> regions = new ArrayList<>();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                regions.add(region(x, z, 1000 + x + z * 16));
            }
        }
        final SummaryTile summary = new SummaryTile(4, 0, 0, regions);
        final BaselineGrid baseline = new BaselineGrid();
        Arrays.fill(baseline.biomeId, 1);
        Arrays.fill(baseline.terrainY, 0);

        final PatchBuilder.Result result = new PatchBuilder().build(summary, 0L, baseline, true);

        assertEquals(Proto.PATCH_MODE_ABSOLUTE, result.mode());
        final PatchCodec.Patch patch = PatchCodec.decode(result.body());
        assertEquals(1000, patch.sampleAt(0).surfaceY());
        assertEquals(1001, patch.sampleAt(16).surfaceY());
        assertEquals(1016, patch.sampleAt(16 * 256).surfaceY());
    }

    @Test
    void lodFourCarriesALargeContiguousBuiltFootprint() throws Exception {
        final ProgressiveSummaryGrid grid = new ProgressiveSummaryGrid(4, 0, 0);
        final SummaryCodec.Chunk built = chunk(100L, 120, 11);
        for (int chunkZ = 64; chunkZ < 192; chunkZ++) {
            for (int chunkX = 64; chunkX < 192; chunkX++) {
                grid.acceptChunk(chunkX, chunkZ, built);
            }
        }

        final PatchBuilder.Result result = new PatchBuilder().build(
            grid.snapshot(false), Long.MIN_VALUE, baselineAt(70), false
        );
        final PatchCodec.Patch patch = PatchCodec.decode(result.body());

        assertEquals(128 * 128, patch.size());
        assertEquals(120, patch.sampleAt(64 * 256 + 64).surfaceY());
        assertEquals(120, patch.sampleAt(191 * 256 + 191).surfaceY());
        assertNull(patch.sampleAt(63 * 256 + 63));
    }

    @Test
    void lodFourCarriesBothHalvesOfAStructureCrossingTileBoundary() throws Exception {
        final ProgressiveSummaryGrid west = new ProgressiveSummaryGrid(4, -1, 0);
        final ProgressiveSummaryGrid east = new ProgressiveSummaryGrid(4, 0, 0);
        final SummaryCodec.Chunk built = chunk(200L, 130, 11);
        for (int chunkZ = 0; chunkZ < 16; chunkZ++) {
            for (int chunkX = -8; chunkX < 8; chunkX++) {
                (chunkX < 0 ? west : east).acceptChunk(chunkX, chunkZ, built);
            }
        }

        final PatchBuilder builder = new PatchBuilder();
        final PatchCodec.Patch westPatch = PatchCodec.decode(
            builder.build(west.snapshot(false), Long.MIN_VALUE, baselineAt(70), false).body()
        );
        final PatchCodec.Patch eastPatch = PatchCodec.decode(
            builder.build(east.snapshot(false), Long.MIN_VALUE, baselineAt(70), false).body()
        );

        assertEquals(8 * 16, westPatch.size());
        assertEquals(8 * 16, eastPatch.size());
        assertEquals(130, westPatch.sampleAt(255).surfaceY());
        assertEquals(130, eastPatch.sampleAt(0).surfaceY());
    }

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

        assertEquals(Proto.PATCH_MODE_ABSOLUTE, result.mode());
        assertEquals(0, result.recordCount());
        try {
            final PatchCodec.Patch patch = PatchCodec.decode(result.body());
            assertTrue(!patch.evaluatedAt(0));
        } catch (final cn.net.rms.confluxmap.core.net.ProtoException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void newResidualSnapshotReplacesOldCorrectionsAndMarksBaselineEquivalentPixelsEvaluated() throws Exception {
        final SummaryCodec.Chunk[] chunks = new SummaryCodec.Chunk[SummaryCodec.CHUNKS];
        Arrays.fill(chunks, SummaryCodec.Chunk.empty());
        chunks[0] = chunk(20L, 70, Proto.MAP_COLOR_NONE);
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
        assertTrue(patch.evaluatedAt(0));
        assertNull(patch.sampleAt(0), "baseline-equivalent pixels need no residual sample");
        assertEquals(80, patch.sampleAt(16).surfaceY());

        final CorrectionTile correction = new CorrectionTile();
        correction.applyPatch(
            10L,
            result.presence(),
            new PatchCodec.Patch(List.of(new PatchCodec.Sample(0, 1, 90, 1, 11, 0)))
        );
        correction.applyPatch(result.revision(), result.presence(), patch);
        assertNull(correction.sampleAt(0), "snapshot replacement must remove stale residuals");
    }

    @Test
    void tileMaxRevisionCannotSuppressAChangedLowerRevisionChunk() throws Exception {
        final SummaryCodec.Chunk[] chunks = new SummaryCodec.Chunk[SummaryCodec.CHUNKS];
        Arrays.fill(chunks, SummaryCodec.Chunk.empty());
        chunks[0] = chunk(100L, 70, Proto.MAP_COLOR_NONE);
        chunks[1] = chunk(11L, 90, 11);
        final SummaryTile summary = new SummaryTile(
            0,
            0,
            0,
            List.of(new SummaryCodec.Region(0, 0, 0L, chunks))
        );

        final PatchBuilder.Result result = new PatchBuilder().build(
            summary, 100L, baselineAt(70), false
        );

        assertEquals(Proto.PATCH_MODE_RESIDUAL, result.mode());
        assertEquals(90, PatchCodec.decode(result.body()).sampleAt(16).surfaceY());
    }

    @Test
    void identicalAuthoritativeSnapshotReturnsUnchangedWithoutABody() {
        final SummaryCodec.Chunk[] chunks = new SummaryCodec.Chunk[SummaryCodec.CHUNKS];
        Arrays.fill(chunks, SummaryCodec.Chunk.empty());
        chunks[0] = chunk(100L, 70, Proto.MAP_COLOR_NONE);
        chunks[1] = chunk(11L, 90, 11);
        final SummaryTile summary = new SummaryTile(
            0,
            0,
            0,
            List.of(new SummaryCodec.Region(0, 0, 0L, chunks))
        );
        final PatchBuilder builder = new PatchBuilder();
        final PatchBuilder.Result first = builder.build(
            summary, Long.MIN_VALUE, baselineAt(70), false
        );

        final PatchBuilder.Result unchanged = builder.build(
            summary, first.revision(), baselineAt(70), false
        );

        assertEquals(Proto.PATCH_MODE_UNCHANGED, unchanged.mode());
        assertEquals(first.revision(), unchanged.revision());
        assertEquals(0, unchanged.body().length);
        assertEquals(0, unchanged.recordCount());
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

    private static BaselineGrid baselineAt(final int surfaceY) {
        final BaselineGrid baseline = new BaselineGrid();
        Arrays.fill(baseline.biomeId, 1);
        Arrays.fill(baseline.terrainY, surfaceY);
        return baseline;
    }
}
