package cn.net.rms.confluxmap.terrain.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;

public final class TerrainWire {
    public static final int PROTOCOL_VERSION = 2;
    public static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;

    public static final byte HELLO = 1;
    public static final byte READY = 2;
    public static final byte CHUNK = 3;
    public static final byte MATERIALS = 4;
    public static final byte MATERIAL_REQUEST = 5;
    public static final byte RESULT = 6;
    public static final byte RESET = 7;
    public static final byte CLOSE = 8;
    public static final byte ERROR = 9;
    public static final byte BLOCK_DELTA = 10;
    public static final byte PAUSE = 11;
    public static final byte INVALIDATE_CHUNK = 12;

    private TerrainWire() {
    }

    public static void writeFrame(
        final DataOutputStream output, final byte type, final byte[] payload
    ) throws IOException {
        if (payload.length + 1 > MAX_FRAME_BYTES) {
            throw new IOException("terrain frame exceeds " + MAX_FRAME_BYTES + " bytes");
        }
        output.writeInt(payload.length + 1);
        output.writeByte(type);
        output.write(payload);
        output.flush();
    }

    public static Frame readFrame(final DataInputStream input) throws IOException {
        final int length;
        try {
            length = input.readInt();
        } catch (final EOFException eof) {
            return null;
        }
        if (length < 1 || length > MAX_FRAME_BYTES) {
            throw new IOException("invalid terrain frame length: " + length);
        }
        final byte type = input.readByte();
        final byte[] payload = new byte[length - 1];
        input.readFully(payload);
        return new Frame(type, payload);
    }

    public record Frame(byte type, byte[] payload) {
    }
}
