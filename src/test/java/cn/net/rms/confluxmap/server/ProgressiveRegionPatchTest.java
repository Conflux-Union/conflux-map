package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.net.Proto;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProgressiveRegionPatchTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        //#if MC>=12100
        //$$ Assumptions.abort(
        //$$     "Yarn's named 1.21 test jar cannot bootstrap ChunkPos dependencies outside Fabric Loader"
        //$$ );
        //#else
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        //#endif
    }

    @Test
    void oversizedCompletedPatchFailsOnceInsteadOfPollingForever(
        @TempDir final Path tempDir
    ) {
        final ChunkSummarizer summarizer = new ChunkSummarizer(new RegistryMapColors());
        final AtomicInteger encodeAttempts = new AtomicInteger();
        final ProgressiveRegionPatch patch = new ProgressiveRegionPatch(
            "minecraft:overworld",
            tempDir,
            new SummaryDiskCache(tempDir),
            new LiveChunkSummaryTracker(new ServerConfig(), summarizer, (dimension, x, z) -> { }),
            summarizer,
            Runnable::run,
            3,
            0,
            0,
            ignored -> PatchBuilder.PreparedBaseline.absoluteOnly(),
            ignored -> null,
            1L,
            (summary, sinceRevision, baseline) -> {
                encodeAttempts.incrementAndGet();
                throw new IllegalArgumentException("patch body exceeds compressed cap");
            }
        );

        for (int i = 0; i < 10 && !patch.complete(); i++) {
            patch.tick(Integer.MAX_VALUE, Long.MAX_VALUE, () -> 0L);
        }
        assertTrue(patch.complete());

        ProgressiveRegionPatch.Response response = null;
        for (int request = 0; request < 5; request++) {
            response = patch.response(0L, request + 2L);
        }

        assertEquals(Proto.PATCH_MODE_UNAVAILABLE, response.mode());
        assertEquals(1, encodeAttempts.get(), "a terminal encode failure must not be retried per request");
    }

    @Test
    void completedPatchStopsPollingAndRestartsOnlyForACoveredRegionEvent(
        @TempDir final Path tempDir
    ) {
        final ChunkSummarizer summarizer = new ChunkSummarizer(new RegistryMapColors());
        final ProgressiveRegionPatch patch = new ProgressiveRegionPatch(
            "minecraft:overworld",
            tempDir,
            new SummaryDiskCache(tempDir),
            new LiveChunkSummaryTracker(new ServerConfig(), summarizer, (dimension, x, z) -> { }),
            summarizer,
            new PatchBuilder(),
            Runnable::run,
            0,
            2,
            -3,
            ignored -> PatchBuilder.PreparedBaseline.absoluteOnly(),
            ignored -> null,
            1L
        );

        patch.tick(2_048, Long.MAX_VALUE, () -> 0L);
        assertFalse(patch.complete(), "the scan must validate its source stamps before commit");
        patch.tick(2_048, Long.MAX_VALUE, () -> 0L);
        assertTrue(patch.complete());
        assertNotEquals(Proto.PATCH_MODE_PARTIAL, patch.response(0L, 2L).mode());

        for (int i = 0; i < 20; i++) {
            patch.tick(2_048, Long.MAX_VALUE, () -> 0L);
        }
        assertTrue(patch.complete(), "completed work must remain idle without a source event");
        assertFalse(patch.invalidateRegion(1, -3));
        assertTrue(patch.invalidateRegion(2, -3));
        assertFalse(patch.complete());
        assertTrue(patch.response(0L, 3L).mode() == Proto.PATCH_MODE_PARTIAL);
    }
}
