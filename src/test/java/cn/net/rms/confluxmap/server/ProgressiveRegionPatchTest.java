package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.net.Proto;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
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
                throw new IllegalArgumentException("patch body exceeds raw cap");
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
    void progressiveSnapshotsStayBodylessUntilTheFinalEncode(@TempDir final Path tempDir) {
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
                return new PatchBuilder().buildPrepared(summary, sinceRevision, baseline);
            }
        );

        patch.tick(ProgressiveRegionPatch.PUBLISH_CHUNK_INTERVAL, Long.MAX_VALUE, () -> 0L);
        final ProgressiveRegionPatch.Response partial = patch.response(Long.MIN_VALUE, 2L);

        assertEquals(Proto.PATCH_MODE_PARTIAL, partial.mode());
        assertArrayEquals(ProgressiveRegionPatch.emptyPatchBody(), partial.body());
        assertEquals(0, encodeAttempts.get(), "progress publication must not encode a growing snapshot");

        for (int i = 0; i < 20 && !patch.complete(); i++) {
            patch.tick(Integer.MAX_VALUE, Long.MAX_VALUE, () -> 0L);
        }
        assertTrue(patch.complete());
        final ProgressiveRegionPatch.Response complete = patch.response(Long.MIN_VALUE, 3L);
        assertNotEquals(Proto.PATCH_MODE_PARTIAL, complete.mode());
        assertEquals(1, encodeAttempts.get(), "the authoritative snapshot must encode once");
    }

    @Test
    void lodFourColdScanSummarizesOnlyTheVisibleColumn(
        @TempDir final Path tempDir
    ) {
        assertEquals(
            1,
            classifiedColumnsAfterOneColdChunk(tempDir, 4),
            "LOD 4 publishes one column per chunk and must not summarize the other 255"
        );
    }

    @Test
    void lodThreeColdScanSummarizesOnlyTheFourVisibleColumns(
        @TempDir final Path tempDir
    ) {
        assertEquals(
            4,
            classifiedColumnsAfterOneColdChunk(tempDir, 3),
            "LOD 3 publishes four columns per chunk and must not summarize the other 252"
        );
    }

    @Test
    void currentPartialCacheSuppliesSampledChunksBeforeNbtFallback(
        @TempDir final Path tempDir
    ) throws IOException {
        final Path regionFile = tempDir.resolve("region").resolve("r.0.0.mca");
        Files.createDirectories(regionFile.getParent());
        Files.write(regionFile, new byte[] {0});
        final long sourceMtime = Files.getLastModifiedTime(regionFile).toMillis();
        final ChunkSummarizer summarizer = new ChunkSummarizer();
        final SummaryDiskCache disk = new SummaryDiskCache(tempDir);
        disk.saveLiveChunk(
            "minecraft:overworld",
            0,
            0,
            sourceMtime,
            summarizer.summarize(generatedStoneChunk())
        );
        final AtomicInteger nbtReads = new AtomicInteger();
        final ProgressiveRegionPatch patch = new ProgressiveRegionPatch(
            "minecraft:overworld",
            tempDir,
            disk,
            new LiveChunkSummaryTracker(new ServerConfig(), summarizer, (dimension, x, z) -> { }),
            summarizer,
            new PatchBuilder(),
            Runnable::run,
            4,
            0,
            0,
            ignored -> PatchBuilder.PreparedBaseline.absoluteOnly(),
            ignored -> {
                nbtReads.incrementAndGet();
                return generatedStoneChunk();
            },
            1L
        );

        patch.tick(2, Long.MAX_VALUE, () -> 0L);

        assertEquals(1, nbtReads.get(), "only the missing cached chunk should read NBT");
    }

    @Test
    void missingRegionSkipsColdNbtReads(@TempDir final Path tempDir) {
        final AtomicInteger nbtReads = new AtomicInteger();
        final ChunkSummarizer summarizer = new ChunkSummarizer();
        final ProgressiveRegionPatch patch = new ProgressiveRegionPatch(
            "minecraft:overworld",
            tempDir,
            new SummaryDiskCache(tempDir),
            new LiveChunkSummaryTracker(new ServerConfig(), summarizer, (dimension, x, z) -> { }),
            summarizer,
            new PatchBuilder(),
            Runnable::run,
            3,
            0,
            0,
            ignored -> PatchBuilder.PreparedBaseline.absoluteOnly(),
            ignored -> {
                nbtReads.incrementAndGet();
                return generatedStoneChunk();
            },
            1L
        );

        patch.tick(1, Long.MAX_VALUE, () -> 0L);

        assertEquals(0, nbtReads.get(), "a missing region file cannot contain a cold chunk");
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

    private static int classifiedColumnsAfterOneColdChunk(final Path tempDir, final int lod) {
        final Path regionFile = tempDir.resolve("region").resolve("r.0.0.mca");
        try {
            Files.createDirectories(regionFile.getParent());
            Files.write(regionFile, new byte[] {0});
        } catch (final IOException e) {
            throw new AssertionError("could not create cold-scan region fixture", e);
        }
        final AtomicInteger classifiedColumns = new AtomicInteger();
        final ChunkSummarizer summarizer = new ChunkSummarizer(name -> {
            classifiedColumns.incrementAndGet();
            return 11;
        });
        final ProgressiveRegionPatch patch = new ProgressiveRegionPatch(
            "minecraft:overworld",
            tempDir,
            new SummaryDiskCache(tempDir),
            new LiveChunkSummaryTracker(new ServerConfig(), summarizer, (dimension, x, z) -> { }),
            summarizer,
            new PatchBuilder(),
            Runnable::run,
            lod,
            0,
            0,
            ignored -> PatchBuilder.PreparedBaseline.absoluteOnly(),
            ignored -> generatedStoneChunk(),
            1L
        );

        patch.tick(1, Long.MAX_VALUE, () -> 0L);
        return classifiedColumns.get();
    }

    private static NbtCompound generatedStoneChunk() {
        final NbtCompound level = new NbtCompound();
        level.putString("Status", "full");
        level.putLong("LastUpdate", 1L);

        final long[] heights = new long[(256 + 6) / 7];
        long packedOnes = 0L;
        for (int i = 0; i < 7; i++) {
            packedOnes |= 1L << (i * 9);
        }
        Arrays.fill(heights, packedOnes);
        final NbtCompound heightmaps = new NbtCompound();
        heightmaps.putLongArray("MOTION_BLOCKING", heights);
        level.put("Heightmaps", heightmaps);
        level.putIntArray("Biomes", new int[1_024]);

        final NbtCompound stone = new NbtCompound();
        stone.putString("Name", "minecraft:stone");
        final NbtList palette = new NbtList();
        palette.add(stone);
        final NbtCompound section = new NbtCompound();
        section.putByte("Y", (byte) 0);
        section.put("Palette", palette);
        final NbtList sections = new NbtList();
        sections.add(section);
        level.put("Sections", sections);

        final NbtCompound root = new NbtCompound();
        root.put("Level", level);
        return root;
    }
}
