package cn.net.rms.confluxmap.terrain;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public final class VarInts {
    private VarInts() {
    }

    public static int read(final DataInput input) throws IOException {
        int value = 0;
        int position = 0;
        while (position < 32) {
            final byte current = input.readByte();
            value |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) {
                return value;
            }
            position += 7;
        }
        throw new IOException("VarInt is too long");
    }

    public static void write(final DataOutput output, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            output.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        output.writeByte(value);
    }
}
