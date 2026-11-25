package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.function.IntUnaryOperator;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import org.junit.jupiter.api.Test;

class NbtChunkColumnSourceTest {
    @Test
    void blockStateIndexOutOfRangeResolvesToAir() {
        final NbtList palette = new NbtList();
        palette.add(paletteEntry("minecraft:stone"));
        palette.add(paletteEntry("minecraft:air"));
        final NbtCompound section = modernSection(
            (byte) 0, palette, pack(4, 4096, index -> index == 0 ? 15 : 0), null, null, null);

        final NbtChunkColumnSource source = new NbtChunkColumnSource(chunk(sections(section)));

        assertEquals("minecraft:air", source.blockNameAt(0, 0, 0));
        assertEquals("minecraft:stone", source.blockNameAt(1, 0, 0));
    }

    @Test
    void blockLightNibbleIsReadFromBothHalves() {
        final NbtList palette = new NbtList();
        palette.add(paletteEntry("minecraft:air"));
        // Nibble halves are addressed by column index parity (y*256 + z*16 + x), so the even
        // and the odd x of one column share a byte: x 0 reads the low nibble, x 1 the high.
        // Both halves are filled so a leaked nibble from the neighbouring half would fail.
        final NbtCompound section = modernSection(
            (byte) 0, palette, new long[0], null, null, new byte[] {(byte) 0xAB});

        final NbtChunkColumnSource source = new NbtChunkColumnSource(chunk(sections(section)));

        assertEquals(0x0B, source.blockLightAbove(0, -1, 0));
        assertEquals(0x0A, source.blockLightAbove(1, -1, 0));
    }

    @Test
    void biomeIsResolvedFromTheNamespacedName() {
        final NbtList palette = new NbtList();
        palette.add(paletteEntry("minecraft:air"));
        final NbtList biomePalette = new NbtList();
        biomePalette.add(NbtString.of("minecraft:ocean"));
        biomePalette.add(NbtString.of("desert"));
        final NbtCompound section = modernSection(
            (byte) 0, palette, new long[0], biomePalette, pack(1, 64, index -> index == 1 ? 1 : 0), null);

        final NbtChunkColumnSource source = new NbtChunkColumnSource(chunk(sections(section)));

        assertEquals(0, source.biomeIdAt(0, 0, 0));
        assertEquals(2, source.biomeIdAt(4, 0, 0));
    }

    @Test
    void sectionYBelowTheLowestSectionIsAir() {
        final NbtList palette = new NbtList();
        palette.add(paletteEntry("minecraft:stone"));
        final NbtCompound section = modernSection((byte) 0, palette, new long[0], null, null, null);

        final NbtChunkColumnSource source = new NbtChunkColumnSource(chunk(sections(section)));

        assertEquals("minecraft:air", source.blockNameAt(0, -1, 0));
        assertEquals("minecraft:air", source.blockNameAt(0, -16, 0));
    }

    private static NbtCompound chunk(final NbtList sections) {
        final NbtCompound root = new NbtCompound();
        root.putString("Status", "minecraft:full");
        root.putInt("yPos", 0);
        root.putLong("LastUpdate", 100L);
        final NbtCompound heightmaps = new NbtCompound();
        heightmaps.putLongArray("MOTION_BLOCKING", pack(9, 256, ignored -> 63));
        root.put("Heightmaps", heightmaps);
        root.put("sections", sections);
        return root;
    }

    private static NbtList sections(final NbtCompound... entries) {
        final NbtList sections = new NbtList();
        for (final NbtCompound entry : entries) {
            sections.add(entry);
        }
        return sections;
    }

    private static NbtCompound modernSection(
        final byte y,
        final NbtList blockPalette,
        final long[] states,
        final NbtList biomePalette,
        final long[] biomeData,
        final byte[] blockLight
    ) {
        final NbtCompound section = new NbtCompound();
        section.putByte("Y", y);
        final NbtCompound blockStates = new NbtCompound();
        blockStates.put("palette", blockPalette);
        blockStates.putLongArray("data", states);
        section.put("block_states", blockStates);
        if (biomePalette != null) {
            final NbtCompound biomes = new NbtCompound();
            biomes.put("palette", biomePalette);
            biomes.putLongArray("data", biomeData);
            section.put("biomes", biomes);
        }
        if (blockLight != null) {
            section.putByteArray("BlockLight", blockLight);
        }
        return section;
    }

    private static NbtCompound paletteEntry(final String name) {
        final NbtCompound entry = new NbtCompound();
        entry.putString("Name", name);
        return entry;
    }

    private static long[] pack(final int bits, final int count, final IntUnaryOperator values) {
        final int perWord = 64 / bits;
        final long[] words = new long[(count + perWord - 1) / perWord];
        final long mask = (1L << bits) - 1L;
        for (int i = 0; i < count; i++) {
            words[i / perWord] |= ((long) values.applyAsInt(i) & mask) << ((i % perWord) * bits);
        }
        return words;
    }
}
