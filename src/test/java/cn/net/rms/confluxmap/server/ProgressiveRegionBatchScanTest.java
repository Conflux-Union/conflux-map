package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProgressiveRegionBatchScanTest {
    @Test
    void lodFourColdScanUsesSixtyFourAnvilBatchesInsteadOfIndividualChunks(
        @TempDir final Path tempDir
    ) throws IOException {
        final Path regionDir = tempDir.resolve("region");
        Files.createDirectories(regionDir);
        for (int regionZ = 0; regionZ < 8; regionZ++) {
            for (int regionX = 0; regionX < 8; regionX++) {
                Files.write(regionDir.resolve("r." + regionX + "." + regionZ + ".mca"), new byte[8_192]);
            }
        }
        final AtomicInteger fallbackReads = new AtomicInteger();
        final ChunkSummarizer summarizer = new ChunkSummarizer();
        final ProgressiveRegionPatch patch = new ProgressiveRegionPatch(
            "minecraft:overworld",
            tempDir,
            new SummaryDiskCache(tempDir),
            new LiveChunkSummaryTracker(new ServerConfig(), summarizer, (dimension, x, z) -> { }),
            summarizer,
            new PatchBuilder(),
            Runnable::run,
            Runnable::run,
            4,
            0,
            0,
            ignored -> PatchBuilder.PreparedBaseline.absoluteOnly(),
            ignored -> {
                fallbackReads.incrementAndGet();
                return null;
            },
            1L
        );

        patch.tick(Integer.MAX_VALUE, Long.MAX_VALUE, () -> 0L);

        assertEquals(0, fallbackReads.get());
    }
}
