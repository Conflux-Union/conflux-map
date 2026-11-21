package cn.net.rms.confluxmap.core.net.shared;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class SharedWaypointFramingTest {
    @Test
    void payloadsUnderTheFragmentTargetPassThroughUnframed() throws SharedWaypointProtocolException {
        final byte[] payload = new byte[1024];
        Arrays.fill(payload, (byte) 0x2A);

        assertEquals(List.of(payload), SharedWaypointFraming.outgoing(payload, 6));
        assertEquals(List.of(payload), SharedWaypointFraming.outgoing(payload, 7));

        final SharedWaypointFraming.Reassembler reassembler = new SharedWaypointFraming.Reassembler();
        assertArrayEquals(payload, reassembler.offer(payload));
    }

    @Test
    void oversizedPayloadsFragmentOnlyAtNegotiatedMinorSeven() throws SharedWaypointProtocolException {
        final byte[] payload = new byte[SharedWaypointFraming.S2C_FRAGMENT_TARGET_BYTES * 2 + 1234];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 31);
        }

        assertEquals(List.of(payload), SharedWaypointFraming.outgoing(payload, 6));

        final List<byte[]> frames = SharedWaypointFraming.outgoing(payload, 7);
        assertEquals(3, frames.size());
        for (final byte[] frame : frames) {
            assertTrue(frame.length <= SharedWaypointFraming.S2C_FRAGMENT_TARGET_BYTES + 3);
            assertEquals(0x00, frame[0]);
        }

        final SharedWaypointFraming.Reassembler reassembler = new SharedWaypointFraming.Reassembler();
        assertNull(reassembler.offer(frames.get(0)));
        assertNull(reassembler.offer(frames.get(1)));
        assertArrayEquals(payload, reassembler.offer(frames.get(2)));
        // The reassembler is ready for the next logical payload right away.
        assertNull(reassembler.offer(frames.get(0)));
    }

    @Test
    void aNewEnvelopeRestartedAccumulationReplacesStaleFragments() throws SharedWaypointProtocolException {
        final byte[] first = new byte[SharedWaypointFraming.S2C_FRAGMENT_TARGET_BYTES * 2 + 10];
        final byte[] second = new byte[SharedWaypointFraming.S2C_FRAGMENT_TARGET_BYTES * 2 + 10];
        Arrays.fill(first, (byte) 1);
        Arrays.fill(second, (byte) 2);
        final List<byte[]> firstFrames = SharedWaypointFraming.outgoing(first, 7);
        final List<byte[]> secondFrames = SharedWaypointFraming.outgoing(second, 7);

        final SharedWaypointFraming.Reassembler reassembler = new SharedWaypointFraming.Reassembler();
        assertNull(reassembler.offer(firstFrames.get(0)));
        assertNull(reassembler.offer(secondFrames.get(0)));
        assertNull(reassembler.offer(secondFrames.get(1)));
        assertArrayEquals(second, reassembler.offer(secondFrames.get(2)));
    }

    @Test
    void fragmentEnvelopesNeverCollideWithLegacyMessageTypes() {
        assertTrue(SharedWaypointProto.MSG_HELLO_C2S >= 1);
        for (int typeId = SharedWaypointProto.MSG_HELLO_C2S;
            typeId <= SharedWaypointProto.MSG_MOVE_BATCH_C2S;
            typeId++) {
            assertTrue(typeId != SharedWaypointFraming.S2C_FRAGMENT_PREFIX);
        }
    }

    @Test
    void reassemblyRejectsEnvelopeAbuseAndDropsTheAccumulation() {
        final SharedWaypointFraming.Reassembler reassembler = new SharedWaypointFraming.Reassembler();
        // count byte of zero and count above the fragment cap are both malformed.
        assertThrows(
            SharedWaypointProtocolException.class,
            () -> reassembler.offer(new byte[] {0x00, 0, 0})
        );
        assertThrows(
            SharedWaypointProtocolException.class,
            () -> reassembler.offer(new byte[] {0x00, 0, (byte) (SharedWaypointFraming.MAX_S2C_FRAGMENTS + 1)})
        );
        // Truncated envelope: claims two fragments but carries no bytes.
        assertThrows(
            SharedWaypointProtocolException.class,
            () -> reassembler.offer(new byte[] {0x00, 0, 2})
        );

        // A sequence out of range for its own count is malformed as well.
        assertThrows(
            SharedWaypointProtocolException.class,
            () -> reassembler.offer(new byte[] {0x00, 2, 2})
        );

        // Chunk bodies never exceed the fragment target; a bigger body is malformed.
        final byte[] oversizedChunk = new byte[3 + SharedWaypointFraming.S2C_FRAGMENT_TARGET_BYTES + 1];
        oversizedChunk[0] = 0x00;
        oversizedChunk[1] = 0;
        oversizedChunk[2] = 1;
        assertThrows(
            SharedWaypointProtocolException.class,
            () -> reassembler.offer(oversizedChunk)
        );
    }
}
