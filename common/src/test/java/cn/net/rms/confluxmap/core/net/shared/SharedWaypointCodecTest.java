package cn.net.rms.confluxmap.core.net.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SharedWaypointCodecTest {
    private static final UUID OPERATION_ID = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
    private static final UUID WAYPOINT_ID = UUID.fromString("12345678-1234-5678-9abc-def012345678");
    private static final UUID PUBLISHER_ID = UUID.fromString("fedcba98-7654-3210-fedc-ba9876543210");

    @Test
    void everyMessageTypeRoundTrips() throws SharedWaypointProtocolException {
        final SharedWaypoint waypoint = waypoint("Spawn", "Builder", DimensionId.OVERWORLD, 41L);
        final List<SharedWaypointMessage> messages = List.of(
            new HelloC2S(1, 0),
            new StatusS2C(1, 0, true, true, false, "world-1", 42L, 200, 20),
            new SubscribeC2S(),
            new CreateC2S(
                OPERATION_ID, 41L, "Village", DimensionId.NETHER, 1.25, -64.0, 99.5,
                0xFF12AB34, Waypoint.Type.NORMAL
            ),
            new DeleteC2S(OPERATION_ID, WAYPOINT_ID, 40L),
            new LockC2S(OPERATION_ID, WAYPOINT_ID, 40L, true),
            new SnapshotS2C(42L, true, List.of(waypoint)),
            new UpsertS2C(42L, waypoint),
            new RemoveS2C(43L, WAYPOINT_ID),
            new ResultS2C(OPERATION_ID, 2, 7)
        );

        for (final SharedWaypointMessage original : messages) {
            assertEquals(original, roundTrip(original), original.getClass().getSimpleName());
        }
    }

    @Test
    void snapshotOwnsAnImmutableListCopy() {
        final List<SharedWaypoint> mutable = new ArrayList<>();
        mutable.add(waypoint("one", "publisher", DimensionId.OVERWORLD, 1L));
        final SnapshotS2C snapshot = new SnapshotS2C(1L, false, mutable);

        mutable.clear();
        assertEquals(1, snapshot.list().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.list().clear());
    }

    @Test
    void exactUtf8AndSnapshotBoundariesAreAccepted() throws SharedWaypointProtocolException {
        final String maxAscii = "x".repeat(SharedWaypointProto.MAX_UTF8_BYTES);
        final DimensionId maxDimension = DimensionId.of("n", "p".repeat(254));
        final SharedWaypoint boundary = waypoint(maxAscii, maxAscii, maxDimension, 1L);
        assertEquals(boundary, ((UpsertS2C) roundTrip(new UpsertS2C(1L, boundary))).waypoint());

        final List<SharedWaypoint> maximum = new ArrayList<>();
        for (int i = 0; i < SharedWaypointProto.MAX_SNAPSHOT_WAYPOINTS; i++) {
            maximum.add(waypoint("", "", DimensionId.OVERWORLD, i));
        }
        final SnapshotS2C snapshot = new SnapshotS2C(8L, false, maximum);
        assertEquals(SharedWaypointProto.MAX_SNAPSHOT_WAYPOINTS,
            ((SnapshotS2C) roundTrip(snapshot)).list().size());
    }

    @Test
    void encoderRejectsUtf8FieldsAboveByteCap() {
        final String tooLong = "x".repeat(SharedWaypointProto.MAX_UTF8_BYTES + 1);
        assertThrows(SharedWaypointProtocolException.class, () -> SharedWaypointCodec.encode(
            new CreateC2S(
                OPERATION_ID, 0L, tooLong, DimensionId.OVERWORLD, 0, 0, 0, 0, Waypoint.Type.NORMAL
            )
        ));
        assertThrows(SharedWaypointProtocolException.class, () -> SharedWaypointCodec.encode(
            new StatusS2C(1, 0, true, true, false, tooLong, 0, 0, 0)
        ));
        assertThrows(SharedWaypointProtocolException.class, () -> SharedWaypointCodec.encode(
            new UpsertS2C(1L, waypoint("ok", tooLong, DimensionId.OVERWORLD, 1L))
        ));
        assertThrows(SharedWaypointProtocolException.class, () -> SharedWaypointCodec.encode(
            new UpsertS2C(1L, waypoint(
                "ok", "ok", DimensionId.of("n", "p".repeat(255)), 1L
            ))
        ));

        final String multiByteAboveCap = "\u4e2d".repeat(86);
        assertTrue(multiByteAboveCap.getBytes(StandardCharsets.UTF_8).length
            > SharedWaypointProto.MAX_UTF8_BYTES);
        assertThrows(SharedWaypointProtocolException.class, () -> SharedWaypointCodec.encode(
            new CreateC2S(
                OPERATION_ID, 0L, multiByteAboveCap, DimensionId.OVERWORLD,
                0, 0, 0, 0, Waypoint.Type.NORMAL
            )
        ));
        assertThrows(SharedWaypointProtocolException.class, () -> SharedWaypointCodec.encode(
            new CreateC2S(
                OPERATION_ID, 0L, "\uD800", DimensionId.OVERWORLD,
                0, 0, 0, 0, Waypoint.Type.NORMAL
            )
        ));
    }

    @Test
    void encoderRejectsSnapshotAboveCountCap() {
        final List<SharedWaypoint> tooMany = new ArrayList<>();
        for (int i = 0; i <= SharedWaypointProto.MAX_SNAPSHOT_WAYPOINTS; i++) {
            tooMany.add(waypoint("", "", DimensionId.OVERWORLD, i));
        }
        assertThrows(SharedWaypointProtocolException.class,
            () -> SharedWaypointCodec.encode(new SnapshotS2C(1L, false, tooMany)));
    }

    @Test
    void decoderEnforcesDirectionAndDirectionalPayloadCaps() throws SharedWaypointProtocolException {
        final byte[] hello = SharedWaypointCodec.encode(new HelloC2S(1, 0));
        final byte[] status = SharedWaypointCodec.encode(
            new StatusS2C(1, 0, true, true, false, "world", 0, 0, 0)
        );
        assertThrows(SharedWaypointProtocolException.class, () -> SharedWaypointCodec.decodeS2C(hello));
        assertThrows(SharedWaypointProtocolException.class, () -> SharedWaypointCodec.decodeC2S(status));

        final byte[] oversizedC2S = new byte[SharedWaypointProto.MAX_C2S_PAYLOAD + 1];
        oversizedC2S[0] = (byte) SharedWaypointProto.MSG_HELLO_C2S;
        assertThrows(SharedWaypointProtocolException.class,
            () -> SharedWaypointCodec.decodeC2S(oversizedC2S));

        final byte[] oversizedS2C = new byte[SharedWaypointProto.MAX_S2C_PAYLOAD + 1];
        oversizedS2C[0] = (byte) SharedWaypointProto.MSG_STATUS_S2C;
        assertThrows(SharedWaypointProtocolException.class,
            () -> SharedWaypointCodec.decodeS2C(oversizedS2C));
    }

    @Test
    void decoderRejectsTrailingTruncatedAndUnknownPayloads() throws SharedWaypointProtocolException {
        final List<SharedWaypointMessage> messages = representativeMessages();
        for (final SharedWaypointMessage message : messages) {
            final byte[] encoded = SharedWaypointCodec.encode(message);
            final byte[] trailing = Arrays.copyOf(encoded, encoded.length + 1);
            assertThrows(SharedWaypointProtocolException.class, () -> decodeFor(message, trailing));

            for (int length = 0; length < encoded.length; length++) {
                final byte[] truncated = Arrays.copyOf(encoded, length);
                assertThrows(SharedWaypointProtocolException.class, () -> decodeFor(message, truncated),
                    message.getClass().getSimpleName() + " accepted prefix length " + length);
            }
        }

        assertThrows(SharedWaypointProtocolException.class,
            () -> SharedWaypointCodec.decodeC2S(new byte[] {(byte) 0x7F}));
        assertThrows(SharedWaypointProtocolException.class, () -> SharedWaypointCodec.decodeC2S(null));
        assertThrows(SharedWaypointProtocolException.class, () -> SharedWaypointCodec.encode(null));
        assertThrows(SharedWaypointProtocolException.class, () -> SharedWaypointCodec.encode(
            new SharedWaypointMessage() {
                @Override
                public int typeId() {
                    return SharedWaypointProto.MSG_HELLO_C2S;
                }
            }
        ));
    }

    @Test
    void decoderRejectsMalformedUtf8AndOversizedLengthPrefix() throws IOException {
        assertThrows(SharedWaypointProtocolException.class,
            () -> SharedWaypointCodec.decodeC2S(createWithRawName(new byte[] {(byte) 0xC3, 0x28})));

        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(raw);
        out.writeByte(SharedWaypointProto.MSG_CREATE_C2S);
        writeUuid(out, OPERATION_ID);
        out.writeLong(0L);
        out.writeShort(SharedWaypointProto.MAX_UTF8_BYTES + 1);
        assertThrows(SharedWaypointProtocolException.class,
            () -> SharedWaypointCodec.decodeC2S(raw.toByteArray()));
    }

    @Test
    void decoderRejectsInvalidBooleanEnumCountAndCoordinates()
        throws IOException, SharedWaypointProtocolException {
        final byte[] lock = SharedWaypointCodec.encode(
            new LockC2S(OPERATION_ID, WAYPOINT_ID, 1L, false)
        );
        lock[lock.length - 1] = 2;
        assertThrows(SharedWaypointProtocolException.class, () -> SharedWaypointCodec.decodeC2S(lock));

        final byte[] create = SharedWaypointCodec.encode(
            new CreateC2S(OPERATION_ID, 0L, "", DimensionId.OVERWORLD, 1, 2, 3, 0, Waypoint.Type.NORMAL)
        );
        create[create.length - 1] = 127;
        assertThrows(SharedWaypointProtocolException.class, () -> SharedWaypointCodec.decodeC2S(create));

        final ByteArrayOutputStream rawSnapshot = new ByteArrayOutputStream();
        final DataOutputStream snapshotOut = new DataOutputStream(rawSnapshot);
        snapshotOut.writeByte(SharedWaypointProto.MSG_SNAPSHOT_S2C);
        snapshotOut.writeLong(1L);
        snapshotOut.writeByte(0);
        snapshotOut.writeShort(SharedWaypointProto.MAX_SNAPSHOT_WAYPOINTS + 1);
        assertThrows(SharedWaypointProtocolException.class,
            () -> SharedWaypointCodec.decodeS2C(rawSnapshot.toByteArray()));

        assertThrows(SharedWaypointProtocolException.class, () -> SharedWaypointCodec.encode(
            new CreateC2S(
                OPERATION_ID, 0L, "bad", DimensionId.OVERWORLD,
                Double.NaN, 0, 0, 0, Waypoint.Type.NORMAL
            )
        ));

        final byte[] nonFinite = SharedWaypointCodec.encode(
            new CreateC2S(OPERATION_ID, 0L, "", DimensionId.OVERWORLD, 1, 2, 3, 0, Waypoint.Type.NORMAL)
        );
        final int xOffset = 1 + 16 + Long.BYTES + 2 + 2
            + DimensionId.OVERWORLD.toString().getBytes(StandardCharsets.UTF_8).length;
        ByteBuffer.wrap(nonFinite, xOffset, Double.BYTES).putDouble(Double.POSITIVE_INFINITY);
        assertThrows(SharedWaypointProtocolException.class,
            () -> SharedWaypointCodec.decodeC2S(nonFinite));
    }

    @Test
    void fixedSeedRandomGarbageNeverLeaksRuntimeFailures() {
        final Random random = new Random(0x5A17C0DEL);
        int rejected = 0;
        for (int i = 0; i < 5_000; i++) {
            final byte[] garbage = new byte[random.nextInt(256)];
            random.nextBytes(garbage);
            try {
                if ((i & 1) == 0) {
                    final SharedWaypointMessage decoded = SharedWaypointCodec.decodeC2S(garbage);
                    assertEquals(decoded, SharedWaypointCodec.decodeC2S(SharedWaypointCodec.encode(decoded)));
                } else {
                    final SharedWaypointMessage decoded = SharedWaypointCodec.decodeS2C(garbage);
                    assertEquals(decoded, SharedWaypointCodec.decodeS2C(SharedWaypointCodec.encode(decoded)));
                }
            } catch (final SharedWaypointProtocolException expected) {
                rejected++;
            }
        }
        assertTrue(rejected > 0);
    }

    private static SharedWaypointMessage roundTrip(final SharedWaypointMessage message)
        throws SharedWaypointProtocolException {
        return decodeFor(message, SharedWaypointCodec.encode(message));
    }

    private static SharedWaypointMessage decodeFor(
        final SharedWaypointMessage message,
        final byte[] payload
    ) throws SharedWaypointProtocolException {
        if (message instanceof HelloC2S
            || message instanceof SubscribeC2S
            || message instanceof CreateC2S
            || message instanceof DeleteC2S
            || message instanceof LockC2S) {
            return SharedWaypointCodec.decodeC2S(payload);
        }
        return SharedWaypointCodec.decodeS2C(payload);
    }

    private static List<SharedWaypointMessage> representativeMessages() {
        final SharedWaypoint waypoint = waypoint("name", "publisher", DimensionId.END, 9L);
        return List.of(
            new HelloC2S(1, 0),
            new StatusS2C(1, 0, true, false, true, "world", 9, 10, 2),
            new SubscribeC2S(),
            new CreateC2S(OPERATION_ID, 8L, "name", DimensionId.END, 1, 2, 3, 4, Waypoint.Type.DEATH),
            new DeleteC2S(OPERATION_ID, WAYPOINT_ID, 8),
            new LockC2S(OPERATION_ID, WAYPOINT_ID, 8, true),
            new SnapshotS2C(9, true, List.of(waypoint)),
            new UpsertS2C(9, waypoint),
            new RemoveS2C(10, WAYPOINT_ID),
            new ResultS2C(OPERATION_ID, 1, 0)
        );
    }

    private static SharedWaypoint waypoint(
        final String name,
        final String publisherName,
        final DimensionId dimensionId,
        final long revision
    ) {
        return new SharedWaypoint(
            WAYPOINT_ID,
            PUBLISHER_ID,
            publisherName,
            name,
            dimensionId,
            12.5,
            -4.25,
            88.0,
            0xFFAABBCC,
            Waypoint.Type.DEATH,
            true,
            1_721_555_200_000L,
            revision
        );
    }

    private static byte[] createWithRawName(final byte[] name) throws IOException {
        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(raw);
        out.writeByte(SharedWaypointProto.MSG_CREATE_C2S);
        writeUuid(out, OPERATION_ID);
        out.writeLong(0L);
        out.writeShort(name.length);
        out.write(name);
        return raw.toByteArray();
    }

    private static void writeUuid(final DataOutputStream out, final UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }
}
