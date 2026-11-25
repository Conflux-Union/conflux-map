package cn.net.rms.confluxmap.core.net;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;

/**
 * The wire primitives shared by {@link PatchCodec} and {@link ChunkPatchCodec}: zigzag
 * varints, byte planes, the material table, and the deflate wrapper. Both codecs use the
 * same primitive layer; the format layouts, version thresholds and compression caps stay
 * with each codec and are passed in by its callers.
 */
final class PatchWire {
    private static final int MAX_DELTA_HEIGHT_BYTES = 3;

    private PatchWire() {
    }

    /** Per-sample field values read in the shared plane order of both patch formats. */
    record ValuePlanes(
        int[] biomes,
        int[] surfaceYs,
        int[] kinds,
        int[] mapColors,
        int[] fluidDepths,
        int[] floorMapColors,
        String[] materialIds,
        String[] floorMaterialIds,
        String[] overlayMaterialIds
    ) {
    }

    static void writeZigzagVarLong(final DataOutputStream out, final long value) throws IOException {
        long encoded = (value << 1) ^ (value >> 63);
        while ((encoded & ~0x7FL) != 0L) {
            out.writeByte((int) (encoded & 0x7F) | 0x80);
            encoded >>>= 7;
        }
        out.writeByte((int) encoded);
    }

    static long readZigzagVarLong(final DataInputStream in) throws IOException, ProtoException {
        long encoded = 0L;
        for (int shift = 0; shift < 70; shift += 7) {
            final int next = in.readUnsignedByte();
            if (shift == 63 && (next & 0xFE) != 0) {
                throw new ProtoException("overlong source revision delta");
            }
            encoded |= (long) (next & 0x7F) << shift;
            if ((next & 0x80) == 0) {
                return (encoded >>> 1) ^ -(encoded & 1L);
            }
        }
        throw new ProtoException("overlong source revision delta");
    }

    static void writeZigzagVarint(final DataOutputStream out, final int value) throws IOException {
        writeUnsignedVarint(out, (value << 1) ^ (value >> 31));
    }

    static int readZigzagVarint(final DataInputStream in) throws IOException, ProtoException {
        final int encoded = readUnsignedVarint(in, MAX_DELTA_HEIGHT_BYTES);
        return (encoded >>> 1) ^ -(encoded & 1);
    }

    static void writeUnsignedVarint(final DataOutputStream out, int value) throws IOException {
        do {
            final int next = value & 0x7F;
            value >>>= 7;
            out.writeByte(value == 0 ? next : next | 0x80);
        } while (value != 0);
    }

    static int readUnsignedVarint(
        final DataInputStream in, final int maxBytes
    ) throws IOException, ProtoException {
        int value = 0;
        for (int byteIndex = 0; byteIndex < maxBytes; byteIndex++) {
            final int next = in.readUnsignedByte();
            value |= (next & 0x7F) << (byteIndex * 7);
            if ((next & 0x80) == 0) {
                return value;
            }
        }
        throw new ProtoException("overlong unsigned varint");
    }

    static int unsignedVarintBytes(int value) {
        int bytes = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            bytes++;
        }
        return bytes;
    }

    static int[] readUnsignedBytePlane(final DataInputStream in, final int count) throws IOException {
        final int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            values[i] = in.readUnsignedByte();
        }
        return values;
    }

    /**
     * Reads the value planes both codecs share, in their common wire order; the caller
     * derives the material-plane thresholds from its format version.
     */
    static ValuePlanes readValuePlanes(
        final DataInputStream in, final int count, final boolean materials, final boolean overlays
    ) throws IOException, ProtoException {
        final int[] biomes = readUnsignedBytePlane(in, count);
        final int[] surfaceYs = new int[count];
        int previousY = 0;
        for (int i = 0; i < count; i++) {
            previousY += readZigzagVarint(in);
            if (previousY < Short.MIN_VALUE || previousY > Short.MAX_VALUE) {
                throw new ProtoException("surface height outside i16: " + previousY);
            }
            surfaceYs[i] = previousY;
        }
        final int[] kinds = readUnsignedBytePlane(in, count);
        final int[] mapColors = readUnsignedBytePlane(in, count);
        final int[] fluidDepths = readUnsignedBytePlane(in, count);
        final int[] floorMapColors = readUnsignedBytePlane(in, count);
        final String[] materialIds = new String[count];
        final String[] floorMaterialIds = new String[count];
        final String[] overlayMaterialIds = new String[count];
        java.util.Arrays.fill(materialIds, "");
        java.util.Arrays.fill(floorMaterialIds, "");
        java.util.Arrays.fill(overlayMaterialIds, "");
        String[] table = new String[] {""};
        if (materials) {
            table = readMaterialTable(in);
            readMaterialPlane(in, table, materialIds);
            readMaterialPlane(in, table, floorMaterialIds);
        }
        if (overlays) {
            readMaterialPlane(in, table, overlayMaterialIds);
        }
        return new ValuePlanes(
            biomes, surfaceYs, kinds, mapColors, fluidDepths, floorMapColors,
            materialIds, floorMaterialIds, overlayMaterialIds
        );
    }

    static boolean hasBit(final byte[] bits, final int index) {
        return (bits[index >>> 3] & (1 << (index & 7))) != 0;
    }

    static void setBit(final byte[] bits, final int index) {
        if (bits == null || index < 0 || index >= bits.length * 8) {
            throw new IndexOutOfBoundsException("bit index outside mask");
        }
        bits[index >>> 3] |= (byte) (1 << (index & 7));
    }

    static Map<String, Integer> materialTable(
        final List<PatchCodec.Sample> samples
    ) {
        final Map<String, Integer> result = new LinkedHashMap<>();
        result.put("", 0);
        for (final PatchCodec.Sample sample : samples) {
            addMaterial(result, sample.materialId());
            addMaterial(result, sample.floorMaterialId());
            addMaterial(result, sample.overlayMaterialId());
        }
        return result;
    }

    private static void addMaterial(final Map<String, Integer> materials, final String value) {
        final String material = value == null ? "" : value;
        if (!materials.containsKey(material)) {
            if (materials.size() >= PatchCodec.MAX_MATERIALS) {
                throw new IllegalArgumentException("patch material table exceeds cap");
            }
            materials.put(material, materials.size());
        }
    }

    static void writeMaterial(
        final DataOutputStream out, final String material
    ) throws IOException {
        final byte[] bytes = material.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > PatchCodec.MAX_MATERIAL_BYTES) {
            throw new IllegalArgumentException("patch material id exceeds UTF-8 cap");
        }
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    static String[] readMaterialTable(
        final DataInputStream in
    ) throws IOException, ProtoException {
        final int count = in.readUnsignedShort();
        if (count < 1 || count > PatchCodec.MAX_MATERIALS) {
            throw new ProtoException("invalid patch material count " + count);
        }
        final String[] result = new String[count];
        for (int i = 0; i < count; i++) {
            final int length = in.readUnsignedShort();
            if (length > PatchCodec.MAX_MATERIAL_BYTES) {
                throw new ProtoException("patch material id exceeds UTF-8 cap");
            }
            final byte[] bytes = new byte[length];
            in.readFully(bytes);
            result[i] = new String(bytes, StandardCharsets.UTF_8);
        }
        if (!result[0].isEmpty()) {
            throw new ProtoException("patch material table must start empty");
        }
        return result;
    }

    static void readMaterialPlane(
        final DataInputStream in,
        final String[] materials,
        final String[] output
    ) throws IOException, ProtoException {
        for (int i = 0; i < output.length; i++) {
            final int index = in.readUnsignedShort();
            if (index >= materials.length) {
                throw new ProtoException("patch material index outside table: " + index);
            }
            output[i] = materials[index];
        }
    }

    static byte[] deflate(final byte[] raw, final int maxCompressedBytes) throws IOException {
        final ByteArrayOutputStream compressed = new ByteArrayOutputStream(Math.min(raw.length, 64 * 1024));
        final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try {
            final DeflaterOutputStream out = new DeflaterOutputStream(compressed, deflater, 8192);
            out.write(raw);
            out.finish();
        } finally {
            deflater.end();
        }
        final byte[] result = compressed.toByteArray();
        if (result.length > maxCompressedBytes) {
            throw new IllegalArgumentException("patch body exceeds compressed cap: " + result.length);
        }
        return result;
    }

    static byte[] inflate(final byte[] body, final int maxRawBytes) throws ProtoException {
        final Inflater inflater = new Inflater();
        final ByteArrayOutputStream raw = new ByteArrayOutputStream(Math.min(body.length * 4, maxRawBytes));
        final byte[] buffer = new byte[8192];
        try {
            inflater.setInput(body);
            while (!inflater.finished()) {
                final int read = inflater.inflate(buffer);
                if (read > 0) {
                    if (raw.size() > maxRawBytes - read) {
                        throw new ProtoException("inflated patch body exceeds cap");
                    }
                    raw.write(buffer, 0, read);
                    continue;
                }
                if (inflater.needsDictionary()) {
                    throw new ProtoException("patch body requires a compression dictionary");
                }
                if (inflater.needsInput()) {
                    throw new ProtoException("truncated compressed patch body");
                }
                throw new ProtoException("compressed patch body made no progress");
            }
            if (inflater.getRemaining() != 0) {
                throw new ProtoException("trailing compressed patch bytes: " + inflater.getRemaining());
            }
            return raw.toByteArray();
        } catch (final DataFormatException e) {
            throw new ProtoException("malformed compressed patch body", e);
        } finally {
            inflater.end();
        }
    }
}
