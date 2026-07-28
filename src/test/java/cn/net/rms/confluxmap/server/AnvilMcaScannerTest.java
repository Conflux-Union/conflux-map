package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.core.util.ChunkRegionSlice;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.DeflaterOutputStream;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

class AnvilMcaScannerTest {
    @Test
    void croppedScanDoesNotParseAnAdjacentChunk(@TempDir final Path tempDir) throws IOException {
        final Path file = tempDir.resolve("r.0.0.mca");
        writeRegion(file, generatedStoneChunk(), 0, 0, 1, 0);
        final long mtime = Files.getLastModifiedTime(file).toMillis();

        final SummaryCodec.SampledRegion region = new AnvilMcaScanner(false).scanRegion(
            file, 0, 0, mtime, 4,
            new ChunkRegionSlice(0, 0, 0, 0, 0, 0),
            new ChunkSummarizer()
        );

        assertTrue(region.chunks()[0].generated());
        assertFalse(region.chunks()[1].generated());
    }

    @Test
    void scansAllFourSummaryRegionsFromOneAnvilOpen(@TempDir final Path tempDir) throws IOException {
        final Path file = tempDir.resolve("r.0.0.mca");
        writeRegion(file, generatedStoneChunk(), 0, 0, 31, 31);
        final long mtime = Files.getLastModifiedTime(file).toMillis();

        final AnvilMcaScanner.Scan scan = new AnvilMcaScanner().scan(
            file, 0, 0, mtime, 4, new ChunkSummarizer()
        );

        assertEquals(mtime, scan.sourceMcaMtimeMs());
        assertTrue(scan.region(0, 0).chunks()[0].generated());
        assertTrue(scan.region(1, 1).chunks()[255].generated());
        assertEquals(1, scan.region(0, 0).chunks()[0].columns().length);
        assertFalse(scan.region(1, 0).chunks()[0].generated());
    }

    @Test
    void nativeScanMatchesVanillaNbtSummary(@TempDir final Path tempDir) throws IOException {
        Assumptions.assumeTrue(NativeLib.initForTests(), "native scanner unavailable");
        final Path file = tempDir.resolve("r.0.0.mca");
        writeRegion(file, generatedStoneChunk(), 0, 0, 31, 31);
        final long mtime = Files.getLastModifiedTime(file).toMillis();

        final AnvilMcaScanner.Scan nativeScan = new AnvilMcaScanner(true).scan(
            file, 0, 0, mtime, 4, new ChunkSummarizer()
        );
        final AnvilMcaScanner.Scan vanillaScan = new AnvilMcaScanner(false).scan(
            file, 0, 0, mtime, 4, new ChunkSummarizer()
        );

        assertSameSummaries(vanillaScan, nativeScan);
    }

    private static void assertSameSummaries(
        final AnvilMcaScanner.Scan expected,
        final AnvilMcaScanner.Scan actual
    ) {
        assertNotNull(expected);
        assertNotNull(actual);
        for (int regionZ = 0; regionZ < 2; regionZ++) {
            for (int regionX = 0; regionX < 2; regionX++) {
                final SummaryCodec.SampledChunk[] expectedChunks = expected.region(regionX, regionZ).chunks();
                final SummaryCodec.SampledChunk[] actualChunks = actual.region(regionX, regionZ).chunks();
                for (int i = 0; i < expectedChunks.length; i++) {
                    assertEquals(expectedChunks[i].generated(), actualChunks[i].generated());
                    assertEquals(expectedChunks[i].revision(), actualChunks[i].revision());
                    assertArrayEquals(expectedChunks[i].columns(), actualChunks[i].columns());
                }
            }
        }
    }

    private static void writeRegion(
        final Path path,
        final NbtCompound chunk,
        final int... coordinates
    ) throws IOException {
        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(raw)) {
            NbtIo.write(chunk, output);
        }
        final ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
            deflater.write(raw.toByteArray());
        }
        final byte[] payload = compressed.toByteArray();
        final int recordLength = Integer.BYTES + 1 + payload.length;
        final int sectorsPerChunk = (recordLength + 4_095) / 4_096;
        final byte[] file = new byte[(2 + sectorsPerChunk * (coordinates.length / 2)) * 4_096];
        final ByteBuffer header = ByteBuffer.wrap(file);
        int sector = 2;
        for (int i = 0; i < coordinates.length; i += 2) {
            final int localX = coordinates[i];
            final int localZ = coordinates[i + 1];
            header.putInt((localZ * 32 + localX) * 4, (sector << 8) | sectorsPerChunk);
            final ByteBuffer record = ByteBuffer.wrap(file, sector * 4_096, sectorsPerChunk * 4_096);
            record.putInt(payload.length + 1);
            record.put((byte) 2);
            record.put(payload);
            sector += sectorsPerChunk;
        }
        Files.write(path, file);
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
