package cn.net.rms.confluxmap.terrain.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;

final class TerrainWireTest {
    @Test
    void roundTripsFramedPayload() throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        TerrainWire.writeFrame(new DataOutputStream(bytes), TerrainWire.CHUNK, new byte[] {3, 1, 4});

        final TerrainWire.Frame frame = TerrainWire.readFrame(
            new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))
        );

        assertEquals(TerrainWire.CHUNK, frame.type());
        assertArrayEquals(new byte[] {3, 1, 4}, frame.payload());
    }

    @Test
    void rejectsOversizedFrameBeforeAllocatingPayload() throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(TerrainWire.MAX_FRAME_BYTES + 1);
        out.writeByte(TerrainWire.CHUNK);

        assertThrows(IOException.class, () -> TerrainWire.readFrame(
            new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))
        ));
    }
}
