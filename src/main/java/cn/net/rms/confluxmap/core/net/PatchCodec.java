package cn.net.rms.confluxmap.core.net;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Compact, hostile-input-safe codec for the sparse MAP_PATCH body.
 *
 * <p>Pre-deflate layout: the 32-byte coarse bitmap of active 16x16 cells, the 32-byte fine
 * bitmap of every active cell in ascending cell order, then one plane per field over all
 * masked pixels in mask-traversal order: {@code biomeId u8}, {@code surfaceY} as a
 * zigzag-varint delta against the previous masked pixel, {@code kind u8}, {@code mapColorId u8},
 * {@code fluidDepth u8}. Field-separated planes keep same-typed bytes adjacent and the height
 * plane near-zero, which deflate compresses far better than interleaved per-pixel records.
 */
public final class PatchCodec {
    public static final int PIXELS = 256 * 256;
    public static final int COARSE_MASK_BYTES = 32;
    public static final int FINE_MASK_BYTES = 32;
    public static final int MAX_RAW_BYTES = 512 * 1024;
    public static final int MAX_COMPRESSED_BYTES = 48 * 1024;

    private PatchCodec() {
    }

    /** Encodes removal of an older correction while remaining harmless to clients that ignore unknown samples. */
    public static Sample removal(final int pixelIndex) {
        return new Sample(pixelIndex, 0, Short.MIN_VALUE, SurfaceKind.UNKNOWN.ordinal(), Proto.MAP_COLOR_NONE, 0);
    }

    public static boolean isRemoval(final Sample sample) {
        return sample != null
            && sample.biomeId() == 0
            && sample.surfaceY() == Short.MIN_VALUE
            && sample.kind() == SurfaceKind.UNKNOWN.ordinal()
            && sample.mapColorId() == Proto.MAP_COLOR_NONE
            && sample.fluidDepth() == 0;
    }

    /** One corrected pixel or {@link #removal(int) removal marker}. {@code pixelIndex = z * 256 + x}. */
    public record Sample(int pixelIndex, int biomeId, int surfaceY, int kind, int mapColorId, int fluidDepth) {
        public Sample {
            if (pixelIndex < 0 || pixelIndex >= PIXELS) {
                throw new IllegalArgumentException("pixel index outside tile: " + pixelIndex);
            }
            checkByte("biomeId", biomeId);
            if (surfaceY < Short.MIN_VALUE || surfaceY > Short.MAX_VALUE) {
                throw new IllegalArgumentException("surfaceY outside i16: " + surfaceY);
            }
            checkByte("kind", kind);
            checkByte("mapColorId", mapColorId);
            checkByte("fluidDepth", fluidDepth);
        }

        private static void checkByte(final String field, final int value) {
            if (value < 0 || value > 255) {
                throw new IllegalArgumentException(field + " outside u8: " + value);
            }
        }
    }

    /** Decoded sparse records, sorted by pixel index. */
    public record Patch(List<Sample> samples) {
        public Patch {
            samples = List.copyOf(samples);
        }

        public int size() {
            return samples.size();
        }

        public List<Sample> records() {
            return samples;
        }

        public Sample sampleAt(final int pixelIndex) {
            for (final Sample sample : samples) {
                if (sample.pixelIndex() == pixelIndex) {
                    return sample;
                }
            }
            return null;
        }
    }

    public static byte[] encode(final Patch patch) {
        if (patch == null) {
            throw new IllegalArgumentException("patch is null");
        }
        final Sample[] byPixel = new Sample[PIXELS];
        final byte[] coarse = new byte[COARSE_MASK_BYTES];
        final byte[][] fine = new byte[PIXELS / 256][];
        for (final Sample sample : patch.samples()) {
            final int pixel = sample.pixelIndex();
            if (byPixel[pixel] != null) {
                throw new IllegalArgumentException("duplicate pixel index " + pixel);
            }
            byPixel[pixel] = sample;
            final int x = pixel & 255;
            final int z = pixel >>> 8;
            final int coarseIndex = (z >>> 4) * 16 + (x >>> 4);
            final int fineIndex = (z & 15) * 16 + (x & 15);
            setBit(coarse, coarseIndex);
            if (fine[coarseIndex] == null) {
                fine[coarseIndex] = new byte[FINE_MASK_BYTES];
            }
            setBit(fine[coarseIndex], fineIndex);
        }
        try {
            final ByteArrayOutputStream rawBytes = new ByteArrayOutputStream(COARSE_MASK_BYTES + patch.size() * 40);
            final DataOutputStream raw = new DataOutputStream(rawBytes);
            raw.write(coarse);
            final List<Sample> ordered = new ArrayList<>(patch.size());
            for (int coarseIndex = 0; coarseIndex < fine.length; coarseIndex++) {
                if (fine[coarseIndex] == null) {
                    continue;
                }
                raw.write(fine[coarseIndex]);
                for (int fineIndex = 0; fineIndex < 256; fineIndex++) {
                    if (hasBit(fine[coarseIndex], fineIndex)) {
                        ordered.add(byPixel[pixelAt(coarseIndex, fineIndex)]);
                    }
                }
            }
            for (final Sample sample : ordered) {
                raw.writeByte(sample.biomeId());
            }
            int previousY = 0;
            for (final Sample sample : ordered) {
                writeZigzagVarint(raw, sample.surfaceY() - previousY);
                previousY = sample.surfaceY();
            }
            for (final Sample sample : ordered) {
                raw.writeByte(sample.kind());
            }
            for (final Sample sample : ordered) {
                raw.writeByte(sample.mapColorId());
            }
            for (final Sample sample : ordered) {
                raw.writeByte(sample.fluidDepth());
            }
            raw.flush();
            final byte[] uncompressed = rawBytes.toByteArray();
            if (uncompressed.length > MAX_RAW_BYTES) {
                throw new IllegalArgumentException("patch body exceeds raw cap");
            }
            final ByteArrayOutputStream compressed = new ByteArrayOutputStream(uncompressed.length);
            final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
            try {
                final DeflaterOutputStream out = new DeflaterOutputStream(compressed, deflater, 8192);
                out.write(uncompressed);
                out.finish();
            } finally {
                deflater.end();
            }
            final byte[] result = compressed.toByteArray();
            if (result.length > MAX_COMPRESSED_BYTES) {
                throw new IllegalArgumentException("patch body exceeds compressed cap: " + result.length);
            }
            return result;
        } catch (final IOException e) {
            throw new IllegalStateException("in-memory patch encoding failed", e);
        }
    }

    public static byte[] encode(final Iterable<Sample> samples) {
        final List<Sample> list = new ArrayList<>();
        for (final Sample sample : samples) {
            list.add(sample);
        }
        return encode(new Patch(list));
    }

    public static byte[] encode(final java.util.Map<Integer, Sample> samples) {
        return encode(samples.values());
    }

    public static Patch decode(final byte[] body) throws ProtoException {
        if (body == null || body.length == 0 || body.length > MAX_COMPRESSED_BYTES) {
            throw new ProtoException("invalid patch body length: " + (body == null ? -1 : body.length));
        }
        final byte[] raw = inflate(body);
        try {
            final DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
            final byte[] coarse = new byte[COARSE_MASK_BYTES];
            in.readFully(coarse);
            final byte[][] fine = new byte[PIXELS / 256][];
            int count = 0;
            for (int coarseIndex = 0; coarseIndex < 256; coarseIndex++) {
                if (!hasBit(coarse, coarseIndex)) {
                    continue;
                }
                final byte[] mask = new byte[FINE_MASK_BYTES];
                in.readFully(mask);
                fine[coarseIndex] = mask;
                for (final byte b : mask) {
                    count += Integer.bitCount(b & 0xFF);
                }
            }
            final int[] pixels = new int[count];
            int next = 0;
            for (int coarseIndex = 0; coarseIndex < 256; coarseIndex++) {
                if (fine[coarseIndex] == null) {
                    continue;
                }
                for (int fineIndex = 0; fineIndex < 256; fineIndex++) {
                    if (hasBit(fine[coarseIndex], fineIndex)) {
                        pixels[next++] = pixelAt(coarseIndex, fineIndex);
                    }
                }
            }
            final int[] biomes = readBytePlane(in, count);
            final int[] surfaceYs = new int[count];
            int previousY = 0;
            for (int i = 0; i < count; i++) {
                previousY += readZigzagVarint(in);
                surfaceYs[i] = previousY;
            }
            final int[] kinds = readBytePlane(in, count);
            final int[] mapColors = readBytePlane(in, count);
            final int[] fluidDepths = readBytePlane(in, count);
            if (in.available() != 0) {
                throw new ProtoException("trailing bytes in patch body: " + in.available());
            }
            final List<Sample> samples = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                samples.add(new Sample(pixels[i], biomes[i], surfaceYs[i], kinds[i], mapColors[i], fluidDepths[i]));
            }
            return new Patch(samples);
        } catch (final EOFException | IllegalArgumentException e) {
            throw new ProtoException("malformed patch body: " + e.getMessage(), e);
        } catch (final IOException e) {
            throw new ProtoException("malformed patch body", e);
        }
    }

    private static byte[] inflate(final byte[] body) throws ProtoException {
        try {
            final InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(body));
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_RAW_BYTES) {
                    throw new ProtoException("inflated patch body exceeds cap");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (final ProtoException e) {
            throw e;
        } catch (final IOException e) {
            throw new ProtoException("invalid deflate patch body", e);
        }
    }

    private static void setBit(final byte[] bits, final int index) {
        bits[index >>> 3] |= (byte) (1 << (index & 7));
    }

    private static boolean hasBit(final byte[] bits, final int index) {
        return (bits[index >>> 3] & (1 << (index & 7))) != 0;
    }

    private static int pixelAt(final int coarseIndex, final int fineIndex) {
        final int x = ((coarseIndex & 15) << 4) | (fineIndex & 15);
        final int z = ((coarseIndex >>> 4) << 4) | (fineIndex >>> 4);
        return (z << 8) | x;
    }

    private static int[] readBytePlane(final DataInputStream in, final int count) throws IOException {
        final int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            values[i] = in.readUnsignedByte();
        }
        return values;
    }

    private static void writeZigzagVarint(final DataOutputStream out, final int value) throws IOException {
        int encoded = (value << 1) ^ (value >> 31);
        while ((encoded & ~0x7F) != 0) {
            out.writeByte((encoded & 0x7F) | 0x80);
            encoded >>>= 7;
        }
        out.writeByte(encoded);
    }

    private static int readZigzagVarint(final DataInputStream in) throws IOException, ProtoException {
        int encoded = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            final int b = in.readUnsignedByte();
            encoded |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return (encoded >>> 1) ^ -(encoded & 1);
            }
        }
        throw new ProtoException("varint longer than 5 bytes");
    }
}
