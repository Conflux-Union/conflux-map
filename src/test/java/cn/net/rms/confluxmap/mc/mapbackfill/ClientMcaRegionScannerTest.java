package cn.net.rms.confluxmap.mc.mapbackfill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.server.AnvilMcaScanner;
import cn.net.rms.confluxmap.server.ChunkSummarizer;
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
import org.junit.jupiter.api.io.TempDir;

/** Save-root layout and region-to-MCA mapping for {@link ClientMcaRegionScanner}. */
class ClientMcaRegionScannerTest {
    @TempDir
    Path tempDir;

    @Test
    void mapsSummaryRegionsToVanillaDimensionDirectories() throws IOException {
        final Path overworldMca = writeMca(overworldRegionDir(tempDir).resolve("r.0.0.mca"), 0, 0);
        final Path netherMca = writeMca(netherRegionDir(tempDir).resolve("r.0.0.mca"), 0, 0);

        final ClientMcaRegionScanner scanner = scannerFor(tempDir);

        assertEquals(Files.getLastModifiedTime(overworldMca).toMillis(),
            scanner.mtimeMs(DimensionId.OVERWORLD, 0, 0));
        assertEquals(Files.getLastModifiedTime(netherMca).toMillis(),
            scanner.mtimeMs(DimensionId.NETHER, 0, 0));
        assertEquals(0L, scanner.mtimeMs(DimensionId.END, 0, 0), "missing file reports mtime 0");
    }

    @Test
    void mapsOneSummaryRegionToItsQuarterMca() throws IOException {
        // Summary region (3,1) covers chunks (48..63, 16..31) = MCA (1,0) local (16..31, 16..31);
        // its first chunk is MCA-local (16,16).
        final Path mca = writeMca(overworldRegionDir(tempDir).resolve("r.1.0.mca"), 16, 16);

        final ClientMcaRegionScanner scanner = scannerFor(tempDir);
        final SummaryCodec.SampledRegion region =
            scanner.scanRegion(DimensionId.OVERWORLD, 3, 1, Files.getLastModifiedTime(mca).toMillis());

        assertNotNull(region);
        assertEquals(1, region.sampleStride());
        assertEquals(Files.getLastModifiedTime(mca).toMillis(), region.sourceMcaMtimeMs());
        assertTrue(region.chunks()[0].generated(), "chunk (6,2) is the region's first chunk");
        assertFalse(region.chunks()[1].generated());
    }

    @Test
    void missingMcaFileYieldsNullScan() throws IOException {
        final ClientMcaRegionScanner scanner = scannerFor(tempDir);
        assertNull(scanner.scanRegion(DimensionId.OVERWORLD, 0, 0, 1L));
    }

    @Test
    void expectedMtimeIsCarriedThroughVerbatim() throws IOException {
        // The scanner stamps the scan with the caller's expectation; the backfill service
        // re-reads the file mtime afterwards and discards on drift.
        final Path mca = writeMca(overworldRegionDir(tempDir).resolve("r.0.0.mca"), 0, 0);
        final ClientMcaRegionScanner scanner = scannerFor(tempDir);
        final SummaryCodec.SampledRegion region =
            scanner.scanRegion(DimensionId.OVERWORLD, 0, 0, 123456789L);

        assertNotNull(region);
        assertEquals(123456789L, region.sourceMcaMtimeMs());
    }

    // Vanilla 26.1 moved every dimension under dimensions/<namespace>/<path>.
    private static Path overworldRegionDir(final Path root) {
        //#if MC>=260100
        //$$ return root.resolve("dimensions/minecraft/overworld/region");
        //#else
        return root.resolve("region");
        //#endif
    }

    private static Path netherRegionDir(final Path root) {
        //#if MC>=260100
        //$$ return root.resolve("dimensions/minecraft/the_nether/region");
        //#else
        return root.resolve("DIM-1").resolve("region");
        //#endif
    }

    private ClientMcaRegionScanner scannerFor(final Path root) {
        return new ClientMcaRegionScanner(
            null, new AnvilMcaScanner(false), new ChunkSummarizer(), root);
    }

    /** Same Anvil fixture layout as {@code AnvilMcaScannerTest}, at LOD0 density. */
    private static Path writeMca(final Path path, final int chunkX, final int chunkZ) throws IOException {
        Files.createDirectories(path.getParent());
        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(raw)) {
            NbtIo.write(generatedStoneChunk(), output);
        }
        final ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
            deflater.write(raw.toByteArray());
        }
        final byte[] payload = compressed.toByteArray();
        final int recordLength = Integer.BYTES + 1 + payload.length;
        final int sectorsPerChunk = (recordLength + 4_095) / 4_096;
        final byte[] file = new byte[(2 + sectorsPerChunk) * 4_096];
        final ByteBuffer header = ByteBuffer.wrap(file);
        final int localX = Math.floorMod(chunkX, 32);
        final int localZ = Math.floorMod(chunkZ, 32);
        header.putInt((localZ * 32 + localX) * 4, (2 << 8) | sectorsPerChunk);
        final ByteBuffer record = ByteBuffer.wrap(file, 2 * 4_096, sectorsPerChunk * 4_096);
        record.putInt(payload.length + 1);
        record.put((byte) 2);
        record.put(payload);
        Files.write(path, file);
        return path;
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
