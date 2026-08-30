package cn.net.rms.confluxmap.terrain;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Arrays;

/** Decodes Minecraft's compact paletted-container packet without loading game classes. */
public final class PalettedSection {
    private PalettedSection() {
    }

    public static int[] decode(
        final byte[] packet,
        final int localPaletteMaxBits,
        final int directPaletteBits,
        final int size
    ) throws IOException {
        final DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet));
        final int bits = input.readUnsignedByte();
        if (bits > 31) {
            throw new IOException("unsupported palette width: " + bits);
        }
        if (bits == 0) {
            final int singleton = VarInts.read(input);
            final int longs = VarInts.read(input);
            if (longs != 0) {
                throw new IOException("singleton palette has packed data");
            }
            final int[] result = new int[size];
            Arrays.fill(result, singleton);
            return result;
        }

        final int[] palette;
        if (bits <= localPaletteMaxBits) {
            final int paletteSize = VarInts.read(input);
            if (paletteSize < 1 || paletteSize > 1 << bits) {
                throw new IOException("invalid local palette size: " + paletteSize);
            }
            palette = new int[paletteSize];
            for (int i = 0; i < palette.length; i++) {
                palette[i] = VarInts.read(input);
            }
        } else {
            if (bits != directPaletteBits) {
                throw new IOException(
                    "unexpected direct palette width " + bits + ", expected " + directPaletteBits
                );
            }
            palette = null;
        }

        final int longCount = VarInts.read(input);
        final int valuesPerLong = 64 / bits;
        final int expectedLongs = (size + valuesPerLong - 1) / valuesPerLong;
        if (longCount != expectedLongs) {
            throw new IOException(
                "invalid packed palette length " + longCount + ", expected " + expectedLongs
            );
        }
        final long[] packed = new long[longCount];
        for (int i = 0; i < packed.length; i++) {
            packed[i] = input.readLong();
        }
        if (input.available() != 0) {
            throw new IOException("trailing paletted-container bytes");
        }

        final long mask = (1L << bits) - 1L;
        final int[] result = new int[size];
        for (int i = 0; i < result.length; i++) {
            final int encoded = (int) ((packed[i / valuesPerLong]
                >>> ((i % valuesPerLong) * bits)) & mask);
            if (palette != null && encoded >= palette.length) {
                throw new IOException("palette index out of bounds: " + encoded);
            }
            result[i] = palette == null ? encoded : palette[encoded];
        }
        return result;
    }
}
