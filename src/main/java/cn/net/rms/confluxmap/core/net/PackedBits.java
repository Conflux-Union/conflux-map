package cn.net.rms.confluxmap.core.net;

/**
 * Decodes the non-spanning packed integer arrays used by Minecraft heightmaps and block-state
 * palettes. Values are packed from the least significant bit of each long; a value never crosses
 * a long boundary (the vanilla palette writer chooses a bits-per-word value that guarantees this).
 */
public final class PackedBits {
    private PackedBits() {
    }

    public static int decode(final long[] words, final int bits, final int index) {
        if (words == null || bits <= 0 || bits > 32 || index < 0) {
            throw new IllegalArgumentException("invalid packed-bit arguments");
        }
        final int perWord = 64 / bits;
        if (perWord == 0) {
            throw new IllegalArgumentException("bits must be <= 64");
        }
        final int wordIndex = index / perWord;
        if (wordIndex >= words.length) {
            throw new IndexOutOfBoundsException("packed index " + index + " outside " + words.length * perWord);
        }
        final int shift = (index % perWord) * bits;
        final long mask = (1L << bits) - 1L;
        return (int) ((words[wordIndex] >>> shift) & mask);
    }

    public static int[] decodeAll(final long[] words, final int bits, final int count) {
        if (count < 0) {
            throw new IllegalArgumentException("negative count");
        }
        final int[] result = new int[count];
        for (int i = 0; i < count; i++) {
            result[i] = decode(words, bits, i);
        }
        return result;
    }
}
