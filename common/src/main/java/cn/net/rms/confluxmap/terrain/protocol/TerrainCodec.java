package cn.net.rms.confluxmap.terrain.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TerrainCodec {
    private TerrainCodec() {
    }

    public static byte[] hello() throws IOException {
        return encode(out -> out.writeInt(TerrainWire.PROTOCOL_VERSION));
    }

    public static void requireHello(final byte[] payload) throws IOException {
        decode(payload, input -> {
            final int version = input.readInt();
            if (version != TerrainWire.PROTOCOL_VERSION) {
                throw new IOException("unsupported terrain protocol: " + version);
            }
            return null;
        });
    }

    public static byte[] view(
        final long sessionToken, final long generation, final int pivotY
    ) throws IOException {
        return view(
            sessionToken, generation, pivotY,
            Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE
        );
    }

    public static byte[] view(
        final long sessionToken,
        final long generation,
        final int pivotY,
        final int minChunkX,
        final int maxChunkX,
        final int minChunkZ,
        final int maxChunkZ
    ) throws IOException {
        return encode(out -> {
            out.writeLong(sessionToken);
            out.writeLong(generation);
            out.writeInt(pivotY);
            out.writeInt(minChunkX);
            out.writeInt(maxChunkX);
            out.writeInt(minChunkZ);
            out.writeInt(maxChunkZ);
        });
    }

    public static View decodeView(final byte[] payload) throws IOException {
        return decode(payload, in -> new View(
            in.readLong(), in.readLong(), in.readInt(),
            in.readInt(), in.readInt(), in.readInt(), in.readInt()
        ));
    }

    public static byte[] chunk(final EncodedChunk chunk) throws IOException {
        return encode(out -> {
            out.writeLong(chunk.sessionToken());
            out.writeLong(chunk.revision());
            out.writeInt(chunk.chunkX());
            out.writeInt(chunk.chunkZ());
            out.writeInt(chunk.minSectionY());
            out.writeInt(chunk.maxSectionY());
            out.writeByte(chunk.localPaletteMaxBits());
            out.writeByte(chunk.directPaletteBits());
            out.writeInt(chunk.airStateId());
            out.writeInt(chunk.sections().size());
            for (final EncodedSection section : chunk.sections()) {
                out.writeInt(section.sectionY());
                out.writeInt(section.blockStates().length);
                out.write(section.blockStates());
            }
        });
    }

    public static EncodedChunk decodeChunk(final byte[] payload) throws IOException {
        return decode(payload, in -> {
            final long session = in.readLong();
            final long revision = in.readLong();
            final int chunkX = in.readInt();
            final int chunkZ = in.readInt();
            final int minSectionY = in.readInt();
            final int maxSectionY = in.readInt();
            final int localBits = in.readUnsignedByte();
            final int directBits = in.readUnsignedByte();
            final int air = in.readInt();
            final int count = boundedCount(in.readInt(), 1024, "section");
            final List<EncodedSection> sections = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                final int sectionY = in.readInt();
                final int length = boundedCount(
                    in.readInt(), TerrainWire.MAX_FRAME_BYTES, "section payload"
                );
                final byte[] bytes = new byte[length];
                in.readFully(bytes);
                sections.add(new EncodedSection(sectionY, bytes));
            }
            return new EncodedChunk(
                session, revision, chunkX, chunkZ, minSectionY, maxSectionY,
                localBits, directBits, air, sections
            );
        });
    }

    public static byte[] delta(final TerrainDelta delta) throws IOException {
        return encode(out -> {
            out.writeLong(delta.sessionToken());
            out.writeLong(delta.revision());
            out.writeInt(delta.chunkX());
            out.writeInt(delta.chunkZ());
            out.writeByte(delta.localX());
            out.writeInt(delta.y());
            out.writeByte(delta.localZ());
            out.writeInt(delta.stateId());
        });
    }

    public static TerrainDelta decodeDelta(final byte[] payload) throws IOException {
        return decode(payload, in -> new TerrainDelta(
            in.readLong(), in.readLong(), in.readInt(), in.readInt(),
            in.readUnsignedByte(), in.readInt(), in.readUnsignedByte(), in.readInt()
        ));
    }

    public static byte[] chunkRef(
        final long sessionToken, final int chunkX, final int chunkZ
    ) throws IOException {
        return encode(out -> {
            out.writeLong(sessionToken);
            out.writeInt(chunkX);
            out.writeInt(chunkZ);
        });
    }

    public static ChunkRef decodeChunkRef(final byte[] payload) throws IOException {
        return decode(payload, in -> new ChunkRef(in.readLong(), in.readInt(), in.readInt()));
    }

    public static byte[] materials(final Map<Integer, MaterialDescriptor> materials)
        throws IOException {
        return encode(out -> {
            out.writeInt(materials.size());
            for (final Map.Entry<Integer, MaterialDescriptor> entry : materials.entrySet()) {
                out.writeInt(entry.getKey());
                out.writeBoolean(entry.getValue().openForFloorScan());
                out.writeBoolean(entry.getValue().overlayCandidate());
            }
        });
    }

    public static Map<Integer, MaterialDescriptor> decodeMaterials(final byte[] payload)
        throws IOException {
        return decode(payload, in -> {
            final int count = boundedCount(in.readInt(), 1 << 20, "material");
            final Map<Integer, MaterialDescriptor> result = new HashMap<>(count);
            for (int i = 0; i < count; i++) {
                result.put(in.readInt(), new MaterialDescriptor(in.readBoolean(), in.readBoolean()));
            }
            return result;
        });
    }

    public static byte[] materialRequest(final Set<Integer> stateIds) throws IOException {
        return encode(out -> {
            out.writeInt(stateIds.size());
            for (final int stateId : stateIds) {
                out.writeInt(stateId);
            }
        });
    }

    public static MaterialRequest decodeMaterialRequest(final byte[] payload) throws IOException {
        return decode(payload, in -> {
            final int count = boundedCount(in.readInt(), 1 << 20, "material request");
            final Set<Integer> result = new LinkedHashSet<>(count);
            for (int i = 0; i < count; i++) {
                result.add(in.readInt());
            }
            return new MaterialRequest(result);
        });
    }

    public static byte[] result(final TerrainResult result) throws IOException {
        return encode(out -> {
            out.writeLong(result.sessionToken());
            out.writeLong(result.generation());
            final CaveChunkResult value = result.result();
            out.writeInt(value.chunkX());
            out.writeInt(value.chunkZ());
            out.writeLong(value.revision());
            out.writeInt(value.pivotY());
            for (int i = 0; i < 256; i++) {
                out.writeShort(value.surfaceY()[i]);
                out.writeInt(value.floorStateId()[i]);
                out.writeInt(value.overlayStateId()[i]);
                out.writeBoolean(value.crossSection()[i]);
            }
        });
    }

    public static TerrainResult decodeResult(final byte[] payload) throws IOException {
        return decode(payload, in -> {
            final long session = in.readLong();
            final long generation = in.readLong();
            final int chunkX = in.readInt();
            final int chunkZ = in.readInt();
            final long revision = in.readLong();
            final int pivot = in.readInt();
            final short[] y = new short[256];
            final int[] floor = new int[256];
            final int[] overlay = new int[256];
            final boolean[] cross = new boolean[256];
            for (int i = 0; i < 256; i++) {
                y[i] = in.readShort();
                floor[i] = in.readInt();
                overlay[i] = in.readInt();
                cross[i] = in.readBoolean();
            }
            return new TerrainResult(
                session, generation,
                new CaveChunkResult(chunkX, chunkZ, revision, pivot, y, floor, overlay, cross)
            );
        });
    }

    public static byte[] error(final String message) throws IOException {
        final byte[] value = message.getBytes(StandardCharsets.UTF_8);
        return encode(out -> {
            out.writeInt(value.length);
            out.write(value);
        });
    }

    public static String decodeError(final byte[] payload) throws IOException {
        return decode(payload, in -> {
            final int length = boundedCount(in.readInt(), 8192, "error");
            final byte[] value = new byte[length];
            in.readFully(value);
            return new String(value, StandardCharsets.UTF_8);
        });
    }

    private static int boundedCount(final int value, final int maximum, final String name)
        throws IOException {
        if (value < 0 || value > maximum) {
            throw new IOException("invalid " + name + " count: " + value);
        }
        return value;
    }

    private static byte[] encode(final Encoder encoder) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(bytes);
        encoder.write(out);
        out.flush();
        return bytes.toByteArray();
    }

    private static <T> T decode(final byte[] bytes, final Decoder<T> decoder) throws IOException {
        final DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        final T result = decoder.read(in);
        if (in.available() != 0) {
            throw new IOException("trailing terrain message bytes");
        }
        return result;
    }

    @FunctionalInterface
    private interface Encoder {
        void write(DataOutputStream output) throws IOException;
    }

    @FunctionalInterface
    private interface Decoder<T> {
        T read(DataInputStream input) throws IOException;
    }

    public record View(
        long sessionToken,
        long generation,
        int pivotY,
        int minChunkX,
        int maxChunkX,
        int minChunkZ,
        int maxChunkZ
    ) {
        public boolean contains(final int chunkX, final int chunkZ) {
            return chunkX >= minChunkX && chunkX <= maxChunkX
                && chunkZ >= minChunkZ && chunkZ <= maxChunkZ;
        }
    }

    public record ChunkRef(long sessionToken, int chunkX, int chunkZ) {
    }
}
