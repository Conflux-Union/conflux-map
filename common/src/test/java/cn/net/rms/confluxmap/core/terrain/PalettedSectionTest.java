package cn.net.rms.confluxmap.core.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import org.junit.jupiter.api.Test;

final class PalettedSectionTest {
    @Test
    void decodesLocalPaletteWithoutValuesCrossingLongBoundaries() throws Exception {
        final int[] expected = new int[4096];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = 100 + i % 17;
        }

        final byte[] packet = packet(expected, 5, 17);

        assertArrayEquals(expected, PalettedSection.decode(packet, 8, 15, 4096));
    }

    @Test
    void decodesSingletonPalette() throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(0);
        VarInts.write(out, 42);
        VarInts.write(out, 0);

        final int[] decoded = PalettedSection.decode(bytes.toByteArray(), 8, 15, 4096);

        assertEquals(4096, decoded.length);
        for (final int value : decoded) {
            assertEquals(42, value);
        }
    }

    @Test
    void decodesSingletonPaletteWithoutAnArrayLengthPrefix() throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(0);
        VarInts.write(out, 42);

        final int[] decoded = PalettedSection.decode(bytes.toByteArray(), 8, 15, 4096);

        assertEquals(4096, decoded.length);
        for (final int value : decoded) {
            assertEquals(42, value);
        }
    }

    @Test
    void decodesPackedValuesWithoutAnArrayLengthPrefix() throws Exception {
        final int[] expected = new int[4096];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = i < 16 ? 100 : 100 + i % 2;
        }

        final byte[] packet = packetWithoutArrayLength(expected, 4, 2);

        assertArrayEquals(expected, PalettedSection.decode(packet, 8, 15, 4096));
    }

    private static byte[] packet(
        final int[] values, final int bits, final int paletteSize
    ) throws Exception {
        return packet(values, bits, paletteSize, true);
    }

    private static byte[] packetWithoutArrayLength(
        final int[] values, final int bits, final int paletteSize
    ) throws Exception {
        return packet(values, bits, paletteSize, false);
    }

    private static byte[] packet(
        final int[] values,
        final int bits,
        final int paletteSize,
        final boolean lengthPrefixed
    ) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(bits);
        VarInts.write(out, paletteSize);
        for (int i = 0; i < paletteSize; i++) {
            VarInts.write(out, 100 + i);
        }
        final int valuesPerLong = 64 / bits;
        final long[] packed = new long[(values.length + valuesPerLong - 1) / valuesPerLong];
        for (int i = 0; i < values.length; i++) {
            packed[i / valuesPerLong] |= (long) (values[i] - 100)
                << ((i % valuesPerLong) * bits);
        }
        if (lengthPrefixed) {
            VarInts.write(out, packed.length);
        }
        for (final long value : packed) {
            out.writeLong(value);
        }
        return bytes.toByteArray();
    }
}
