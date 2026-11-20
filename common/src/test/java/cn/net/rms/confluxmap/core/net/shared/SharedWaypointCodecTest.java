package cn.net.rms.confluxmap.core.net.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import cn.net.rms.confluxmap.core.shared.SharedWaypointGroup;
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
    private static final UUID GROUP_ID = UUID.fromString("0f0e0d0c-0b0a-4909-8877-665544332211");
    private static final String IMAGE_ID = "0123abcd-4567-789a-bcde-f0123456789a";

    @Test
    void descriptionsAndImageIdsRoundTripAtMinorSevenAndVanishBelowIt() throws Exception {
        final CreateC2S notedCreate = new CreateC2S(
            OPERATION_ID, 41L, "Village", DimensionId.NETHER, 1.25, -64.0, 99.5,
            0xFF12AB34, Waypoint.Type.NORMAL, "minecraft:diamond", "V",
            "产物:\n钻石 ×3", IMAGE_ID, GROUP_ID
        );
        final CreateC2S plainCreate = new CreateC2S(
            OPERATION_ID, 41L, "Village", DimensionId.NETHER, 1.25, -64.0, 99.5,
            0xFF12AB34, Waypoint.Type.NORMAL, "minecraft:diamond", "V", GROUP_ID
        );

        assertEquals(notedCreate, SharedWaypointCodec.decodeC2S(SharedWaypointCodec.encode(notedCreate, 7), 7));
        assertArrayEquals(
            SharedWaypointCodec.encode(plainCreate, 6),
            SharedWaypointCodec.encode(notedCreate, 6)
        );
        final CreateC2S legacyCreate = (CreateC2S) SharedWaypointCodec.decodeC2S(
            SharedWaypointCodec.encode(notedCreate, 6), 6
        );
        assertEquals("", legacyCreate.description());
        assertEquals("", legacyCreate.imageId());

        final SharedWaypoint notedWaypoint = new SharedWaypoint(
            WAYPOINT_ID, PUBLISHER_ID, "Builder", "Spawn", DimensionId.OVERWORLD,
            12.5, -4.25, 88.0, 0xFFAABBCC, Waypoint.Type.NORMAL,
            "minecraft:compass", "S", "产物:\n钻石 ×3", IMAGE_ID,
            1_721_555_200_000L, 41L, GROUP_ID
        );
        final UpsertS2C upsert = new UpsertS2C(42L, notedWaypoint);
        assertEquals(upsert, SharedWaypointCodec.decodeS2C(SharedWaypointCodec.encode(upsert, 7), 7));
        final SharedWaypoint legacyWaypoint = ((UpsertS2C) SharedWaypointCodec.decodeS2C(
            SharedWaypointCodec.encode(upsert, 6), 6
        )).waypoint();
        assertEquals("", legacyWaypoint.description());
        assertEquals("", legacyWaypoint.imageId());

        final SnapshotS2C snapshot = new SnapshotS2C(43L, true, List.of(notedWaypoint));
        assertEquals(
            snapshot,
            SharedWaypointCodec.decodeS2C(SharedWaypointCodec.encode(snapshot, 7), 7)
        );
    }

    @Test
    void createAndUpdateRejectDescriptionsBeyondTheNormalizedCap() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new CreateC2S(
                OPERATION_ID, 41L, "Village", DimensionId.NETHER, 1.25, -64.0, 99.5,
                0xFF12AB34, Waypoint.Type.NORMAL, "minecraft:diamond", "V",
                "a".repeat(513), "", GROUP_ID
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new UpdateC2S(
                OPERATION_ID, WAYPOINT_ID, 40L, "Updated", DimensionId.END,
                8d, 70d, 9d, 0xFF3498DB, Waypoint.Type.NORMAL, "minecraft:diamond", "V",
                "a".repeat(513), "", null, true
            )
        );
    }

    @Test
    void descriptionLengthPrefixAboveTheWireCapIsRejectedOnDecode() throws IOException {
        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(raw);
        out.writeByte(SharedWaypointProto.MSG_UPSERT_S2C);
        out.writeLong(42L);
        writeUuid(out, WAYPOINT_ID);
        writeUuid(out, PUBLISHER_ID);
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(0);
        out.writeDouble(0);
        out.writeDouble(0);
        out.writeDouble(0);
        out.writeInt(0);
        out.writeByte(0);
        out.writeByte(0);
        out.writeLong(1L);
        out.writeLong(1L);
        out.writeShort(0);
        out.writeShort(0);
        out.writeByte(0);
        out.writeShort(SharedWaypointProto.MAX_DESCRIPTION_UTF8_BYTES + 1);
        assertThrows(SharedWaypointProtocolException.class,
            () -> SharedWaypointCodec.decodeS2C(raw.toByteArray(), 7));
    }


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
            new CreateC2S(
                OPERATION_ID, 41L, "Village", DimensionId.NETHER, 1.25, -64.0, 99.5,
                0xFF12AB34, Waypoint.Type.NORMAL, "minecraft:diamond", "V", GROUP_ID
            ),
            new UpdateC2S(
                OPERATION_ID, WAYPOINT_ID, 40L, "Updated", DimensionId.END,
                8d, 70d, 9d, 0xFF3498DB, Waypoint.Type.NORMAL, "minecraft:diamond", "V", GROUP_ID, true
            ),
            new UpdateC2S(
                OPERATION_ID, WAYPOINT_ID, 40L, "Updated", DimensionId.END,
                8d, 70d, 9d, 0xFF3498DB, Waypoint.Type.NORMAL, "minecraft:diamond", "V", null, true
            ),
            new GroupCreateC2S(OPERATION_ID, 41L, "Villages"),
            new GroupRenameC2S(OPERATION_ID, 3L, GROUP_ID, "Farms"),
            new GroupDeleteC2S(OPERATION_ID, 3L, GROUP_ID),
            new SetWaypointGroupC2S(OPERATION_ID, WAYPOINT_ID, 40L, GROUP_ID),
            new SetWaypointGroupC2S(OPERATION_ID, WAYPOINT_ID, 40L, null),
            new MoveBatchC2S(OPERATION_ID, 41L, List.of(
                new MoveBatchC2S.Entry(WAYPOINT_ID, 40L, GROUP_ID),
                new MoveBatchC2S.Entry(PUBLISHER_ID, 39L, null)
            )),
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
    void statusAddsOwnerPolicyOnlyForProtocolMinorTwo() throws Exception {
        final StatusS2C legacy = new StatusS2C(
            1, 1, true, true, false, "world", 2L, 20, 10, false
        );
        final StatusS2C current = new StatusS2C(
            1, 2, true, true, false, "world", 2L, 20, 10, false
        );

        assertEquals(current, SharedWaypointCodec.decodeS2C(SharedWaypointCodec.encode(current)));
        assertEquals(
            SharedWaypointCodec.encode(legacy).length + 1,
            SharedWaypointCodec.encode(current).length
        );
        assertTrue(((StatusS2C) SharedWaypointCodec.decodeS2C(
            SharedWaypointCodec.encode(legacy)
        )).ownerManagementAllowed());
    }

    @Test
    void protocolMinorThreeCarriesMarkerStyleWhileOlderMinorsKeepTheirWireShape()
        throws Exception {
        final CreateC2S styledCreate = new CreateC2S(
            OPERATION_ID, 41L, "Village", DimensionId.NETHER, 1.25, -64.0, 99.5,
            0xFF12AB34, Waypoint.Type.NORMAL, "minecraft:diamond", "\u5bb6\uD83D\uDE80"
        );
        final CreateC2S plainCreate = new CreateC2S(
            OPERATION_ID, 41L, "Village", DimensionId.NETHER, 1.25, -64.0, 99.5,
            0xFF12AB34, Waypoint.Type.NORMAL
        );

        assertTrue(
            SharedWaypointCodec.encode(styledCreate, 3).length
                > SharedWaypointCodec.encode(styledCreate, 2).length
        );
        assertArrayEquals(
            SharedWaypointCodec.encode(plainCreate, 2),
            SharedWaypointCodec.encode(styledCreate, 2)
        );
        assertEquals(
            styledCreate,
            SharedWaypointCodec.decodeC2S(SharedWaypointCodec.encode(styledCreate, 3), 3)
        );
        assertEquals(
            plainCreate,
            SharedWaypointCodec.decodeC2S(SharedWaypointCodec.encode(styledCreate, 2), 2)
        );
        assertThrows(
            SharedWaypointProtocolException.class,
            () -> SharedWaypointCodec.decodeC2S(
                SharedWaypointCodec.encode(styledCreate, 3),
                2
            )
        );

        final SharedWaypoint styledWaypoint = new SharedWaypoint(
            WAYPOINT_ID, PUBLISHER_ID, "Builder", "Spawn", DimensionId.OVERWORLD,
            12.5, -4.25, 88.0, 0xFFAABBCC, Waypoint.Type.NORMAL,
            "minecraft:compass", "S", 1_721_555_200_000L, 41L
        );
        final UpsertS2C upsert = new UpsertS2C(42L, styledWaypoint);
        assertEquals(
            upsert,
            SharedWaypointCodec.decodeS2C(SharedWaypointCodec.encode(upsert, 3), 3)
        );
    }

    @Test
    void batchMovesAndGroupAssignmentRequireNegotiatedMinorSix() throws Exception {
        final MoveBatchC2S batch = new MoveBatchC2S(OPERATION_ID, 41L, List.of(
            new MoveBatchC2S.Entry(WAYPOINT_ID, 40L, GROUP_ID)
        ));
        assertThrows(
            SharedWaypointProtocolException.class,
            () -> SharedWaypointCodec.encode(batch, SharedWaypointProto.PROTO_MINOR_BATCH_MOVE - 1)
        );
        assertThrows(
            SharedWaypointProtocolException.class,
            () -> SharedWaypointCodec.decodeC2S(
                SharedWaypointCodec.encode(batch), SharedWaypointProto.PROTO_MINOR_BATCH_MOVE - 1
            )
        );
        assertEquals(
            batch,
            SharedWaypointCodec.decodeC2S(SharedWaypointCodec.encode(batch))
        );

        final CreateC2S groupedCreate = new CreateC2S(
            OPERATION_ID, 41L, "Village", DimensionId.OVERWORLD, 1, 2, 3,
            0xFF12AB34, Waypoint.Type.NORMAL, "", "", GROUP_ID
        );
        assertThrows(
            SharedWaypointProtocolException.class,
            () -> SharedWaypointCodec.encode(groupedCreate, SharedWaypointProto.PROTO_MINOR_BATCH_MOVE - 1)
        );

        final UpdateC2S unspecifiedUpdate = new UpdateC2S(
            OPERATION_ID, WAYPOINT_ID, 40L, "Updated", DimensionId.OVERWORLD,
            8d, 70d, 9d, 0xFF3498DB, Waypoint.Type.NORMAL, "", ""
        );
        assertThrows(
            SharedWaypointProtocolException.class,
            () -> SharedWaypointCodec.encode(unspecifiedUpdate, SharedWaypointProto.PROTO_MINOR_BATCH_MOVE)
        );
        // Below minor 6 the same message keeps its legacy wire shape with no group bytes.
        assertEquals(
            unspecifiedUpdate,
            SharedWaypointCodec.decodeC2S(
                SharedWaypointCodec.encode(unspecifiedUpdate, SharedWaypointProto.PROTO_MINOR_BATCH_MOVE - 1),
                SharedWaypointProto.PROTO_MINOR_BATCH_MOVE - 1
            )
        );
    }

    @Test
    void batchEntryCountIsCappedOnTheWire() {
        final List<MoveBatchC2S.Entry> entries = new ArrayList<>();
        for (int i = 0; i < SharedWaypointProto.MAX_BATCH_MOVE_ENTRIES + 1; i++) {
            entries.add(new MoveBatchC2S.Entry(UUID.randomUUID(), i, null));
        }
        final MoveBatchC2S oversized = new MoveBatchC2S(OPERATION_ID, 0L, entries);
        assertThrows(
            SharedWaypointProtocolException.class,
            () -> SharedWaypointCodec.encode(oversized)
        );
    }

    @Test
    void groupMutationsRequireNegotiatedMinorFive() throws SharedWaypointProtocolException {
        final GroupCreateC2S groupCreate = new GroupCreateC2S(OPERATION_ID, 41L, "Villages");
        final GroupRenameC2S groupRename = new GroupRenameC2S(OPERATION_ID, 3L, GROUP_ID, "Farms");
        final GroupDeleteC2S groupDelete = new GroupDeleteC2S(OPERATION_ID, 3L, GROUP_ID);
        final SetWaypointGroupC2S setGroup = new SetWaypointGroupC2S(
            OPERATION_ID, WAYPOINT_ID, 40L, GROUP_ID
        );
        final List<SharedWaypointMessage> mutations = List.of(groupCreate, groupRename, groupDelete, setGroup);

        for (final SharedWaypointMessage mutation : mutations) {
            assertThrows(
                SharedWaypointProtocolException.class,
                () -> SharedWaypointCodec.encode(mutation, SharedWaypointProto.PROTO_MINOR_GROUP_MUTATIONS - 1),
                mutation.getClass().getSimpleName()
            );
            assertThrows(
                SharedWaypointProtocolException.class,
                () -> SharedWaypointCodec.decodeC2S(
                    SharedWaypointCodec.encode(mutation),
                    SharedWaypointProto.PROTO_MINOR_GROUP_MUTATIONS - 1
                ),
                mutation.getClass().getSimpleName()
            );
            final byte[] payload = SharedWaypointCodec.encode(mutation);
            assertEquals(
                mutation.typeId(),
                payload[0] & 0xFF,
                mutation.getClass().getSimpleName()
            );
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

    @Test
    void groupMessagesRoundTripAndRequireNegotiatedMinorFour()
        throws SharedWaypointProtocolException {
        final SharedWaypointGroup group = group("Villages", 41L);
        final GroupUpsertS2C upsert = new GroupUpsertS2C(42L, group);
        final GroupRemoveS2C remove = new GroupRemoveS2C(43L, GROUP_ID);

        assertEquals(upsert, roundTrip(upsert));
        assertEquals(remove, roundTrip(remove));

        assertThrows(SharedWaypointProtocolException.class, () -> SharedWaypointCodec.encode(upsert, 3));
        assertThrows(SharedWaypointProtocolException.class, () -> SharedWaypointCodec.encode(remove, 3));
        assertFalse(SharedWaypointCodec.encodableAt(upsert, 3));
        assertFalse(SharedWaypointCodec.encodableAt(remove, 0));
        assertTrue(SharedWaypointCodec.encodableAt(upsert, 4));
        assertTrue(SharedWaypointCodec.encodableAt(remove, SharedWaypointProto.PROTO_MINOR));
        assertTrue(SharedWaypointCodec.encodableAt(new ResultS2C(OPERATION_ID, 0, 0), 0));
        assertTrue(SharedWaypointCodec.encodableAt(new SubscribeC2S(), 1));
    }

    @Test
    void minorFourSnapshotsCarryGroupsWhileMinorThreeKeepsItsWireShape()
        throws SharedWaypointProtocolException {
        final SharedWaypointGroup group = group("Villages", 41L);
        final SharedWaypoint grouped = groupedWaypoint(group.id(), 42L);
        final SnapshotS2C snapshot = new SnapshotS2C(42L, true, List.of(grouped), List.of(group));

        assertEquals(snapshot, roundTrip(snapshot));

        final byte[] legacy = SharedWaypointCodec.encode(snapshot, 3);
        final SnapshotS2C legacyView = (SnapshotS2C) SharedWaypointCodec.decodeS2C(legacy, 3);
        assertTrue(legacyView.groups().isEmpty());
        assertNull(legacyView.list().get(0).groupId());
        assertTrue(SharedWaypointCodec.encode(snapshot, 4).length > legacy.length);

        // A minor-3 peer must also tolerate a waypoint that carries a group reference.
        final byte[] legacyUpsert = SharedWaypointCodec.encode(new UpsertS2C(43L, grouped), 3);
        assertNull(((UpsertS2C) SharedWaypointCodec.decodeS2C(legacyUpsert, 3)).waypoint().groupId());
        assertEquals(
            grouped,
            ((UpsertS2C) SharedWaypointCodec.decodeS2C(
                SharedWaypointCodec.encode(new UpsertS2C(43L, grouped), 4), 4
            )).waypoint()
        );
    }

    @Test
    void snapshotGroupCountAboveCapIsRejected() throws IOException {
        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(raw);
        out.writeByte(SharedWaypointProto.MSG_SNAPSHOT_S2C);
        out.writeLong(1L);
        out.writeByte(0);
        out.writeShort(0);
        out.writeShort(SharedWaypointProto.MAX_SNAPSHOT_GROUPS + 1);
        assertThrows(SharedWaypointProtocolException.class,
            () -> SharedWaypointCodec.decodeS2C(raw.toByteArray()));

        final List<SharedWaypointGroup> tooMany = new ArrayList<>();
        for (int i = 0; i <= SharedWaypointProto.MAX_SNAPSHOT_GROUPS; i++) {
            tooMany.add(group("g" + i, 1L));
        }
        assertThrows(SharedWaypointProtocolException.class,
            () -> SharedWaypointCodec.encode(new SnapshotS2C(1L, false, List.of(), tooMany)));
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
            || message instanceof LockC2S
            || message instanceof UpdateC2S
            || message instanceof GroupCreateC2S
            || message instanceof GroupRenameC2S
            || message instanceof GroupDeleteC2S
            || message instanceof SetWaypointGroupC2S
            || message instanceof MoveBatchC2S) {
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
            new UpdateC2S(
                OPERATION_ID, WAYPOINT_ID, 8, "updated", DimensionId.END,
                1, 2, 3, 4, Waypoint.Type.DEATH, "", "", GROUP_ID, true
            ),
            new SnapshotS2C(9, true, List.of(waypoint)),
            new UpsertS2C(9, waypoint),
            new RemoveS2C(10, WAYPOINT_ID),
            new GroupUpsertS2C(11, group("g", 9L)),
            new GroupRemoveS2C(12, WAYPOINT_ID),
            new ResultS2C(OPERATION_ID, 1, 0)
        );
    }

    private static SharedWaypointGroup group(final String name, final long revision) {
        return new SharedWaypointGroup(
            GROUP_ID, PUBLISHER_ID, name, 1_721_555_200_000L, revision
        );
    }

    private static SharedWaypoint groupedWaypoint(final UUID groupId, final long revision) {
        return new SharedWaypoint(
            WAYPOINT_ID, PUBLISHER_ID, "Builder", "Spawn", DimensionId.OVERWORLD,
            12.5, -4.25, 88.0, 0xFFAABBCC, Waypoint.Type.NORMAL,
            "", "", 1_721_555_200_000L, revision, groupId
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
