package cn.net.rms.confluxmap.core.net.shared;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Transport-level S2C framing for the shared-waypoint channel. A single
 * snapshot can exceed the 1 MiB vanilla S2C payload cap once waypoints carry
 * descriptions, so the sender splits oversized payloads into fragment
 * envelopes and the receiver reassembles them before the codec ever sees the
 * bytes. Everything else about the protocol - message ids, the state machine,
 * ordering - is untouched.
 *
 * <p>An envelope starts with {@link #S2C_FRAGMENT_PREFIX} ({@code 0x00},
 * which is never a valid legacy message type id), followed by the fragment
 * sequence and count, then the chunk bytes. Envelopes are only ever sent to
 * peers negotiated at {@link SharedWaypointProto#PROTO_MINOR_WAYPOINT_DESCRIPTION}
 * or newer, where descriptions exist and oversized payloads become possible.
 */
public final class SharedWaypointFraming {
    public static final int S2C_FRAGMENT_PREFIX = 0x00;
    /** Chunk size target: well under the vanilla S2C cap with envelope overhead. */
    public static final int S2C_FRAGMENT_TARGET_BYTES = 512 * 1024;
    public static final int MAX_S2C_FRAGMENTS = 8;
    private static final int ENVELOPE_OVERHEAD = 3;

    private SharedWaypointFraming() {
    }

    /**
     * Frames one encoded S2C payload for sending. Payloads at or under the
     * fragment target pass through unchanged; anything larger is split into
     * contiguous envelopes. Peers negotiated below the description minor never
     * produce oversized payloads, so they get the payload as-is.
     */
    public static List<byte[]> outgoing(final byte[] payload, final int negotiatedMinor) {
        if (payload == null) {
            throw new IllegalArgumentException("null payload");
        }
        if (negotiatedMinor < SharedWaypointProto.PROTO_MINOR_WAYPOINT_DESCRIPTION
            || payload.length <= S2C_FRAGMENT_TARGET_BYTES) {
            return List.of(payload);
        }
        final int fragmentCount = (payload.length + S2C_FRAGMENT_TARGET_BYTES - 1)
            / S2C_FRAGMENT_TARGET_BYTES;
        if (fragmentCount > MAX_S2C_FRAGMENTS) {
            throw new IllegalArgumentException(
                "payload of " + payload.length + " bytes needs " + fragmentCount
                    + " fragments, above the cap " + MAX_S2C_FRAGMENTS
            );
        }
        final List<byte[]> frames = new ArrayList<>(fragmentCount);
        for (int seq = 0; seq < fragmentCount; seq++) {
            final int from = seq * S2C_FRAGMENT_TARGET_BYTES;
            final int chunkLength = Math.min(S2C_FRAGMENT_TARGET_BYTES, payload.length - from);
            final byte[] frame = new byte[ENVELOPE_OVERHEAD + chunkLength];
            frame[0] = (byte) S2C_FRAGMENT_PREFIX;
            frame[1] = (byte) seq;
            frame[2] = (byte) fragmentCount;
            System.arraycopy(payload, from, frame, ENVELOPE_OVERHEAD, chunkLength);
            frames.add(frame);
        }
        return frames;
    }

    /**
     * Per-connection receiver for framed S2C payloads. Unframed payloads pass
     * through unchanged; envelopes are accumulated and the reassembled payload
     * is returned exactly once, after which the receiver is immediately ready
     * for the next logical payload. Malformed envelopes reset the accumulation
     * and throw; a new envelope arriving mid-accumulation replaces the stale
     * fragments (the sender emits its fragments contiguously, so anything else
     * means the earlier attempt is dead).
     */
    public static final class Reassembler {
        private final byte[][] chunks = new byte[MAX_S2C_FRAGMENTS][];
        private int count;
        private int received;

        /** Returns the reassembled payload when complete, else {@code null} while accumulating. */
        public byte[] offer(final byte[] payload) throws SharedWaypointProtocolException {
            if (payload == null || payload.length < ENVELOPE_OVERHEAD
                || (payload[0] & 0xFF) != S2C_FRAGMENT_PREFIX) {
                return payload;
            }
            final int seq = payload[1] & 0xFF;
            final int fragmentCount = payload[2] & 0xFF;
            if (fragmentCount < 1
                || fragmentCount > MAX_S2C_FRAGMENTS
                || seq >= fragmentCount
                || payload.length == ENVELOPE_OVERHEAD
                || payload.length - ENVELOPE_OVERHEAD > S2C_FRAGMENT_TARGET_BYTES) {
                reset();
                throw new SharedWaypointProtocolException(
                    "malformed S2C fragment envelope (seq=" + seq + " count=" + fragmentCount
                        + " bytes=" + (payload.length - ENVELOPE_OVERHEAD) + ")"
                );
            }
            // seq 0 always starts a fresh logical payload; a stale partial
            // accumulation with the same fragment count must not be merged into it.
            if (fragmentCount != count || seq == 0) {
                reset();
                count = fragmentCount;
            }
            chunks[seq] = Arrays.copyOfRange(payload, ENVELOPE_OVERHEAD, payload.length);
            received++;
            if (received < count) {
                return null;
            }
            final int total;
            try {
                total = totalLength();
            } catch (final IllegalStateException e) {
                reset();
                throw new SharedWaypointProtocolException(
                    "unusable S2C fragment accumulation: " + e.getMessage(), e
                );
            }
            final byte[] reassembled = new byte[total];
            int offset = 0;
            for (int i = 0; i < count; i++) {
                System.arraycopy(chunks[i], 0, reassembled, offset, chunks[i].length);
                offset += chunks[i].length;
            }
            reset();
            return reassembled;
        }

        public void reset() {
            Arrays.fill(chunks, null);
            count = 0;
            received = 0;
        }

        private int totalLength() {
            long total = 0;
            for (int i = 0; i < count; i++) {
                if (chunks[i] == null) {
                    throw new IllegalStateException("missing fragment " + i);
                }
                total += chunks[i].length;
            }
            if (total > (long) MAX_S2C_FRAGMENTS * S2C_FRAGMENT_TARGET_BYTES) {
                throw new IllegalStateException("total " + total);
            }
            return (int) total;
        }
    }
}
