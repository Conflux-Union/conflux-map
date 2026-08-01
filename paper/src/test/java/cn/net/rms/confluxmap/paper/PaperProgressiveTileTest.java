package cn.net.rms.confluxmap.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.server.PatchBuilder;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaperProgressiveTileTest {
    @TempDir
    Path temporary;

    @Test
    void coarseTileStaysPartialUntilEveryRegionIsScanned() {
        final PaperProgressiveTile tile = new PaperProgressiveTile(
            temporary,
            3,
            2,
            -1,
            (regionX, regionZ) -> emptyRegion(regionX, regionZ, 3),
            ignored -> PatchBuilder.PreparedBaseline.absoluteOnly(),
            new PatchBuilder(),
            Runnable::run,
            1L
        );

        assertEquals(
            Proto.PATCH_MODE_PARTIAL,
            tile.response(Long.MIN_VALUE, 1L).mode()
        );
        for (int i = 0; i < 200 && !tile.complete(); i++) {
            tile.tick();
        }

        assertTrue(tile.complete());
        assertEquals(
            Proto.PATCH_MODE_ABSOLUTE,
            tile.response(Long.MIN_VALUE, 2L).mode()
        );
    }

    private static SummaryCodec.SampledRegion emptyRegion(
        final int regionX,
        final int regionZ,
        final int lod
    ) {
        final int stride = 1 << lod;
        final SummaryCodec.SampledChunk[] chunks =
            new SummaryCodec.SampledChunk[SummaryCodec.CHUNKS];
        Arrays.fill(chunks, SummaryCodec.SampledChunk.empty(stride));
        return new SummaryCodec.SampledRegion(regionX, regionZ, 0L, stride, chunks);
    }
}
