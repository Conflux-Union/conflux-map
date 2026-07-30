package cn.net.rms.confluxmap.core.net;

import cn.net.rms.confluxmap.core.predict.FlatBaseline;
import cn.net.rms.confluxmap.core.predict.WorldPreset;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
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
 *   <li>Malformed UTF-8 and trailing bytes after the one expected message are rejected.</li>
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
            } else if (msg instanceof final FlatBaselineS2C m) {
                encodeFlatBaselineS2C(out, m);
            } else if (msg instanceof final LoadStateSubscribeC2S m) {
                encodeLoadStateSubscribeC2S(out, m);
            } else if (msg instanceof final LoadStateDeltaS2C m) {
                encodeLoadStateDeltaS2C(out, m);
            } else if (msg instanceof final MapSyncSubscribeC2S m) {
                encodeMapSyncSubscribeC2S(out, m);
            } else if (msg instanceof final MapInvalidateS2C m) {
                encodeMapInvalidateS2C(out, m);
            } else if (msg instanceof final MapRegionViewReqC2S m) {
                encodeMapRegionViewReqC2S(out, m);
            } else if (msg instanceof final MapRegionPatchS2C m) {
                encodeMapRegionPatchS2C(out, m);
            } else if (msg instanceof final MapRegionSyncSubscribeC2S m) {
                encodeMapRegionSyncSubscribeC2S(out, m);
            } else if (msg instanceof final MapRegionInvalidateS2C m) {
                encodeMapRegionInvalidateS2C(out, m);
            } else if (msg instanceof final MapCompatibilityS2C m) {
                encodeMapCompatibilityS2C(out, m);
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
            || typeId == Proto.MSG_ERROR_S2C
            || typeId == Proto.MSG_FLAT_BASELINE_S2C
            || typeId == Proto.MSG_LOAD_STATE_DELTA_S2C
            || typeId == Proto.MSG_MAP_INVALIDATE_S2C
            || typeId == Proto.MSG_MAP_REGION_PATCH_S2C
            || typeId == Proto.MSG_MAP_REGION_INVALIDATE_S2C
            || typeId == Proto.MSG_MAP_COMPATIBILITY_S2C;
    }

    private static void encodeHelloC2S(final DataOutputStream out, final HelloC2S m) throws IOException, ProtoException {
        writeUtf(out, m.modVersion());
        writeUtf(out, m.predictorVersion());
    }

    private static void encodeMapCompatibilityS2C(
        final DataOutputStream out, final MapCompatibilityS2C m
    ) throws IOException, ProtoException {
        if (m.correctionMode() < MapCompatibilityS2C.MODE_RESIDUAL
            || m.correctionMode() > MapCompatibilityS2C.MODE_DISABLED
            || m.reasonCode() < MapCompatibilityS2C.REASON_NONE
            || m.reasonCode() > MapCompatibilityS2C.REASON_NO_COMMON_WIRE) {
            throw new ProtoException("invalid map compatibility selection");
        }
        requireUnsignedByte("negotiation version", m.negotiationVersion());
        requireUnsignedShort("protocol major", m.protocolMajor());
        requireUnsignedShort("protocol minor", m.protocolMinor());
        requireUnsignedByte("patch codec version", m.patchCodecVersion());
        requireUnsignedByte("region codec version", m.regionCodecVersion());
        requireUnsignedByte("correction mode", m.correctionMode());
        requireUnsignedByte("compatibility reason", m.reasonCode());
        out.writeByte(m.negotiationVersion());
        writeUtf(out, m.serverModVersion());
        out.writeShort(m.protocolMajor());
        out.writeShort(m.protocolMinor());
        out.writeByte(m.patchCodecVersion());
        out.writeByte(m.regionCodecVersion());
        writeUtf(out, m.serverPredictorVersion());
        out.writeByte(m.correctionMode());
        out.writeByte(m.reasonCode());
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
        if (f.biomeMapForbidden()) {
            flagBits |= 4;
        }
        if (f.chunkLoadStateEnabled()) {
            flagBits |= 8;
        }
        if (f.entityRadarForbidden()) {
            flagBits |= 16;
        }
        if (f.correctionInvalidationEnabled()) {
            flagBits |= 32;
        }
        if (f.chunkRangeCorrectionEnabled()) {
            flagBits |= 64;
        }
        if (f.structureSearchForbidden()) {
            flagBits |= 128;
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
            // Bits 2-4 carry the recognized generator preset; a pre-preset client masks them away.
            dimBits |= (d.preset() == null ? WorldPreset.DEFAULT : d.preset()).wireId() << 2;
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
        if (m.presence() == null || m.presence().length != Proto.PATCH_PRESENCE_BYTES) {
            throw new ProtoException(
                "presence bitmap must be " + Proto.PATCH_PRESENCE_BYTES + " bytes, got "
                    + (m.presence() == null ? "null" : m.presence().length)
            );
        }
        out.write(m.presence());
        if (m.structures().size() > 255) {
            throw new ProtoException("too many structure entries");
        }
        out.writeByte(m.structures().size());
        for (final MapPatchS2C.StructureEntry entry : m.structures()) {
            out.writeByte(entry.structType());
            out.writeInt(entry.blockX());
            out.writeInt(entry.blockZ());
            out.writeByte(entry.state());
        }
        writeBoundedBytes(out, m.body(), PatchCodec.MAX_COMPRESSED_BYTES);
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
        if (f.biomeMapForbidden()) {
            flagBits |= 4;
        }
        if (f.chunkLoadStateEnabled()) {
            flagBits |= 8;
        }
        if (f.entityRadarForbidden()) {
            flagBits |= 16;
        }
        if (f.correctionInvalidationEnabled()) {
            flagBits |= 32;
        }
        if (f.chunkRangeCorrectionEnabled()) {
            flagBits |= 64;
        }
        if (f.structureSearchForbidden()) {
            flagBits |= 128;
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

    private static void encodeFlatBaselineS2C(final DataOutputStream out, final FlatBaselineS2C m) throws IOException, ProtoException {
        final List<FlatBaselineS2C.Entry> entries = m.entries();
        if (entries.isEmpty() || entries.size() > Proto.MAX_DIM_ENTRIES) {
            throw new ProtoException("flat baseline entry count out of range: " + entries.size());
        }
        out.writeByte(entries.size());
        for (final FlatBaselineS2C.Entry entry : entries) {
            final FlatBaseline b = entry.baseline();
            out.writeByte(entry.dimIndex());
            out.writeByte(b.biomeId());
            out.writeShort(b.surfaceY());
            out.writeByte(b.kind());
            out.writeByte(b.mapColorId());
            out.writeByte(b.fluidDepth());
        }
    }

    private static void encodeLoadStateSubscribeC2S(
        final DataOutputStream out,
        final LoadStateSubscribeC2S m
    ) throws IOException, ProtoException {
        if (m.dimIndex() < 0 || m.dimIndex() > 0xFF) {
            throw new ProtoException("load-state dim index out of range: " + m.dimIndex());
        }
        validateLoadStateBounds(m);
        out.writeInt(m.subscriptionId());
        out.writeByte(m.dimIndex());
        out.writeByte(m.active() ? 1 : 0);
        out.writeInt(m.minChunkX());
        out.writeInt(m.minChunkZ());
        out.writeInt(m.maxChunkX());
        out.writeInt(m.maxChunkZ());
    }

    private static void encodeLoadStateDeltaS2C(
        final DataOutputStream out,
        final LoadStateDeltaS2C m
    ) throws IOException, ProtoException {
        if (m.entries().size() > Proto.MAX_LOAD_STATE_ENTRIES) {
            throw new ProtoException("too many load-state entries: " + m.entries().size());
        }
        out.writeInt(m.subscriptionId());
        int flags = 0;
        if (m.reset()) {
            flags |= 1;
        }
        if (m.complete()) {
            flags |= 2;
        }
        out.writeByte(flags);
        out.writeShort(m.entries().size());
        for (final LoadStateDeltaS2C.Entry entry : m.entries()) {
            validateLoadStateEntry(entry);
            out.writeInt(entry.chunkX());
            out.writeInt(entry.chunkZ());
            out.writeByte(entry.level());
            out.writeByte(entry.band().wireId());
        }
    }

    private static void encodeMapSyncSubscribeC2S(
        final DataOutputStream out, final MapSyncSubscribeC2S m
    ) throws IOException, ProtoException {
        validateMapSyncSubscription(m);
        out.writeByte(m.dimIndex());
        out.writeByte(m.lod());
        out.writeByte(m.active() ? 1 : 0);
        out.writeInt(m.minTileX());
        out.writeInt(m.maxTileX());
        out.writeInt(m.minTileZ());
        out.writeInt(m.maxTileZ());
    }

    private static void encodeMapInvalidateS2C(
        final DataOutputStream out, final MapInvalidateS2C m
    ) throws IOException, ProtoException {
        if (m.dimIndex() < 0 || m.dimIndex() >= Proto.MAX_DIM_ENTRIES) {
            throw new ProtoException("map invalidation dimension out of range: " + m.dimIndex());
        }
        if (m.lod() < 0 || m.lod() > cn.net.rms.confluxmap.core.util.TileMath.MAX_LOD) {
            throw new ProtoException("map invalidation LOD out of range: " + m.lod());
        }
        if (m.tiles().isEmpty() || m.tiles().size() > Proto.MAX_MAP_INVALIDATION_TILES) {
            throw new ProtoException("map invalidation tile count out of range: " + m.tiles().size());
        }
        out.writeByte(m.dimIndex());
        out.writeByte(m.lod());
        out.writeShort(m.tiles().size());
        for (final MapInvalidateS2C.Tile tile : m.tiles()) {
            if (tile == null) {
                throw new ProtoException("null map invalidation tile");
            }
            out.writeInt(tile.tileX());
            out.writeInt(tile.tileZ());
        }
    }

    private static void encodeMapRegionViewReqC2S(
        final DataOutputStream out, final MapRegionViewReqC2S m
    ) throws IOException, ProtoException {
        validateRegionHeader(m.dimIndex(), m.lod(), "region page request");
        out.writeInt(m.reqId());
        out.writeByte(m.dimIndex());
        out.writeByte(m.lod());
        if (m.regions().isEmpty() || m.regions().size() > Proto.MAX_REGION_PAGES_PER_REQ) {
            throw new ProtoException("region page request count out of range: " + m.regions().size());
        }
        out.writeByte(m.regions().size());
        for (final MapRegionViewReqC2S.RegionReq region : m.regions()) {
            if (region == null) {
                throw new ProtoException("null region page request");
            }
            region.slice();
            out.writeInt(region.regionX());
            out.writeInt(region.regionZ());
            out.writeByte(region.minLocalChunkX());
            out.writeByte(region.minLocalChunkZ());
            out.writeByte(region.maxLocalChunkX());
            out.writeByte(region.maxLocalChunkZ());
            out.writeLong(region.sinceRevision());
        }
    }

    private static void encodeMapRegionPatchS2C(
        final DataOutputStream out, final MapRegionPatchS2C m
    ) throws IOException, ProtoException {
        m.slice();
        validateRegionHeader(m.dimIndex(), m.lod(), "region patch");
        validateRegionPatchBody(m.mode(), m.body());
        out.writeInt(m.reqId());
        out.writeByte(m.dimIndex());
        out.writeByte(m.lod());
        out.writeInt(m.regionX());
        out.writeInt(m.regionZ());
        out.writeByte(m.minLocalChunkX());
        out.writeByte(m.minLocalChunkZ());
        out.writeByte(m.maxLocalChunkX());
        out.writeByte(m.maxLocalChunkZ());
        out.writeByte(m.mode());
        out.writeLong(m.regionRevision());
        writeBoundedBytes(out, m.body(), ChunkPatchCodec.MAX_COMPRESSED_BYTES);
    }

    private static void encodeMapRegionSyncSubscribeC2S(
        final DataOutputStream out, final MapRegionSyncSubscribeC2S m
    ) throws IOException, ProtoException {
        validateRegionSyncBounds(m);
        out.writeByte(m.dimIndex());
        out.writeByte(m.lod());
        out.writeByte(m.active() ? 1 : 0);
        out.writeInt(m.minChunkX());
        out.writeInt(m.maxChunkX());
        out.writeInt(m.minChunkZ());
        out.writeInt(m.maxChunkZ());
    }

    private static void encodeMapRegionInvalidateS2C(
        final DataOutputStream out, final MapRegionInvalidateS2C m
    ) throws IOException, ProtoException {
        validateRegionHeader(m.dimIndex(), m.lod(), "region invalidation");
        if (m.regions().isEmpty() || m.regions().size() > Proto.MAX_REGION_INVALIDATIONS) {
            throw new ProtoException("region invalidation count out of range: " + m.regions().size());
        }
        out.writeByte(m.dimIndex());
        out.writeByte(m.lod());
        out.writeShort(m.regions().size());
        for (final MapRegionInvalidateS2C.Region region : m.regions()) {
            if (region == null) {
                throw new ProtoException("null region invalidation");
            }
            out.writeInt(region.regionX());
            out.writeInt(region.regionZ());
        }
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
        requireLen(payload.length, capForType(typeId));
        final DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload, 1, payload.length - 1));
        try {
            final Message message = switch (typeId) {
                case Proto.MSG_HELLO_C2S -> decodeHelloC2S(in);
                case Proto.MSG_HELLO_POLICY_S2C -> decodeHelloPolicyS2C(in);
                case Proto.MSG_MAP_VIEW_REQ_C2S -> decodeMapViewReqC2S(in);
                case Proto.MSG_MAP_PATCH_S2C -> decodeMapPatchS2C(in);
                case Proto.MSG_POLICY_UPDATE_S2C -> decodePolicyUpdateS2C(in);
                case Proto.MSG_ERROR_S2C -> decodeErrorS2C(in);
                case Proto.MSG_FLAT_BASELINE_S2C -> decodeFlatBaselineS2C(in);
                case Proto.MSG_LOAD_STATE_SUBSCRIBE_C2S -> decodeLoadStateSubscribeC2S(in);
                case Proto.MSG_LOAD_STATE_DELTA_S2C -> decodeLoadStateDeltaS2C(in);
                case Proto.MSG_MAP_SYNC_SUBSCRIBE_C2S -> decodeMapSyncSubscribeC2S(in);
                case Proto.MSG_MAP_INVALIDATE_S2C -> decodeMapInvalidateS2C(in);
                case Proto.MSG_MAP_REGION_VIEW_REQ_C2S -> decodeMapRegionViewReqC2S(in);
                case Proto.MSG_MAP_REGION_PATCH_S2C -> decodeMapRegionPatchS2C(in);
                case Proto.MSG_MAP_REGION_SYNC_SUBSCRIBE_C2S -> decodeMapRegionSyncSubscribeC2S(in);
                case Proto.MSG_MAP_REGION_INVALIDATE_S2C -> decodeMapRegionInvalidateS2C(in);
                case Proto.MSG_MAP_COMPATIBILITY_S2C -> decodeMapCompatibilityS2C(in);
                default -> throw new ProtoException("unhandled message type id: 0x" + Integer.toHexString(typeId));
            };
            if (in.available() != 0) {
                throw new ProtoException("trailing bytes after message: " + in.available());
            }
            return message;
        } catch (final ProtoException e) {
            throw e;
        } catch (final IOException e) {
            // DataInputStream on a ByteArrayInputStream throws EOFException when truncated;
            // anything else means the input is hostile but in a shape we didn't anticipate.
            throw new ProtoException("decode io error: " + e.getMessage(), e);
        }
    }

    private static HelloC2S decodeHelloC2S(final DataInputStream in) throws IOException, ProtoException {
        final String modVersion = readUtf(in);
        final String predictorVersion = readUtf(in);
        return new HelloC2S(modVersion, predictorVersion);
    }

    private static MapCompatibilityS2C decodeMapCompatibilityS2C(
        final DataInputStream in
    ) throws IOException, ProtoException {
        final MapCompatibilityS2C message = new MapCompatibilityS2C(
            in.readUnsignedByte(),
            readUtf(in),
            in.readUnsignedShort(),
            in.readUnsignedShort(),
            in.readUnsignedByte(),
            in.readUnsignedByte(),
            readUtf(in),
            in.readUnsignedByte(),
            in.readUnsignedByte()
        );
        if (message.correctionMode() < MapCompatibilityS2C.MODE_RESIDUAL
            || message.correctionMode() > MapCompatibilityS2C.MODE_DISABLED
            || message.reasonCode() < MapCompatibilityS2C.REASON_NONE
            || message.reasonCode() > MapCompatibilityS2C.REASON_NO_COMMON_WIRE) {
            throw new ProtoException("invalid map compatibility selection");
        }
        return message;
    }

    private static HelloPolicyS2C decodeHelloPolicyS2C(final DataInputStream in) throws IOException, ProtoException {
        final int flagBits = in.readUnsignedByte();
        final HelloPolicyS2C.Flags flags = new HelloPolicyS2C.Flags(
            (flagBits & 1) != 0,
            (flagBits & 2) != 0,
            (flagBits & 4) != 0,
            (flagBits & 8) != 0,
            (flagBits & 16) != 0,
            (flagBits & 32) != 0,
            (flagBits & 64) != 0,
            (flagBits & 128) != 0
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
                seed,
                WorldPreset.fromWireId((dimBits >> 2) & 0x7)
            ));
        }
        return new HelloPolicyS2C(flags, worldId, worldgenVersion, budgets, dims);
    }

    private static MapViewReqC2S decodeMapViewReqC2S(final DataInputStream in) throws IOException, ProtoException {
        final int reqId = in.readInt();
        final int dimIndex = in.readUnsignedByte();
        final int lod = in.readUnsignedByte();
        validateRegionHeader(dimIndex, lod, "region page request");
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

    private static MapPatchS2C decodeMapPatchS2C(final DataInputStream in) throws IOException, ProtoException {
        final int reqId = in.readInt();
        final int dimIndex = in.readUnsignedByte();
        final int lod = in.readUnsignedByte();
        final int tileX = in.readInt();
        final int tileZ = in.readInt();
        final int mode = in.readUnsignedByte();
        final long tileRevision = in.readLong();
        final byte[] presence = new byte[Proto.PATCH_PRESENCE_BYTES];
        in.readFully(presence);
        final int structCount = in.readUnsignedByte();
        final List<MapPatchS2C.StructureEntry> structures = new ArrayList<>(structCount);
        for (int i = 0; i < structCount; i++) {
            structures.add(new MapPatchS2C.StructureEntry(in.readUnsignedByte(), in.readInt(), in.readInt(), in.readUnsignedByte()));
        }
        final byte[] body = readBoundedBytes(in, PatchCodec.MAX_COMPRESSED_BYTES);
        return new MapPatchS2C(reqId, dimIndex, lod, tileX, tileZ, mode, tileRevision, presence, body, structures);
    }

    private static PolicyUpdateS2C decodePolicyUpdateS2C(final DataInputStream in) throws IOException, ProtoException {
        final int flagBits = in.readUnsignedByte();
        final HelloPolicyS2C.Flags flags = new HelloPolicyS2C.Flags(
            (flagBits & 1) != 0,
            (flagBits & 2) != 0,
            (flagBits & 4) != 0,
            (flagBits & 8) != 0,
            (flagBits & 16) != 0,
            (flagBits & 32) != 0,
            (flagBits & 64) != 0,
            (flagBits & 128) != 0
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

    private static FlatBaselineS2C decodeFlatBaselineS2C(final DataInputStream in) throws IOException, ProtoException {
        final int count = in.readUnsignedByte();
        if (count == 0 || count > Proto.MAX_DIM_ENTRIES) {
            throw new ProtoException("flat baseline entry count out of range: " + count);
        }
        final List<FlatBaselineS2C.Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final int dimIndex = in.readUnsignedByte();
            final int biomeId = in.readUnsignedByte();
            final int surfaceY = in.readShort();
            final int kind = in.readUnsignedByte();
            final int mapColorId = in.readUnsignedByte();
            final int fluidDepth = in.readUnsignedByte();
            entries.add(new FlatBaselineS2C.Entry(
                dimIndex, new FlatBaseline(biomeId, surfaceY, kind, mapColorId, fluidDepth)
            ));
        }
        return new FlatBaselineS2C(entries);
    }

    private static LoadStateSubscribeC2S decodeLoadStateSubscribeC2S(
        final DataInputStream in
    ) throws IOException, ProtoException {
        final int subscriptionId = in.readInt();
        final int dimIndex = in.readUnsignedByte();
        final int activeByte = in.readUnsignedByte();
        if (activeByte > 1) {
            throw new ProtoException("invalid load-state active flag: " + activeByte);
        }
        final LoadStateSubscribeC2S message = new LoadStateSubscribeC2S(
            subscriptionId,
            dimIndex,
            activeByte == 1,
            in.readInt(),
            in.readInt(),
            in.readInt(),
            in.readInt()
        );
        validateLoadStateBounds(message);
        return message;
    }

    private static LoadStateDeltaS2C decodeLoadStateDeltaS2C(
        final DataInputStream in
    ) throws IOException, ProtoException {
        final int subscriptionId = in.readInt();
        final int flags = in.readUnsignedByte();
        if ((flags & ~3) != 0) {
            throw new ProtoException("unknown load-state delta flags: " + flags);
        }
        final int count = in.readUnsignedShort();
        if (count > Proto.MAX_LOAD_STATE_ENTRIES) {
            throw new ProtoException("load-state entry count " + count + " above cap " + Proto.MAX_LOAD_STATE_ENTRIES);
        }
        final List<LoadStateDeltaS2C.Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final LoadStateDeltaS2C.Entry entry = new LoadStateDeltaS2C.Entry(
                in.readInt(),
                in.readInt(),
                in.readUnsignedByte(),
                ChunkLoadBand.fromWireId(in.readUnsignedByte())
            );
            validateLoadStateEntry(entry);
            entries.add(entry);
        }
        return new LoadStateDeltaS2C(
            subscriptionId,
            (flags & 1) != 0,
            (flags & 2) != 0,
            entries
        );
    }

    private static MapSyncSubscribeC2S decodeMapSyncSubscribeC2S(
        final DataInputStream in
    ) throws IOException, ProtoException {
        final int dimIndex = in.readUnsignedByte();
        final int lod = in.readUnsignedByte();
        final int activeByte = in.readUnsignedByte();
        if (activeByte > 1) {
            throw new ProtoException("invalid map-sync active flag: " + activeByte);
        }
        final MapSyncSubscribeC2S message = new MapSyncSubscribeC2S(
            dimIndex, lod, activeByte == 1,
            in.readInt(), in.readInt(), in.readInt(), in.readInt()
        );
        validateMapSyncSubscription(message);
        return message;
    }

    private static MapInvalidateS2C decodeMapInvalidateS2C(
        final DataInputStream in
    ) throws IOException, ProtoException {
        final int dimIndex = in.readUnsignedByte();
        final int lod = in.readUnsignedByte();
        if (dimIndex >= Proto.MAX_DIM_ENTRIES) {
            throw new ProtoException("map invalidation dimension out of range: " + dimIndex);
        }
        if (lod > cn.net.rms.confluxmap.core.util.TileMath.MAX_LOD) {
            throw new ProtoException("map invalidation LOD out of range: " + lod);
        }
        final int count = in.readUnsignedShort();
        if (count == 0 || count > Proto.MAX_MAP_INVALIDATION_TILES) {
            throw new ProtoException("map invalidation tile count out of range: " + count);
        }
        final List<MapInvalidateS2C.Tile> tiles = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            tiles.add(new MapInvalidateS2C.Tile(in.readInt(), in.readInt()));
        }
        return new MapInvalidateS2C(dimIndex, lod, tiles);
    }

    private static MapRegionViewReqC2S decodeMapRegionViewReqC2S(
        final DataInputStream in
    ) throws IOException, ProtoException {
        final int reqId = in.readInt();
        final int dimIndex = in.readUnsignedByte();
        final int lod = in.readUnsignedByte();
        validateRegionHeader(dimIndex, lod, "region page request");
        final int count = in.readUnsignedByte();
        if (count == 0 || count > Proto.MAX_REGION_PAGES_PER_REQ) {
            throw new ProtoException("region page request count out of range: " + count);
        }
        final List<MapRegionViewReqC2S.RegionReq> regions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            try {
                regions.add(new MapRegionViewReqC2S.RegionReq(
                    in.readInt(), in.readInt(),
                    in.readUnsignedByte(), in.readUnsignedByte(),
                    in.readUnsignedByte(), in.readUnsignedByte(),
                    in.readLong()
                ));
            } catch (final IllegalArgumentException e) {
                throw new ProtoException("invalid region page bounds", e);
            }
        }
        return new MapRegionViewReqC2S(reqId, dimIndex, lod, regions);
    }

    private static MapRegionPatchS2C decodeMapRegionPatchS2C(
        final DataInputStream in
    ) throws IOException, ProtoException {
        try {
            final MapRegionPatchS2C message = new MapRegionPatchS2C(
                in.readInt(), in.readUnsignedByte(), in.readUnsignedByte(),
                in.readInt(), in.readInt(),
                in.readUnsignedByte(), in.readUnsignedByte(),
                in.readUnsignedByte(), in.readUnsignedByte(),
                in.readUnsignedByte(), in.readLong(),
                readBoundedBytes(in, ChunkPatchCodec.MAX_COMPRESSED_BYTES)
            );
            validateRegionHeader(message.dimIndex(), message.lod(), "region patch");
            validateRegionPatchBody(message.mode(), message.body());
            return message;
        } catch (final IllegalArgumentException e) {
            throw new ProtoException("invalid region patch bounds", e);
        }
    }

    private static MapRegionSyncSubscribeC2S decodeMapRegionSyncSubscribeC2S(
        final DataInputStream in
    ) throws IOException, ProtoException {
        final int dimIndex = in.readUnsignedByte();
        final int lod = in.readUnsignedByte();
        final int activeByte = in.readUnsignedByte();
        if (activeByte > 1) {
            throw new ProtoException("invalid region-sync active flag: " + activeByte);
        }
        final MapRegionSyncSubscribeC2S message = new MapRegionSyncSubscribeC2S(
            dimIndex, lod, activeByte == 1,
            in.readInt(), in.readInt(), in.readInt(), in.readInt()
        );
        validateRegionSyncBounds(message);
        return message;
    }

    private static MapRegionInvalidateS2C decodeMapRegionInvalidateS2C(
        final DataInputStream in
    ) throws IOException, ProtoException {
        final int dimIndex = in.readUnsignedByte();
        final int lod = in.readUnsignedByte();
        if (dimIndex >= Proto.MAX_DIM_ENTRIES
            || lod > cn.net.rms.confluxmap.core.util.TileMath.MAX_LOD) {
            throw new ProtoException("region invalidation header out of range");
        }
        final int count = in.readUnsignedShort();
        if (count == 0 || count > Proto.MAX_REGION_INVALIDATIONS) {
            throw new ProtoException("region invalidation count out of range: " + count);
        }
        final List<MapRegionInvalidateS2C.Region> regions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            regions.add(new MapRegionInvalidateS2C.Region(in.readInt(), in.readInt()));
        }
        return new MapRegionInvalidateS2C(dimIndex, lod, regions);
    }

    private static void validateLoadStateBounds(final LoadStateSubscribeC2S m) throws ProtoException {
        if (!m.active()) {
            return;
        }
        if (m.minChunkX() > m.maxChunkX() || m.minChunkZ() > m.maxChunkZ()) {
            throw new ProtoException("load-state viewport bounds are inverted");
        }
        final long width = (long) m.maxChunkX() - m.minChunkX() + 1L;
        final long height = (long) m.maxChunkZ() - m.minChunkZ() + 1L;
        if (width > Proto.MAX_LOAD_STATE_SPAN || height > Proto.MAX_LOAD_STATE_SPAN) {
            throw new ProtoException("load-state viewport exceeds " + Proto.MAX_LOAD_STATE_SPAN + " chunks per axis");
        }
    }

    private static void validateLoadStateEntry(final LoadStateDeltaS2C.Entry entry) throws ProtoException {
        if (entry.band() == null) {
            throw new ProtoException("null chunk load band");
        }
        if (entry.level() < 0 || entry.level() > 0xFF) {
            throw new ProtoException("chunk ticket level out of range: " + entry.level());
        }
        final boolean unloaded = entry.band() == ChunkLoadBand.UNLOADED;
        if (unloaded != (entry.level() == Proto.LOAD_STATE_UNLOADED_LEVEL)) {
            throw new ProtoException("unloaded band and level sentinel disagree");
        }
        if (!unloaded && ChunkLoadBand.fromTicketLevel(entry.level()) != entry.band()) {
            throw new ProtoException("chunk ticket level and load band disagree");
        }
    }

    private static void validateMapSyncSubscription(final MapSyncSubscribeC2S m) throws ProtoException {
        if (m.dimIndex() < 0 || m.dimIndex() >= Proto.MAX_DIM_ENTRIES) {
            throw new ProtoException("map-sync subscription dimension out of range: " + m.dimIndex());
        }
        if (m.lod() < 0 || m.lod() > cn.net.rms.confluxmap.core.util.TileMath.MAX_LOD) {
            throw new ProtoException("map-sync subscription LOD out of range: " + m.lod());
        }
        if (!m.active()) {
            return;
        }
        if (m.minTileX() > m.maxTileX() || m.minTileZ() > m.maxTileZ()) {
            throw new ProtoException("map-sync viewport bounds are inverted");
        }
        final long width = (long) m.maxTileX() - m.minTileX() + 1L;
        final long height = (long) m.maxTileZ() - m.minTileZ() + 1L;
        if (width > Proto.MAX_MAP_SYNC_VIEW_TILES
            || height > Proto.MAX_MAP_SYNC_VIEW_TILES
            || width * height > Proto.MAX_MAP_SYNC_VIEW_TILES) {
            throw new ProtoException("map-sync viewport exceeds " + Proto.MAX_MAP_SYNC_VIEW_TILES + " tiles");
        }
    }

    private static void validateRegionSyncBounds(
        final MapRegionSyncSubscribeC2S m
    ) throws ProtoException {
        if (m.dimIndex() < 0 || m.dimIndex() >= Proto.MAX_DIM_ENTRIES) {
            throw new ProtoException("region-sync subscription dimension out of range: " + m.dimIndex());
        }
        if (m.lod() < 0 || m.lod() > cn.net.rms.confluxmap.core.util.TileMath.MAX_LOD) {
            throw new ProtoException("region-sync subscription LOD out of range: " + m.lod());
        }
        if (!m.active()) {
            return;
        }
        if (m.minChunkX() > m.maxChunkX() || m.minChunkZ() > m.maxChunkZ()) {
            throw new ProtoException("region-sync viewport bounds are inverted");
        }
        final long width = (long) m.maxChunkX() - m.minChunkX() + 1L;
        final long height = (long) m.maxChunkZ() - m.minChunkZ() + 1L;
        if (width > Proto.MAX_REGION_SYNC_SPAN_CHUNKS
            || height > Proto.MAX_REGION_SYNC_SPAN_CHUNKS) {
            throw new ProtoException(
                "region-sync viewport exceeds " + Proto.MAX_REGION_SYNC_SPAN_CHUNKS + " chunks per axis"
            );
        }
    }

    private static void validateRegionHeader(
        final int dimIndex, final int lod, final String label
    ) throws ProtoException {
        if (dimIndex < 0 || dimIndex >= Proto.MAX_DIM_ENTRIES) {
            throw new ProtoException(label + " dimension out of range: " + dimIndex);
        }
        if (lod < 0 || lod > cn.net.rms.confluxmap.core.util.TileMath.MAX_LOD) {
            throw new ProtoException(label + " LOD out of range: " + lod);
        }
    }

    private static void validateRegionPatchBody(
        final int mode, final byte[] body
    ) throws ProtoException {
        final int length = body == null ? -1 : body.length;
        if (mode == Proto.PATCH_MODE_UNCHANGED || mode == Proto.PATCH_MODE_UNAVAILABLE) {
            if (length != 0) {
                throw new ProtoException("bodyless region patch mode contains a body");
            }
            return;
        }
        if (mode != Proto.PATCH_MODE_RESIDUAL && mode != Proto.PATCH_MODE_ABSOLUTE) {
            throw new ProtoException("unsupported region patch mode " + mode);
        }
        if (length <= 0) {
            throw new ProtoException("region correction patch body is empty");
        }
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
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (final CharacterCodingException e) {
            throw new ProtoException("invalid utf-8", e);
        }
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

    private static void requireUnsignedByte(final String field, final int value) throws ProtoException {
        if (value < 0 || value > 0xFF) {
            throw new ProtoException(field + " out of range: " + value);
        }
    }

    private static void requireUnsignedShort(final String field, final int value) throws ProtoException {
        if (value < 0 || value > 0xFFFF) {
            throw new ProtoException(field + " out of range: " + value);
        }
    }
}
