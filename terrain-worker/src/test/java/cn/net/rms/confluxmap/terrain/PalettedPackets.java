package cn.net.rms.confluxmap.terrain;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PalettedPackets {
    private PalettedPackets() {
    }

    public static byte[] encode(
        final int[] values, final int localMaxBits, final int directBits
    ) throws IOException {
        final Map<Integer, Integer> indexes = new LinkedHashMap<>();
        for (final int value : values) {
            indexes.computeIfAbsent(value, ignored -> indexes.size());
        }
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(bytes);
        if (indexes.size() == 1) {
            out.writeByte(0);
            VarInts.write(out, indexes.keySet().iterator().next());
            VarInts.write(out, 0);
            return bytes.toByteArray();
        }
        final int localBits = Math.max(4, 32 - Integer.numberOfLeadingZeros(indexes.size() - 1));
        final boolean direct = localBits > localMaxBits;
        final int bits = direct ? directBits : localBits;
        out.writeByte(bits);
        if (!direct) {
            VarInts.write(out, indexes.size());
            for (final int value : indexes.keySet()) {
                VarInts.write(out, value);
            }
        }
        final int valuesPerLong = 64 / bits;
        final long[] packed = new long[(values.length + valuesPerLong - 1) / valuesPerLong];
        for (int i = 0; i < values.length; i++) {
            final int encoded = direct ? values[i] : indexes.get(values[i]);
            packed[i / valuesPerLong] |= (long) encoded << ((i % valuesPerLong) * bits);
        }
        VarInts.write(out, packed.length);
        for (final long value : packed) {
            out.writeLong(value);
        }
        return bytes.toByteArray();
    }
}
