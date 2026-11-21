package cn.net.rms.confluxmap.mc.net.waypointimage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.net.image.WaypointImageProto;
import java.util.List;
import org.junit.jupiter.api.Test;

class WaypointImageClientChunkerTest {
    @Test
    void fullImageSplitsIntoFourFullChunksPlusNoRemainder() {
        final byte[] payload = new byte[WaypointImageProto.MAX_IMAGE_BYTES];
        final List<byte[]> chunks = WaypointImageClient.chunk(payload);
        assertEquals(4, chunks.size());
        for (final byte[] chunk : chunks) {
            assertEquals(WaypointImageClient.UPLOAD_CHUNK_BYTES, chunk.length);
        }
    }

    @Test
    void oddSizesKeepTheRemainderInAFinalSmallerChunk() {
        assertEquals(1, WaypointImageClient.chunk(new byte[48]).size());
        assertEquals(48, WaypointImageClient.chunk(new byte[48]).get(0).length);
        final byte[] payload = new byte[WaypointImageProto.MAX_CHUNK_BYTES + 1];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 13);
        }
        final List<byte[]> chunks = WaypointImageClient.chunk(payload);
        assertEquals(2, chunks.size());
        assertEquals(WaypointImageProto.MAX_CHUNK_BYTES, chunks.get(0).length);
        assertEquals(1, chunks.get(1).length);
        // The tail chunk carries the payload's last byte in order.
        assertEquals(payload[WaypointImageProto.MAX_CHUNK_BYTES], chunks.get(1)[0]);
        assertEquals(payload[0], chunks.get(0)[0]);
    }
}
