package cn.net.rms.confluxmap.core.net.image;

import cn.net.rms.confluxmap.core.net.shared.SharedWaypointProtocolException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * Encodes and decodes {@code waypoint_images_v1} payloads. Mirrors the
 * shared-waypoint codec's strictness: unknown type ids, wrong directions,
 * truncated bodies, and out-of-range fields all throw
 * {@link SharedWaypointProtocolException} so the malformed-strike path applies
 * unchanged to this channel.
 */
public final class WaypointImageCodec {
    private WaypointImageCodec() {
    }

    public static boolean isKnownFormat(final int formatTag) {
        return formatTag == WaypointImageProto.FORMAT_PNG;
    }

    public static byte[] encodeC2S(final WaypointImageMessage.C2S message)
        throws SharedWaypointProtocolException {
        return encode(message, false);
    }

    public static byte[] encodeS2C(final WaypointImageMessage.S2C message)
        throws SharedWaypointProtocolException {
        return encode(message, true);
    }

    public static WaypointImageMessage.C2S decodeC2S(final byte[] payload)
        throws SharedWaypointProtocolException {
        return (WaypointImageMessage.C2S) decode(payload, false);
    }

    public static WaypointImageMessage.S2C decodeS2C(final byte[] payload)
        throws SharedWaypointProtocolException {
        return (WaypointImageMessage.S2C) decode(payload, true);
    }

    private static byte[] encode(final WaypointImageMessage message, final boolean s2c)
        throws SharedWaypointProtocolException {
        if (message == null) {
            throw new SharedWaypointProtocolException("null message");
        }
        final ByteArrayOutputStream rawOut = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(rawOut);
        try {
            if (message instanceof final ImageUploadBeginC2S m) {
                out.writeByte(WaypointImageProto.MSG_IMAGE_UPLOAD_BEGIN);
                writeUuid(out, m.imageId());
                out.writeByte(m.formatTag());
                out.writeInt(m.width());
                out.writeInt(m.height());
                out.writeInt(m.totalBytes());
            } else if (message instanceof final ImageUploadChunkC2S m) {
                out.writeByte(WaypointImageProto.MSG_IMAGE_UPLOAD_CHUNK);
                writeUuid(out, m.imageId());
                out.writeInt(m.seq());
                out.writeShort(m.bytes().length);
                out.write(m.bytes());
            } else if (message instanceof final ImageFetchC2S m) {
                out.writeByte(WaypointImageProto.MSG_IMAGE_FETCH);
                writeUuid(out, m.imageId());
            } else if (message instanceof final ImageUploadResultS2C m) {
                out.writeByte(WaypointImageProto.MSG_IMAGE_UPLOAD_RESULT);
                writeUuid(out, m.imageId());
                out.writeByte(m.status());
            } else if (message instanceof final ImageDataS2C m) {
                out.writeByte(WaypointImageProto.MSG_IMAGE_DATA);
                writeUuid(out, m.imageId());
                out.writeByte(m.formatTag());
                out.writeInt(m.bytes().length);
                out.write(m.bytes());
            } else if (message instanceof final ImageMissingS2C m) {
                out.writeByte(WaypointImageProto.MSG_IMAGE_MISSING);
                writeUuid(out, m.imageId());
            } else {
                throw new SharedWaypointProtocolException(
                    "unknown waypoint-image message type: " + message.getClass().getName()
                );
            }
            out.flush();
        } catch (final IOException e) {
            throw new SharedWaypointProtocolException("encode failed: " + e.getMessage(), e);
        }
        final byte[] payload = rawOut.toByteArray();
        if (payload.length > (s2c
            ? WaypointImageProto.MAX_S2C_PAYLOAD
            : WaypointImageProto.MAX_C2S_PAYLOAD)) {
            throw new SharedWaypointProtocolException(
                "waypoint-image payload of " + payload.length + " bytes exceeds the cap"
            );
        }
        return payload;
    }

    private static WaypointImageMessage decode(final byte[] payload, final boolean s2c)
        throws SharedWaypointProtocolException {
        if (payload == null || payload.length == 0) {
            throw new SharedWaypointProtocolException("empty waypoint-image payload");
        }
        final int typeId = payload[0] & 0xFF;
        final boolean knownS2C = typeId == WaypointImageProto.MSG_IMAGE_UPLOAD_RESULT
            || typeId == WaypointImageProto.MSG_IMAGE_DATA
            || typeId == WaypointImageProto.MSG_IMAGE_MISSING;
        final boolean knownC2S = typeId == WaypointImageProto.MSG_IMAGE_UPLOAD_BEGIN
            || typeId == WaypointImageProto.MSG_IMAGE_UPLOAD_CHUNK
            || typeId == WaypointImageProto.MSG_IMAGE_FETCH;
        if (!knownS2C && !knownC2S) {
            throw new SharedWaypointProtocolException(
                "unknown waypoint-image message type id: 0x" + Integer.toHexString(typeId)
            );
        }
        if (knownS2C != s2c) {
            throw new SharedWaypointProtocolException(
                "waypoint-image message type 0x" + Integer.toHexString(typeId)
                    + " is not valid in the " + (s2c ? "S2C" : "C2S") + " direction"
            );
        }
        final DataInputStream in = new DataInputStream(
            new ByteArrayInputStream(payload, 1, payload.length - 1)
        );
        try {
            final WaypointImageMessage message = switch (typeId) {
                case WaypointImageProto.MSG_IMAGE_UPLOAD_BEGIN -> new ImageUploadBeginC2S(
                    readUuid(in), in.readUnsignedByte(), in.readInt(), in.readInt(), in.readInt()
                );
                case WaypointImageProto.MSG_IMAGE_UPLOAD_CHUNK -> new ImageUploadChunkC2S(
                    readUuid(in), in.readInt(), readChunk(in)
                );
                case WaypointImageProto.MSG_IMAGE_FETCH -> new ImageFetchC2S(readUuid(in));
                case WaypointImageProto.MSG_IMAGE_UPLOAD_RESULT -> new ImageUploadResultS2C(
                    readUuid(in), in.readUnsignedByte()
                );
                case WaypointImageProto.MSG_IMAGE_DATA -> new ImageDataS2C(
                    readUuid(in), in.readUnsignedByte(), readImage(in)
                );
                case WaypointImageProto.MSG_IMAGE_MISSING -> new ImageMissingS2C(readUuid(in));
                default -> throw new SharedWaypointProtocolException(
                    "unhandled waypoint-image message type: " + typeId
                );
            };
            if (in.available() != 0) {
                throw new SharedWaypointProtocolException(
                    "trailing bytes after waypoint-image message: " + in.available()
                );
            }
            return message;
        } catch (final SharedWaypointProtocolException e) {
            throw e;
        } catch (final IOException | IllegalArgumentException e) {
            throw new SharedWaypointProtocolException("malformed waypoint-image payload", e);
        }
    }

    private static byte[] readChunk(final DataInputStream in)
        throws IOException, SharedWaypointProtocolException {
        final int length = in.readUnsignedShort();
        if (length < 1 || length > WaypointImageProto.MAX_CHUNK_BYTES) {
            throw new SharedWaypointProtocolException(
                "chunk length " + length + " outside 1.." + WaypointImageProto.MAX_CHUNK_BYTES
            );
        }
        return readFully(in, length);
    }

    private static byte[] readImage(final DataInputStream in)
        throws IOException, SharedWaypointProtocolException {
        final int length = in.readInt();
        if (length < 1 || length > WaypointImageProto.MAX_IMAGE_BYTES) {
            throw new SharedWaypointProtocolException(
                "image length " + length + " outside 1.." + WaypointImageProto.MAX_IMAGE_BYTES
            );
        }
        return readFully(in, length);
    }

    private static byte[] readFully(final DataInputStream in, final int length)
        throws IOException {
        final byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    private static void writeUuid(final DataOutputStream out, final UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(final DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }
}
