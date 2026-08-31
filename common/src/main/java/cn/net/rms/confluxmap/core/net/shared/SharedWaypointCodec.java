package cn.net.rms.confluxmap.core.net.shared;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Strict, Minecraft-free codec for {@link SharedWaypointProto#CHANNEL_ID}.
 *
 * <p>Every payload is {@code u8 messageType} followed by a fixed message body. Multi-byte
 * values are big-endian. Strings are {@code u16 byteLength + UTF-8}; snapshot counts are
 * {@code u16}. Callers must use the decoder matching the network direction so that both the
 * direction allowlist and its payload cap are enforced before any body allocation.
 */
public final class SharedWaypointCodec {
    private SharedWaypointCodec() {
    }

    /** Encodes one known message and enforces the cap for that message's direction. */
    public static byte[] encode(final SharedWaypointMessage message)
        throws SharedWaypointProtocolException {
        return encode(message, SharedWaypointProto.PROTO_MINOR);
    }

    /** Encodes one message using the wire shape negotiated with this peer. */
    public static byte[] encode(final SharedWaypointMessage message, final int negotiatedMinor)
        throws SharedWaypointProtocolException {
        if (message == null) {
            throw new SharedWaypointProtocolException("null message");
        }

        final ByteArrayOutputStream rawOut = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(rawOut);
        final boolean s2c;
        try {
            if (message instanceof final HelloC2S m) {
                s2c = false;
                out.writeByte(SharedWaypointProto.MSG_HELLO_C2S);
                out.writeInt(m.major());
                out.writeInt(m.minor());
            } else if (message instanceof final StatusS2C m) {
                s2c = true;
                out.writeByte(SharedWaypointProto.MSG_STATUS_S2C);
                out.writeInt(m.major());
                out.writeInt(m.minor());
                writeBoolean(out, m.supported());
                writeBoolean(out, m.enabled());
                writeBoolean(out, m.operator());
                writeUtf(out, m.worldId(), "worldId");
                out.writeLong(m.revision());
                out.writeInt(m.maxWorld());
                out.writeInt(m.maxPlayer());
                if (m.minor() >= 2) {
                    writeBoolean(out, m.ownerManagementAllowed());
                }
            } else if (message instanceof SubscribeC2S) {
                s2c = false;
                out.writeByte(SharedWaypointProto.MSG_SUBSCRIBE_C2S);
            } else if (message instanceof final CreateC2S m) {
                s2c = false;
                out.writeByte(SharedWaypointProto.MSG_CREATE_C2S);
                writeUuid(out, m.operationId());
                out.writeLong(m.expectedRevision());
                writeUtf(out, m.name(), "name");
                writeDimension(out, m.dimensionId());
                writeCoordinates(out, m.x(), m.y(), m.z());
                out.writeInt(m.color());
                writeWaypointType(out, m.type());
                writeMarkerStyle(out, negotiatedMinor, m.iconItemId(), m.markerLabel());
            } else if (message instanceof final DeleteC2S m) {
                s2c = false;
                out.writeByte(SharedWaypointProto.MSG_DELETE_C2S);
                writeUuid(out, m.operationId());
                writeUuid(out, m.id());
                out.writeLong(m.expectedRevision());
            } else if (message instanceof final LockC2S m) {
                s2c = false;
                out.writeByte(SharedWaypointProto.MSG_LOCK_C2S);
                writeUuid(out, m.operationId());
                writeUuid(out, m.id());
                out.writeLong(m.expectedRevision());
                writeBoolean(out, m.locked());
            } else if (message instanceof final UpdateC2S m) {
                s2c = false;
                out.writeByte(SharedWaypointProto.MSG_UPDATE_C2S);
                writeUuid(out, m.operationId());
                writeUuid(out, m.id());
                out.writeLong(m.expectedRevision());
                writeUtf(out, m.name(), "name");
                writeDimension(out, m.dimensionId());
                writeCoordinates(out, m.x(), m.y(), m.z());
                out.writeInt(m.color());
                writeWaypointType(out, m.type());
                writeMarkerStyle(out, negotiatedMinor, m.iconItemId(), m.markerLabel());
            } else if (message instanceof final SnapshotS2C m) {
                s2c = true;
                out.writeByte(SharedWaypointProto.MSG_SNAPSHOT_S2C);
                out.writeLong(m.revision());
                writeBoolean(out, m.operator());
                writeSnapshot(out, m.list(), negotiatedMinor);
            } else if (message instanceof final UpsertS2C m) {
                s2c = true;
                out.writeByte(SharedWaypointProto.MSG_UPSERT_S2C);
                out.writeLong(m.revision());
                writeWaypoint(out, m.waypoint(), negotiatedMinor);
            } else if (message instanceof final RemoveS2C m) {
                s2c = true;
                out.writeByte(SharedWaypointProto.MSG_REMOVE_S2C);
                out.writeLong(m.revision());
                writeUuid(out, m.id());
            } else if (message instanceof final ResultS2C m) {
                s2c = true;
                out.writeByte(SharedWaypointProto.MSG_RESULT_S2C);
                writeUuid(out, m.operationId());
                out.writeInt(m.statusCode());
                out.writeInt(m.errorCode());
            } else {
                throw new SharedWaypointProtocolException(
                    "unknown shared-waypoint message type: " + message.getClass().getName()
                );
            }
            out.flush();
        } catch (final SharedWaypointProtocolException e) {
            throw e;
        } catch (final IOException e) {
            throw new SharedWaypointProtocolException("encode failed: " + e.getMessage(), e);
        }

        final byte[] payload = rawOut.toByteArray();
        requirePayloadCap(payload.length, s2c);
        return payload;
    }

    /** Decodes exactly one client-to-server message. */
    public static SharedWaypointMessage decodeC2S(final byte[] payload)
        throws SharedWaypointProtocolException {
        return decodeC2S(payload, SharedWaypointProto.PROTO_MINOR);
    }

    public static SharedWaypointMessage decodeC2S(final byte[] payload, final int negotiatedMinor)
        throws SharedWaypointProtocolException {
        return decode(payload, false, negotiatedMinor);
    }

    /** Decodes exactly one server-to-client message. */
    public static SharedWaypointMessage decodeS2C(final byte[] payload)
        throws SharedWaypointProtocolException {
        return decodeS2C(payload, SharedWaypointProto.PROTO_MINOR);
    }

    public static SharedWaypointMessage decodeS2C(final byte[] payload, final int negotiatedMinor)
        throws SharedWaypointProtocolException {
        return decode(payload, true, negotiatedMinor);
    }

    private static SharedWaypointMessage decode(
        final byte[] payload,
        final boolean s2c,
        final int negotiatedMinor
    )
        throws SharedWaypointProtocolException {
        if (payload == null) {
            throw new SharedWaypointProtocolException("null payload");
        }
        if (payload.length == 0) {
            throw new SharedWaypointProtocolException("empty payload");
        }
        requirePayloadCap(payload.length, s2c);

        final int typeId = payload[0] & 0xFF;
        if (!isKnownType(typeId)) {
            throw new SharedWaypointProtocolException(
                "unknown shared-waypoint message type id: 0x" + Integer.toHexString(typeId)
            );
        }
        if (isS2C(typeId) != s2c) {
            throw new SharedWaypointProtocolException(
                "message type 0x" + Integer.toHexString(typeId)
                    + " is not valid in the " + (s2c ? "S2C" : "C2S") + " direction"
            );
        }

        final DataInputStream in = new DataInputStream(
            new ByteArrayInputStream(payload, 1, payload.length - 1)
        );
        try {
            final SharedWaypointMessage message = switch (typeId) {
                case SharedWaypointProto.MSG_HELLO_C2S -> new HelloC2S(in.readInt(), in.readInt());
                case SharedWaypointProto.MSG_STATUS_S2C -> readStatus(in);
                case SharedWaypointProto.MSG_SUBSCRIBE_C2S -> new SubscribeC2S();
                case SharedWaypointProto.MSG_CREATE_C2S -> new CreateC2S(
                    readUuid(in),
                    in.readLong(),
                    readUtf(in, "name"),
                    readDimension(in),
                    readFiniteDouble(in, "x"),
                    readFiniteDouble(in, "y"),
                    readFiniteDouble(in, "z"),
                    in.readInt(),
                    readWaypointType(in),
                    readIconItemId(in, negotiatedMinor),
                    readMarkerLabel(in, negotiatedMinor)
                );
                case SharedWaypointProto.MSG_DELETE_C2S -> new DeleteC2S(
                    readUuid(in), readUuid(in), in.readLong()
                );
                case SharedWaypointProto.MSG_LOCK_C2S -> new LockC2S(
                    readUuid(in), readUuid(in), in.readLong(), readBoolean(in, "locked")
                );
                case SharedWaypointProto.MSG_UPDATE_C2S -> new UpdateC2S(
                    readUuid(in), readUuid(in), in.readLong(), readUtf(in, "name"),
                    readDimension(in), readFiniteDouble(in, "x"), readFiniteDouble(in, "y"),
                    readFiniteDouble(in, "z"), in.readInt(), readWaypointType(in),
                    readIconItemId(in, negotiatedMinor), readMarkerLabel(in, negotiatedMinor)
                );
                case SharedWaypointProto.MSG_SNAPSHOT_S2C -> new SnapshotS2C(
                    in.readLong(), readBoolean(in, "operator"), readSnapshot(in, negotiatedMinor)
                );
                case SharedWaypointProto.MSG_UPSERT_S2C -> new UpsertS2C(
                    in.readLong(), readWaypoint(in, negotiatedMinor)
                );
                case SharedWaypointProto.MSG_REMOVE_S2C -> new RemoveS2C(
                    in.readLong(), readUuid(in)
                );
                case SharedWaypointProto.MSG_RESULT_S2C -> new ResultS2C(
                    readUuid(in), in.readInt(), in.readInt()
                );
                default -> throw new SharedWaypointProtocolException("unhandled message type: " + typeId);
            };
            if (in.available() != 0) {
                throw new SharedWaypointProtocolException(
                    "trailing bytes after shared-waypoint message: " + in.available()
                );
            }
            return message;
        } catch (final SharedWaypointProtocolException e) {
            throw e;
        } catch (final IOException | IllegalArgumentException e) {
            throw new SharedWaypointProtocolException("malformed shared-waypoint payload", e);
        }
    }

    private static void writeSnapshot(
        final DataOutputStream out,
        final List<SharedWaypoint> waypoints,
        final int negotiatedMinor
    )
        throws IOException, SharedWaypointProtocolException {
        if (waypoints.size() > SharedWaypointProto.MAX_SNAPSHOT_WAYPOINTS) {
            throw new SharedWaypointProtocolException(
                "snapshot count " + waypoints.size() + " above cap "
                    + SharedWaypointProto.MAX_SNAPSHOT_WAYPOINTS
            );
        }
        out.writeShort(waypoints.size());
        for (final SharedWaypoint waypoint : waypoints) {
            writeWaypoint(out, waypoint, negotiatedMinor);
        }
    }

    private static StatusS2C readStatus(final DataInputStream in)
        throws IOException, SharedWaypointProtocolException {
        final int major = in.readInt();
        final int minor = in.readInt();
        final boolean supported = readBoolean(in, "supported");
        final boolean enabled = readBoolean(in, "enabled");
        final boolean operator = readBoolean(in, "operator");
        final String worldId = readUtf(in, "worldId");
        final long revision = in.readLong();
        final int maxWorld = in.readInt();
        final int maxPlayer = in.readInt();
        final boolean ownerManagementAllowed = minor < 2
            || readBoolean(in, "ownerManagementAllowed");
        return new StatusS2C(
            major, minor, supported, enabled, operator, worldId, revision,
            maxWorld, maxPlayer, ownerManagementAllowed
        );
    }

    private static List<SharedWaypoint> readSnapshot(
        final DataInputStream in,
        final int negotiatedMinor
    )
        throws IOException, SharedWaypointProtocolException {
        final int count = in.readUnsignedShort();
        if (count > SharedWaypointProto.MAX_SNAPSHOT_WAYPOINTS) {
            throw new SharedWaypointProtocolException(
                "snapshot count " + count + " above cap "
                    + SharedWaypointProto.MAX_SNAPSHOT_WAYPOINTS
            );
        }
        final List<SharedWaypoint> waypoints = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            waypoints.add(readWaypoint(in, negotiatedMinor));
        }
        return waypoints;
    }

    private static void writeWaypoint(
        final DataOutputStream out,
        final SharedWaypoint waypoint,
        final int negotiatedMinor
    )
        throws IOException, SharedWaypointProtocolException {
        if (waypoint == null) {
            throw new SharedWaypointProtocolException("null shared waypoint");
        }
        writeUuid(out, waypoint.id());
        writeUuid(out, waypoint.publisherId());
        writeUtf(out, waypoint.publisherName(), "publisherName");
        writeUtf(out, waypoint.name(), "name");
        writeDimension(out, waypoint.dimensionId());
        writeCoordinates(out, waypoint.x(), waypoint.y(), waypoint.z());
        out.writeInt(waypoint.colorArgb());
        writeWaypointType(out, waypoint.type());
        // Kept in the v1 wire shape for older clients; server markers no longer exist.
        writeBoolean(out, false);
        out.writeLong(waypoint.createdAtEpochMs());
        out.writeLong(waypoint.revision());
        writeMarkerStyle(out, negotiatedMinor, waypoint.iconItemId(), waypoint.markerLabel());
    }

    private static SharedWaypoint readWaypoint(
        final DataInputStream in,
        final int negotiatedMinor
    )
        throws IOException, SharedWaypointProtocolException {
        final UUID id = readUuid(in);
        final UUID publisherId = readUuid(in);
        final String publisherName = readUtf(in, "publisherName");
        final String name = readUtf(in, "name");
        final DimensionId dimensionId = readDimension(in);
        final double x = readFiniteDouble(in, "x");
        final double y = readFiniteDouble(in, "y");
        final double z = readFiniteDouble(in, "z");
        final int color = in.readInt();
        final Waypoint.Type type = readWaypointType(in);
        readBoolean(in, "legacyLocked");
        final long createdAtEpochMs = in.readLong();
        final long revision = in.readLong();
        return new SharedWaypoint(
            id, publisherId, publisherName, name, dimensionId, x, y, z,
            color, type, readIconItemId(in, negotiatedMinor), readMarkerLabel(in, negotiatedMinor),
            createdAtEpochMs, revision
        );
    }

    private static void writeMarkerStyle(
        final DataOutputStream out,
        final int negotiatedMinor,
        final String iconItemId,
        final String markerLabel
    ) throws IOException, SharedWaypointProtocolException {
        if (negotiatedMinor >= 3) {
            writeUtf(out, iconItemId, "iconItemId");
            writeUtf(out, markerLabel, "markerLabel");
        }
    }

    private static String readIconItemId(
        final DataInputStream in,
        final int negotiatedMinor
    ) throws IOException, SharedWaypointProtocolException {
        return negotiatedMinor >= 3 ? readUtf(in, "iconItemId") : "";
    }

    private static String readMarkerLabel(
        final DataInputStream in,
        final int negotiatedMinor
    ) throws IOException, SharedWaypointProtocolException {
        return negotiatedMinor >= 3 ? readUtf(in, "markerLabel") : "";
    }

    private static void writeCoordinates(
        final DataOutputStream out,
        final double x,
        final double y,
        final double z
    ) throws IOException, SharedWaypointProtocolException {
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(z, "z");
        out.writeDouble(x);
        out.writeDouble(y);
        out.writeDouble(z);
    }

    private static double readFiniteDouble(final DataInputStream in, final String field)
        throws IOException, SharedWaypointProtocolException {
        final double value = in.readDouble();
        requireFinite(value, field);
        return value;
    }

    private static void requireFinite(final double value, final String field)
        throws SharedWaypointProtocolException {
        if (!Double.isFinite(value)) {
            throw new SharedWaypointProtocolException(field + " coordinate must be finite");
        }
    }

    private static void writeDimension(final DataOutputStream out, final DimensionId dimensionId)
        throws IOException, SharedWaypointProtocolException {
        if (dimensionId == null) {
            throw new SharedWaypointProtocolException("null dimensionId");
        }
        writeUtf(out, dimensionId.toString(), "dimensionId");
    }

    private static DimensionId readDimension(final DataInputStream in)
        throws IOException, SharedWaypointProtocolException {
        return DimensionId.parse(readUtf(in, "dimensionId"));
    }

    private static void writeUuid(final DataOutputStream out, final UUID value)
        throws IOException, SharedWaypointProtocolException {
        if (value == null) {
            throw new SharedWaypointProtocolException("null UUID field");
        }
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(final DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private static void writeWaypointType(final DataOutputStream out, final Waypoint.Type type)
        throws IOException, SharedWaypointProtocolException {
        if (type == null) {
            throw new SharedWaypointProtocolException("null waypoint type");
        }
        switch (type) {
            case NORMAL -> out.writeByte(0);
            case DEATH -> out.writeByte(1);
        }
    }

    private static Waypoint.Type readWaypointType(final DataInputStream in)
        throws IOException, SharedWaypointProtocolException {
        final int id = in.readUnsignedByte();
        return switch (id) {
            case 0 -> Waypoint.Type.NORMAL;
            case 1 -> Waypoint.Type.DEATH;
            default -> throw new SharedWaypointProtocolException("unknown waypoint type id: " + id);
        };
    }

    private static void writeBoolean(final DataOutputStream out, final boolean value) throws IOException {
        out.writeByte(value ? 1 : 0);
    }

    private static boolean readBoolean(final DataInputStream in, final String field)
        throws IOException, SharedWaypointProtocolException {
        final int value = in.readUnsignedByte();
        if (value != 0 && value != 1) {
            throw new SharedWaypointProtocolException(
                field + " boolean must be encoded as 0 or 1, got " + value
            );
        }
        return value == 1;
    }

    private static void writeUtf(
        final DataOutputStream out,
        final String value,
        final String field
    ) throws IOException, SharedWaypointProtocolException {
        if (value == null) {
            throw new SharedWaypointProtocolException("null " + field);
        }
        final byte[] bytes;
        try {
            final ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value));
            bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
        } catch (final CharacterCodingException e) {
            throw new SharedWaypointProtocolException("invalid Unicode in " + field, e);
        }
        if (bytes.length > SharedWaypointProto.MAX_UTF8_BYTES) {
            throw new SharedWaypointProtocolException(
                field + " UTF-8 length " + bytes.length + " above cap "
                    + SharedWaypointProto.MAX_UTF8_BYTES
            );
        }
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static String readUtf(final DataInputStream in, final String field)
        throws IOException, SharedWaypointProtocolException {
        final int length = in.readUnsignedShort();
        if (length > SharedWaypointProto.MAX_UTF8_BYTES) {
            throw new SharedWaypointProtocolException(
                field + " UTF-8 length " + length + " above cap "
                    + SharedWaypointProto.MAX_UTF8_BYTES
            );
        }
        final byte[] bytes = new byte[length];
        in.readFully(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (final CharacterCodingException e) {
            throw new SharedWaypointProtocolException("invalid UTF-8 in " + field, e);
        }
    }

    private static boolean isKnownType(final int typeId) {
        return typeId >= SharedWaypointProto.MSG_HELLO_C2S
            && typeId <= SharedWaypointProto.MSG_UPDATE_C2S;
    }

    private static boolean isS2C(final int typeId) {
        return typeId == SharedWaypointProto.MSG_STATUS_S2C
            || typeId == SharedWaypointProto.MSG_SNAPSHOT_S2C
            || typeId == SharedWaypointProto.MSG_UPSERT_S2C
            || typeId == SharedWaypointProto.MSG_REMOVE_S2C
            || typeId == SharedWaypointProto.MSG_RESULT_S2C;
    }

    private static void requirePayloadCap(final int length, final boolean s2c)
        throws SharedWaypointProtocolException {
        final int cap = s2c
            ? SharedWaypointProto.MAX_S2C_PAYLOAD
            : SharedWaypointProto.MAX_C2S_PAYLOAD;
        if (length > cap) {
            throw new SharedWaypointProtocolException(
                (s2c ? "S2C" : "C2S") + " payload of " + length + " bytes exceeds cap " + cap
            );
        }
    }
}
