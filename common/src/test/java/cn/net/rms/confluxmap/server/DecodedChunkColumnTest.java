package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.server.DecodedChunkColumn.Section;
import java.util.List;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;

class DecodedChunkColumnTest {
    private static final class TestColumn extends DecodedChunkColumn {
        TestColumn(final List<Section> sections) {
            super(true, 7L, 0, new long[0], new long[0], new int[0], sections);
        }
    }

    @Test
    void blockStateIndexOutOfRangeResolvesToAir() {
        final Section section = new Section(
            0,
            new String[] {"minecraft:stone", "minecraft:air"},
            new SurfaceKind[] {SurfaceKind.UNKNOWN, SurfaceKind.UNKNOWN},
            pack(4, 4096, index -> index == 0 ? 15 : 0),
            4,
            new int[] {1},
            new long[0],
            1,
            new byte[0]
        );

        final TestColumn column = new TestColumn(List.of(section));

        assertEquals("minecraft:air", column.blockNameAt(0, 0, 0));
        assertEquals("minecraft:stone", column.blockNameAt(1, 0, 0));
    }

    @Test
    void blockLightNibbleIsReadFromBothHalves() {
        final Section section = new Section(
            0,
            new String[] {"minecraft:air"},
            new SurfaceKind[] {SurfaceKind.UNKNOWN},
            new long[0],
            4,
            new int[] {1},
            new long[0],
            1,
            new byte[] {(byte) 0xAB}
        );

        final TestColumn column = new TestColumn(List.of(section));

        assertEquals(0x0B, column.blockLightAbove(0, -1, 0));
        assertEquals(0x0A, column.blockLightAbove(1, -1, 0));
    }

    @Test
    void biomeIsResolvedFromTheNamespacedName() {
        final Section section = new Section(
            0,
            new String[] {"minecraft:air"},
            new SurfaceKind[] {SurfaceKind.UNKNOWN},
            new long[0],
            4,
            new int[] {0, 2},
            pack(1, 64, index -> index == 1 ? 1 : 0),
            1,
            new byte[0]
        );

        final TestColumn column = new TestColumn(List.of(section));

        assertEquals(0, column.biomeIdAt(0, 0, 0));
        assertEquals(2, column.biomeIdAt(4, 0, 0));
    }

    @Test
    void sectionYBelowTheLowestSectionIsAir() {
        final Section section = new Section(
            0,
            new String[] {"minecraft:stone"},
            new SurfaceKind[] {SurfaceKind.UNKNOWN},
            new long[0],
            4,
            new int[] {1},
            new long[0],
            1,
            new byte[0]
        );

        final TestColumn column = new TestColumn(List.of(section));

        assertEquals("minecraft:air", column.blockNameAt(0, -1, 0));
        assertEquals("minecraft:air", column.blockNameAt(0, -16, 0));
    }

    @Test
    void paletteIndexClampsAtBothEnds() {
        final Section twoNames = new Section(
            0,
            new String[] {"minecraft:stone", "minecraft:air"},
            new SurfaceKind[] {SurfaceKind.UNKNOWN, SurfaceKind.UNKNOWN},
            fill(4, 4096, 15),
            4,
            new int[] {1},
            new long[0],
            1,
            new byte[0]
        );
        final Section oneName = new Section(
            0,
            new String[] {"minecraft:air"},
            new SurfaceKind[] {SurfaceKind.UNKNOWN},
            fill(4, 4096, 15),
            4,
            new int[] {1},
            new long[0],
            1,
            new byte[0]
        );
        final Section noStates = new Section(
            0,
            new String[] {"minecraft:stone"},
            new SurfaceKind[] {SurfaceKind.UNKNOWN},
            new long[0],
            4,
            new int[] {1},
            new long[0],
            1,
            new byte[0]
        );

        assertEquals(1, DecodedChunkColumn.paletteIndex(twoNames, 0, 0, 0));
        assertEquals(0, DecodedChunkColumn.paletteIndex(oneName, 0, 0, 0));
        assertEquals(0, DecodedChunkColumn.paletteIndex(noStates, 0, 0, 0));
    }

    @Test
    void nibbleReadsXAndZFifteenFromBothHalves() {
        final byte[] light = new byte[2_048];
        light[127] = (byte) 0x42;
        light[255] = (byte) 0x51;

        // x 15, z 15 maps y 0 to index 255 (byte 127, odd -> high nibble) and y 1 to
        // index 511 (byte 255, high nibble); x 14 reads the low nibble of byte 127.
        assertEquals(0x4, DecodedChunkColumn.nibble(light, 15, 0, 15));
        assertEquals(0x5, DecodedChunkColumn.nibble(light, 15, 1, 15));
        assertEquals(0x2, DecodedChunkColumn.nibble(light, 14, 0, 15));
    }

    private static long[] fill(final int bits, final int count, final int value) {
        return pack(bits, count, ignored -> value);
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
