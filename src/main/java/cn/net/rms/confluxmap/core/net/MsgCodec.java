package cn.net.rms.confluxmap.core.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UTFDataFormatException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Encodes and decodes every {@link Message} on the {@link Proto#CHANNEL_ID} channel.
 *
 * <p>All payloads are framed as: {@code u8 type} + type-specific body, big-endian. Strings are
 * {@code u16 length} + UTF-8 bytes, capped at {@value Proto#MAX_UTF8_BYTES} bytes. Arrays are
 * {@code u8 count} + entries. Byte blobs are {@code u32 length} + bytes (length bounded per
 * message type by {@link Proto#MAX_S2C_PAYLOAD} / {@link Proto#MAX_C2S_PAYLOAD}).
 *
 * <p>Hostile-input rules:
 * <ul>
 *   <li>Every length is bounds-checked against a hardcoded cap before allocation.</li>
 *   <li>Every string is read into a fixed-size buffer; the standard {@link DataInputStream#readUTF()}
 *       65535-byte ceiling is not relied on (it is too generous for the protocol's actual needs).</li>
 *   <li>{@link ProtoException} is thrown on any violation; never a negative-array-size, {@code OOM},
 *       or silent corruption.</li>
 *   <li>The decoder returns the one specific {@link Message} subtype matching the type byte; an
 *       unknown type byte throws.</li>
 * </ul>
 *
 * <p>This class is deliberately Minecraft-free (pure {@code byte[]}). The Fabric wiring in
 * {@code mc.net.ClientNetworking} / {@code server.ServerNetworking} is a thin {@link
 * java.io.DataOutput} bridge on top of {@code PacketByteBuf}.
 */
public final class MsgCodec {
    private MsgCodec() {
    }

    // ---- Encode ----

    /** Serializes {@code msg} to its wire form, including the leading type byte. */
    public static byte[] encode(final Message msg) throws ProtoException {
        final ByteArrayOutputStream rawOut = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(rawOut);
        try {
            out.writeByte(msg.typeId());
            if (msg instanceof final HelloC2S m) {
                encodeHelloC2S(out, m);
            } else if (msg instanceof final HelloPolicyS2C m) {
                encodeHelloPolicyS2C(out, m);
            } else if (msg instanceof final MapViewReqC2S m) {
                encodeMapViewReqC2S(out, m);
            } else if (msg instanceof final MapPatchS2C m) {
                encodeMapPatchS2C(out, m);
            } else if (msg instanceof final PolicyUpdateS2C m) {
                encodePolicyUpdateS2C(out, m);
            } else if (msg instanceof final ErrorS2C m) {
                encodeErrorS2C(out, m);
            } else {
                throw new ProtoException("unknown message type: " + msg.getClass().getName());
            }
            out.flush();
        } catch (final ProtoException e) {
            throw e;
        } catch (final IOException e) {
            // ByteArrayOutputStream/DataOutputStream only throw on length-cap violations
            // (UTFDataFormatException when the encoder rejects a too-long string); anything
            // else is a bug.
            throw new ProtoException("encode failed: " + e.getMessage(), e);
        }
        final byte[] bytes = rawOut.toByteArray();
        final int cap = capForType(msg.typeId());
        if (bytes.length > cap) {
            throw new ProtoException("encoded " + msg.getClass().getSimpleName() + " is " + bytes.length
                + " bytes, cap is " + cap);
        }
        return bytes;
    }

    private static int capForType(final int typeId) {
        return isS2C(typeId) ? Proto.MAX_S2C_PAYLOAD : Proto.MAX_C2S_PAYLOAD;
    }

    private static boolean isS2C(final int typeId) {
        return typeId == Proto.MSG_HELLO_POLICY_S2C
            || typeId == Proto.MSG_MAP_PATCH_S2C
            || typeId == Proto.MSG_POLICY_UPDATE_S2C
            || typeId == Proto.MSG_ERROR_S2C;
    }

    private static void encodeHelloC2S(final DataOutputStream out, final HelloC2S m) throws IOException, ProtoException {
        writeUtf(out, m.modVersion());
        writeUtf(out, m.predictorVersion());
    }

    private static void encodeHelloPolicyS2C(final DataOutputStream out, final HelloPolicyS2C m) throws IOException, ProtoException {
        final HelloPolicyS2C.Flags f = m.flags();
        int flagBits = 0;
        if (f.seedGranted()) {
            flagBits |= 1;
        }
        if (f.correctionsEnabled()) {
            flagBits |= 2;
        }
        if (f.structureInfoEnabled()) {
            flagBits |= 4;
        }
        out.writeByte(flagBits);
        writeUtf(out, m.worldId());
        writeUtf(out, m.worldgenVersion());
        final HelloPolicyS2C.Budgets b = m.budgets();
        out.writeInt(b.maxBytesPerSec());
        out.writeShort(b.maxTilesPerReq());
        out.writeShort(b.minReqIntervalMs());
        out.writeByte(b.maxPatchLod());
        final List<HelloPolicyS2C.DimDescriptor> dims = m.dims();
        if (dims.size() > Proto.MAX_DIM_ENTRIES) {
            throw new ProtoException("too many dim entries: " + dims.size());
        }
        out.writeByte(dims.size());
        for (final HelloPolicyS2C.DimDescriptor d : dims) {
            writeUtf(out, d.dimId());
            writeUtf(out, d.dimType());
            int dimBits = 0;
            if (d.predictable()) {
                dimBits |= 1;
            }
            if (d.hasSeed()) {
                dimBits |= 2;
            }
            out.writeByte(dimBits);
            out.writeLong(d.seed());
        }
    }

    private static void encodeMapViewReqC2S(final DataOutputStream out, final MapViewReqC2S m) throws IOException, ProtoException {
        out.writeInt(m.reqId());
        out.writeByte(m.dimIndex());
        out.writeByte(m.lod());
        final List<MapViewReqC2S.TileReq> tiles = m.tiles();
        if (tiles.size() > Proto.MAX_TILES_PER_REQ) {
            throw new ProtoException("too many tiles in MAP_VIEW_REQ: " + tiles.size());
        }
        out.writeByte(tiles.size());
        for (final MapViewReqC2S.TileReq t : tiles) {
            out.writeInt(t.tileX());
            out.writeInt(t.tileZ());
            out.writeLong(t.sinceRevision());
        }
    }

    private static void encodeMapPatchS2C(final DataOutputStream out, final MapPatchS2C m) throws IOException, ProtoException {
        out.writeInt(m.reqId());
        out.writeByte(m.dimIndex());
        out.writeByte(m.lod());
        out.writeInt(m.tileX());
        out.writeInt(m.tileZ());
        out.writeByte(m.mode());
        out.writeLong(m.tileRevision());
        if (m.presence().length != Proto.PATCH_PRESENCE_BYTES) {
            throw new ProtoException(
                "presence bitmap must be " + Proto.PATCH_PRESENCE_BYTES + " bytes, got " + m.presence().length
            );
        }
        out.write(m.presence());
        // S4: PatchCodec.encode/decode the body. Until then it is opaque bytes the codec round-trips verbatim.
        writeBoundedBytes(out, m.body(), Proto.MAX_S2C_PAYLOAD);
    }

    private static void encodePolicyUpdateS2C(final DataOutputStream out, final PolicyUpdateS2C m) throws IOException {
        final HelloPolicyS2C.Flags f = m.flags();
        int flagBits = 0;
        if (f.seedGranted()) {
            flagBits |= 1;
        }
        if (f.correctionsEnabled()) {
            flagBits |= 2;
        }
        if (f.structureInfoEnabled()) {
            flagBits |= 4;
        }
        out.writeByte(flagBits);
        final HelloPolicyS2C.Budgets b = m.budgets();
        out.writeInt(b.maxBytesPerSec());
        out.writeShort(b.maxTilesPerReq());
        out.writeShort(b.minReqIntervalMs());
        out.writeByte(b.maxPatchLod());
    }

    private static void encodeErrorS2C(final DataOutputStream out, final ErrorS2C m) throws IOException, ProtoException {
        out.writeByte(m.code());
        writeUtf(out, m.detail());
    }

    // ---- Decode ----

    /** Parses exactly one {@link Message} from {@code payload}; throws on any violation. */
    public static Message decode(final byte[] payload) throws ProtoException {
        if (payload.length == 0) {
            throw new ProtoException("empty payload");
        }
        final int typeId = payload[0] & 0xFF;
        if (typeId < Proto.MSG_MIN || typeId > Proto.MSG_MAX) {
            throw new ProtoException("unknown message type id: 0x" + Integer.toHexString(typeId));
        }
        final DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload, 1, payload.length - 1));
        try {
            return switch (typeId) {
                case Proto.MSG_HELLO_C2S -> decodeHelloC2S(in);
                case Proto.MSG_HELLO_POLICY_S2C -> decodeHelloPolicyS2C(in, payload.length);
                case Proto.MSG_MAP_VIEW_REQ_C2S -> decodeMapViewReqC2S(in, payload.length);
                case Proto.MSG_MAP_PATCH_S2C -> decodeMapPatchS2C(in, payload.length);
                case Proto.MSG_POLICY_UPDATE_S2C -> decodePolicyUpdateS2C(in);
                case Proto.MSG_ERROR_S2C -> decodeErrorS2C(in);
                default -> throw new ProtoException("unhandled message type id: 0x" + Integer.toHexString(typeId));
            };
        } catch (final ProtoException e) {
            throw e;
        } catch (final UTFDataFormatException e) {
            throw new ProtoException("invalid utf-8: " + e.getMessage(), e);
        } catch (final IOException e) {
            // DataInputStream on a ByteArrayInputStream throws EOFException when truncated;
            // UTFDataFormatException is handled above. Anything else means the input is hostile
            // but in a shape we didn't anticipate.
            throw new ProtoException("decode io error: " + e.getMessage(), e);
        }
    }

    private static HelloC2S decodeHelloC2S(final DataInputStream in) throws IOException, ProtoException {
        final String modVersion = readUtf(in);
        final String predictorVersion = readUtf(in);
        return new HelloC2S(modVersion, predictorVersion);
    }

    private static HelloPolicyS2C decodeHelloPolicyS2C(final DataInputStream in, final int payloadLen) throws IOException, ProtoException {
        requireLen(payloadLen, Proto.MAX_S2C_PAYLOAD);
        final int flagBits = in.readUnsignedByte();
        final HelloPolicyS2C.Flags flags = new HelloPolicyS2C.Flags(
            (flagBits & 1) != 0,
            (flagBits & 2) != 0,
            (flagBits & 4) != 0
        );
        final String worldId = readUtf(in);
        final String worldgenVersion = readUtf(in);
        final int maxBytesPerSec = in.readInt();
        final int maxTilesPerReq = in.readUnsignedShort();
        final int minReqIntervalMs = in.readUnsignedShort();
        final int maxPatchLod = in.readUnsignedByte();
        final HelloPolicyS2C.Budgets budgets = new HelloPolicyS2C.Budgets(maxBytesPerSec, maxTilesPerReq, minReqIntervalMs, maxPatchLod);
        final int dimCount = in.readUnsignedByte();
        if (dimCount > Proto.MAX_DIM_ENTRIES) {
            throw new ProtoException("dim count " + dimCount + " above cap " + Proto.MAX_DIM_ENTRIES);
        }
        final List<HelloPolicyS2C.DimDescriptor> dims = new ArrayList<>(dimCount);
        for (int i = 0; i < dimCount; i++) {
            final String dimId = readUtf(in);
            final String dimType = readUtf(in);
            final int dimBits = in.readUnsignedByte();
            final long seed = in.readLong();
            dims.add(new HelloPolicyS2C.DimDescriptor(
                dimId, dimType,
                (dimBits & 1) != 0,
                (dimBits & 2) != 0,
                seed
            ));
        }
        return new HelloPolicyS2C(flags, worldId, worldgenVersion, budgets, dims);
    }

    private static MapViewReqC2S decodeMapViewReqC2S(final DataInputStream in, final int payloadLen) throws IOException, ProtoException {
        requireLen(payloadLen, Proto.MAX_C2S_PAYLOAD);
        final int reqId = in.readInt();
        final int dimIndex = in.readUnsignedByte();
        final int lod = in.readUnsignedByte();
        final int tileCount = in.readUnsignedByte();
        if (tileCount > Proto.MAX_TILES_PER_REQ) {
            throw new ProtoException("tile count " + tileCount + " above cap " + Proto.MAX_TILES_PER_REQ);
        }
        final List<MapViewReqC2S.TileReq> tiles = new ArrayList<>(tileCount);
        for (int i = 0; i < tileCount; i++) {
            final int tileX = in.readInt();
            final int tileZ = in.readInt();
            final long sinceRevision = in.readLong();
            tiles.add(new MapViewReqC2S.TileReq(tileX, tileZ, sinceRevision));
        }
        return new MapViewReqC2S(reqId, dimIndex, lod, tiles);
    }

    private static MapPatchS2C decodeMapPatchS2C(final DataInputStream in, final int payloadLen) throws IOException, ProtoException {
        requireLen(payloadLen, Proto.MAX_S2C_PAYLOAD);
        final int reqId = in.readInt();
        final int dimIndex = in.readUnsignedByte();
        final int lod = in.readUnsignedByte();
        final int tileX = in.readInt();
        final int tileZ = in.readInt();
        final int mode = in.readUnsignedByte();
        final long tileRevision = in.readLong();
        final byte[] presence = new byte[Proto.PATCH_PRESENCE_BYTES];
        in.readFully(presence);
        final byte[] body = readBoundedBytes(in, Proto.MAX_S2C_PAYLOAD);
        return new MapPatchS2C(reqId, dimIndex, lod, tileX, tileZ, mode, tileRevision, presence, body);
    }

    private static PolicyUpdateS2C decodePolicyUpdateS2C(final DataInputStream in) throws IOException, ProtoException {
        final int flagBits = in.readUnsignedByte();
        final HelloPolicyS2C.Flags flags = new HelloPolicyS2C.Flags(
            (flagBits & 1) != 0,
            (flagBits & 2) != 0,
            (flagBits & 4) != 0
        );
        final int maxBytesPerSec = in.readInt();
        final int maxTilesPerReq = in.readUnsignedShort();
        final int minReqIntervalMs = in.readUnsignedShort();
        final int maxPatchLod = in.readUnsignedByte();
        return new PolicyUpdateS2C(flags, new HelloPolicyS2C.Budgets(maxBytesPerSec, maxTilesPerReq, minReqIntervalMs, maxPatchLod));
    }

    private static ErrorS2C decodeErrorS2C(final DataInputStream in) throws IOException, ProtoException {
        final int code = in.readUnsignedByte();
        final String detail = readUtf(in);
        return new ErrorS2C(code, detail);
    }

    // ---- Low-level helpers (shared by encode and decode) ----

    /**
     * Writes a string as {@code u16 byteLength} + UTF-8 bytes. Refuses strings whose UTF-8 form
     * exceeds {@value Proto#MAX_UTF8_BYTES} bytes before touching the stream.
     */
    private static void writeUtf(final DataOutputStream out, final String s) throws IOException, ProtoException {
        if (s == null) {
            throw new ProtoException("null utf-8 field");
        }
        final byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > Proto.MAX_UTF8_BYTES) {
            throw new ProtoException("utf-8 field too long: " + bytes.length + " bytes");
        }
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    /** Reads a string written by {@link #writeUtf}; caps at {@value Proto#MAX_UTF8_BYTES} bytes. */
    private static String readUtf(final DataInputStream in) throws IOException, ProtoException {
        final int len = in.readUnsignedShort();
        if (len > Proto.MAX_UTF8_BYTES) {
            throw new ProtoException("utf-8 length " + len + " above cap " + Proto.MAX_UTF8_BYTES);
        }
        final byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Writes a byte blob as {@code u32 length} + bytes. The cap is the caller's responsibility:
     * encode passes {@link Proto#MAX_S2C_PAYLOAD} (or a smaller per-message cap) as the hard ceiling.
     */
    private static void writeBoundedBytes(final DataOutputStream out, final byte[] bytes, final int cap) throws IOException, ProtoException {
        if (bytes == null) {
            throw new ProtoException("null byte blob");
        }
        if (bytes.length > cap) {
            throw new ProtoException("byte blob too long: " + bytes.length + " bytes, cap " + cap);
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    /** Reads a byte blob written by {@link #writeBoundedBytes}; caps allocation at {@code cap}. */
    private static byte[] readBoundedBytes(final DataInputStream in, final int cap) throws IOException, ProtoException {
        final int len = in.readInt();
        if (len < 0 || len > cap) {
            throw new ProtoException("byte blob length " + len + " out of range [0, " + cap + "]");
        }
        final byte[] bytes = new byte[len];
        in.readFully(bytes);
        return bytes;
    }

    private static void requireLen(final int payloadLen, final int cap) throws ProtoException {
        if (payloadLen > cap) {
            throw new ProtoException("payload of " + payloadLen + " bytes exceeds cap " + cap);
        }
    }
}
