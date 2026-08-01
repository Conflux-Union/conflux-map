package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.ChunkPatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.core.predict.FlatBaseline;
import cn.net.rms.confluxmap.core.util.ChunkRegionSlice;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class RegionPatchBuilderTest {
    @Test
    void flatResidualUsesOneConstantInsteadOfMaterializingATileGrid() throws Exception {
        final SummaryCodec.SampledChunk[] chunks = emptyChunks(16);
        chunks[0] = generatedChunk(16, 70);
        final SummaryCodec.SampledRegion region = new SummaryCodec.SampledRegion(
            0, 0, 1L, 16, chunks
        );
        final PatchBuilder.PreparedBaseline prepared = PatchBuilder.PreparedBaseline.uniform(
            new FlatBaseline(1, 70, SurfaceKind.LAND.ordinal(), 1, 0), false
        );

        final RegionPatchBuilder.Result result = new RegionPatchBuilder().build(
            4, new ChunkRegionSlice(0, 0, 0, 0, 0, 0), region,
            Long.MIN_VALUE, prepared
        );
        final ChunkPatchCodec.Patch patch = ChunkPatchCodec.decode(result.body());

        assertNull(prepared.baseline());
        assertNull(prepared.derived());
        assertEquals(Proto.PATCH_MODE_RESIDUAL, result.mode());
        assertEquals(0, result.recordCount());
        assertTrue(patch.evaluatedAt(0));
        assertNull(patch.sampleAt(0));
    }

    @Test
    void lodFourEdgeSlicePublishesOnlyItsOneVisibleChunk() throws Exception {
        final SummaryCodec.SampledChunk[] chunks = emptyChunks(16);
        chunks[15] = generatedChunk(16, 91);
        chunks[14] = generatedChunk(16, 77);
        final SummaryCodec.SampledRegion region = new SummaryCodec.SampledRegion(
            0, 0, 1L, 16, chunks
        );
        final ChunkRegionSlice slice = new ChunkRegionSlice(0, 0, 15, 0, 15, 0);

        final RegionPatchBuilder.Result result = new RegionPatchBuilder().build(
            4, slice, region, Long.MIN_VALUE, PatchBuilder.PreparedBaseline.absoluteOnly()
        );
        final ChunkPatchCodec.Patch patch = ChunkPatchCodec.decode(result.body());

        assertEquals(Proto.PATCH_MODE_ABSOLUTE, result.mode());
        assertEquals(1, patch.chunkWidth());
        assertEquals(1, patch.chunkHeight());
        assertEquals(1, patch.pixelCount());
        assertTrue(patch.generatedAt(0));
        assertEquals(91, patch.sampleAt(0).surfaceY());

        final RegionPatchBuilder.Result unchanged = new RegionPatchBuilder().build(
            4, slice, region, result.revision(), PatchBuilder.PreparedBaseline.absoluteOnly()
        );
        assertEquals(Proto.PATCH_MODE_UNCHANGED, unchanged.mode());
        assertEquals(0, unchanged.body().length);
    }

    private static SummaryCodec.SampledChunk[] emptyChunks(final int stride) {
        final SummaryCodec.SampledChunk[] chunks = new SummaryCodec.SampledChunk[SummaryCodec.CHUNKS];
        Arrays.fill(chunks, SummaryCodec.SampledChunk.empty(stride));
        return chunks;
    }

    private static SummaryCodec.SampledChunk generatedChunk(final int stride, final int surfaceY) {
        final int side = 16 / stride;
        final SummaryCodec.Column[] columns = new SummaryCodec.Column[side * side];
        Arrays.fill(columns, new SummaryCodec.Column(
            1, surfaceY, SurfaceKind.LAND.ordinal(), 1, 0, 255
        ));
        return new SummaryCodec.SampledChunk(true, surfaceY, stride, columns);
    }
}
