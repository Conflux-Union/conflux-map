package cn.net.rms.confluxmap.nativepredict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class NativeChunkNbtScannerTest {
    @Test
    void selectivelyReadsLegacySurfaceColumn() throws IOException {
        Assumptions.assumeTrue(NativeLib.initForTests(), "native library unavailable");
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            NbtIo.write(generatedStoneChunk(), output);
        }

        final NativeChunkNbtScanner.Chunk chunk = NativeChunkNbtScanner.scan(bytes.toByteArray(), 4);

        assertNotNull(chunk);
        assertTrue(chunk.generated());
        assertEquals(1L, chunk.revision());
        assertEquals(1, chunk.samples().length);
        assertEquals(0, chunk.samples()[0].surfaceY());
        assertEquals(0, chunk.samples()[0].biomeId());
        assertEquals("minecraft:stone", chunk.samples()[0].surfaceBlock());
    }

    @Test
    void malformedNbtFailsWithoutEscapingNativeParser() {
        Assumptions.assumeTrue(NativeLib.initForTests(), "native library unavailable");
        final java.util.Random random = new java.util.Random(0xC0FFEE);
        for (int size = 0; size < 512; size++) {
            final byte[] bytes = new byte[size];
            random.nextBytes(bytes);
            NativeChunkNbtScanner.scan(bytes, 4);
        }
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
