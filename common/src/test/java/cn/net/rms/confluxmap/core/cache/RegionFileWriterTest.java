package cn.net.rms.confluxmap.core.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The writer is called from the IO thread and (defensively) from other writer
 * threads against the same target file; concurrent writers must never collide
 * on one tmp path, as the losing move then fails and interleaved output can
 * corrupt the target.
 */
class RegionFileWriterTest {
    private static final Logger LOGGER = LogManager.getLogger("RegionFileWriterTest");

    private static final int WRITERS = 4;
    private static final int ROUNDS = 6;

    @TempDir
    Path tempDir;

    @Test
    void concurrentWritersToTheSameFileAllSucceed() throws Exception {
        final Path file = tempDir.resolve("r.0.0.cfr");
        final CyclicBarrier barrier = new CyclicBarrier(WRITERS);
        final List<Boolean> results = new CopyOnWriteArrayList<>();
        final List<Thread> threads = new ArrayList<>();
        for (int w = 0; w < WRITERS; w++) {
            final int writerId = w;
            final Thread thread = new Thread(() -> {
                try {
                    for (int round = 0; round < ROUNDS; round++) {
                        barrier.await();
                        results.add(RegionFileWriter.writeAtomic(file, data(writerId, round), 0, LOGGER));
                    }
                } catch (final InterruptedException | BrokenBarrierException e) {
                    results.add(false);
                }
            });
            thread.start();
            threads.add(thread);
        }
        for (final Thread thread : threads) {
            thread.join(10_000L);
        }

        assertEquals(WRITERS * ROUNDS, results.size(), "every writer finished every round");
        assertTrue(results.stream().allMatch(Boolean::booleanValue),
            "no writer may lose the tmp race: " + results);
        assertTrue(noTmpFilesLeft(tempDir), "failed writers must not leave tmp files behind");
        try (BufferedInputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            final RegionFileCodec.RegionData decoded = RegionFileCodec.decode(in, 0, 0, 0);
            assertEquals(0, decoded.rx());
        }
    }

    private static boolean noTmpFilesLeft(final Path dir) throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, "*tmp*")) {
            return !entries.iterator().hasNext();
        }
    }

    /** Minimal legal payload; this test targets the tmp/move path, not encoding volume. */
    private static RegionFileCodec.RegionData data(final int writerId, final int round) {
        final int[] chunkUpdateEpochSeconds = new int[RegionFileCodec.CHUNK_TABLE_ENTRIES];
        chunkUpdateEpochSeconds[0] = 1_700_000_000 + writerId * ROUNDS + round;
        return new RegionFileCodec.RegionData(
            0, 0, 1_700_000_123_456L,
            new byte[RegionFileCodec.CHUNK_TABLE_ENTRIES], chunkUpdateEpochSeconds,
            new long[RegionFileCodec.CHUNK_TABLE_ENTRIES],
            new short[RegionFileCodec.COLUMN_COUNT], new byte[RegionFileCodec.COLUMN_COUNT],
            new byte[RegionFileCodec.COLUMN_COUNT], new String[RegionFileCodec.COLUMN_COUNT],
            new int[RegionFileCodec.COLUMN_COUNT], new int[RegionFileCodec.COLUMN_COUNT],
            new int[RegionFileCodec.COLUMN_COUNT], new int[RegionFileCodec.COLUMN_COUNT],
            new int[RegionFileCodec.COLUMN_COUNT], new byte[RegionFileCodec.COLUMN_COUNT]
        );
    }
}
