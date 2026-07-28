package cn.net.rms.confluxmap.core.net;

import cn.net.rms.confluxmap.core.model.MapPixel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Hostile-input-safe raw layout for one authoritative residual correction snapshot.
 *
 * <p>The body is deliberately not application-compressed. It contains two hierarchical sparse bit
 * planes followed by fixed-width homogeneous value planes, leaving Minecraft's packet compression
 * to compress long runs and repeated terrain values. {@code evaluated[pixel]} distinguishes a
 * column the server proved equivalent to the deterministic baseline from a column it could not
 * read; {@code difference[pixel]} is implicit in the sample mask.
 */
public final class PatchCodec {
    public static final int FORMAT_VERSION = 2;
    public static final int PIXELS = 256 * 256;
    public static final int MASK_BYTES = PIXELS / 8;
    public static final int COARSE_MASK_BYTES = 32;
    public static final int FINE_MASK_BYTES = 32;
    public static final int MAX_SPARSE_MASK_BYTES = COARSE_MASK_BYTES + 256 * FINE_MASK_BYTES;
    public static final int RECORD_BYTES = 7;
    public static final int MAX_RAW_BYTES = 1 + MAX_SPARSE_MASK_BYTES * 2 + PIXELS * RECORD_BYTES;

    private PatchCodec() {
    }

    /** One absolute actual pixel selected by the residual difference mask. */
    public record Sample(int pixelIndex, MapPixel pixel) {
        public Sample {
            if (pixelIndex < 0 || pixelIndex >= PIXELS) {
                throw new IllegalArgumentException("pixel index outside tile: " + pixelIndex);
            }
            if (pixel == null) {
                throw new IllegalArgumentException("patch sample pixel is null");
            }
        }

        public Sample(
            final int pixelIndex,
            final int biomeId,
            final int surfaceY,
            final int kind,
            final int mapColorId,
            final int fluidDepth
        ) {
            this(pixelIndex, new MapPixel(biomeId, surfaceY, kind, mapColorId, fluidDepth));
        }

        public Sample(
            final int pixelIndex,
            final int biomeId,
            final int surfaceY,
            final int kind,
            final int mapColorId,
            final int fluidDepth,
            final int floorMapColorId
        ) {
            this(pixelIndex, new MapPixel(
                biomeId, surfaceY, kind, mapColorId, fluidDepth, floorMapColorId
            ));
        }

        public int biomeId() {
            return pixel.biomeId();
        }

        public int surfaceY() {
            return pixel.surfaceY();
        }

        public int kind() {
            return pixel.kind();
        }

        public int mapColorId() {
            return pixel.mapColorId();
        }

        public int fluidDepth() {
            return pixel.fluidDepth();
        }

        public int floorMapColorId() {
            return pixel.floorMapColorId();
        }
    }

    /** Evaluated coverage plus absolute residual samples, sorted by pixel on the wire. */
    public record Patch(byte[] evaluated, List<Sample> samples) {
        public Patch {
            if (evaluated == null || evaluated.length != MASK_BYTES) {
                throw new IllegalArgumentException("evaluated mask must contain " + MASK_BYTES + " bytes");
            }
            if (samples == null) {
                throw new IllegalArgumentException("patch samples are null");
            }
            evaluated = evaluated.clone();
            samples = List.copyOf(samples);
            final boolean[] seen = new boolean[PIXELS];
            for (final Sample sample : samples) {
                if (sample == null || seen[sample.pixelIndex()]) {
                    throw new IllegalArgumentException(
                        sample == null ? "patch contains null sample" : "duplicate pixel index " + sample.pixelIndex()
                    );
                }
                if (!hasBit(evaluated, sample.pixelIndex())) {
                    throw new IllegalArgumentException("difference pixel was not evaluated: " + sample.pixelIndex());
                }
                seen[sample.pixelIndex()] = true;
            }
        }

        /** Compatibility constructor: handcrafted samples are evaluated exactly where they differ. */
        public Patch(final List<Sample> samples) {
            this(evaluatedFrom(samples), samples);
        }

        @Override
        public byte[] evaluated() {
            return evaluated.clone();
        }

        public int size() {
            return samples.size();
        }

        public List<Sample> records() {
            return samples;
        }

        public boolean evaluatedAt(final int pixelIndex) {
            checkPixel(pixelIndex);
            return hasBit(evaluated, pixelIndex);
        }

        public Sample sampleAt(final int pixelIndex) {
            checkPixel(pixelIndex);
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
        final byte[] difference = new byte[MASK_BYTES];
        for (final Sample sample : patch.samples()) {
            byPixel[sample.pixelIndex()] = sample;
            setBit(difference, sample.pixelIndex());
        }
        final List<Sample> ordered = new ArrayList<>(patch.size());
        for (int pixel = 0; pixel < PIXELS; pixel++) {
            if (byPixel[pixel] != null) {
                ordered.add(byPixel[pixel]);
            }
        }
        try {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream(
                1 + COARSE_MASK_BYTES * 2 + ordered.size() * RECORD_BYTES
            );
            final DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(FORMAT_VERSION);
            writeSparseMask(out, patch.evaluated());
            writeSparseMask(out, difference);
            for (final Sample sample : ordered) {
                out.writeByte(sample.biomeId());
            }
            for (final Sample sample : ordered) {
                out.writeShort(sample.surfaceY());
            }
            for (final Sample sample : ordered) {
                out.writeByte(sample.kind());
            }
            for (final Sample sample : ordered) {
                out.writeByte(sample.mapColorId());
            }
            for (final Sample sample : ordered) {
                out.writeByte(sample.fluidDepth());
            }
            for (final Sample sample : ordered) {
                out.writeByte(sample.floorMapColorId());
            }
            out.flush();
            final byte[] result = bytes.toByteArray();
            if (result.length > MAX_RAW_BYTES) {
                throw new IllegalArgumentException("patch body exceeds raw cap");
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
        if (body == null || body.length < 1 + COARSE_MASK_BYTES * 2 || body.length > MAX_RAW_BYTES) {
            throw new ProtoException("invalid patch body length: " + (body == null ? -1 : body.length));
        }
        try {
            final DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
            final int version = in.readUnsignedByte();
            if (version != FORMAT_VERSION) {
                throw new ProtoException("unsupported patch body version " + version);
            }
            final byte[] evaluated = readSparseMask(in);
            final byte[] difference = readSparseMask(in);
            int count = 0;
            for (int i = 0; i < MASK_BYTES; i++) {
                if ((difference[i] & ~evaluated[i]) != 0) {
                    throw new ProtoException("difference mask contains unevaluated pixels");
                }
                count += Integer.bitCount(difference[i] & 255);
            }
            final int expectedBytes = count * RECORD_BYTES;
            if (in.available() != expectedBytes) {
                throw new ProtoException(
                    "patch field planes have wrong length: expected " + expectedBytes + ", got " + in.available()
                );
            }
            final int[] pixels = new int[count];
            int next = 0;
            for (int pixel = 0; pixel < PIXELS; pixel++) {
                if (hasBit(difference, pixel)) {
                    pixels[next++] = pixel;
                }
            }
            final int[] biomes = readUnsignedBytePlane(in, count);
            final int[] surfaceYs = readShortPlane(in, count);
            final int[] kinds = readUnsignedBytePlane(in, count);
            final int[] mapColors = readUnsignedBytePlane(in, count);
            final int[] fluidDepths = readUnsignedBytePlane(in, count);
            final int[] floorMapColors = readUnsignedBytePlane(in, count);
            final List<Sample> samples = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                samples.add(new Sample(
                    pixels[i],
                    biomes[i],
                    surfaceYs[i],
                    kinds[i],
                    mapColors[i],
                    fluidDepths[i],
                    floorMapColors[i]
                ));
            }
            return new Patch(evaluated, samples);
        } catch (final EOFException | IllegalArgumentException e) {
            throw new ProtoException("malformed patch body: " + e.getMessage(), e);
        } catch (final IOException e) {
            throw new ProtoException("malformed patch body", e);
        }
    }

    public static void setEvaluated(final byte[] evaluated, final int pixelIndex) {
        if (evaluated == null || evaluated.length != MASK_BYTES) {
            throw new IllegalArgumentException("invalid evaluated mask");
        }
        checkPixel(pixelIndex);
        setBit(evaluated, pixelIndex);
    }

    private static byte[] evaluatedFrom(final List<Sample> samples) {
        if (samples == null) {
            throw new IllegalArgumentException("patch samples are null");
        }
        final byte[] result = new byte[MASK_BYTES];
        for (final Sample sample : samples) {
            if (sample == null) {
                throw new IllegalArgumentException("patch contains null sample");
            }
            setBit(result, sample.pixelIndex());
        }
        return result;
    }

    private static void writeSparseMask(final DataOutputStream out, final byte[] pixels) throws IOException {
        final byte[] coarse = new byte[COARSE_MASK_BYTES];
        final byte[][] fine = new byte[256][];
        for (int pixel = 0; pixel < PIXELS; pixel++) {
            if (!hasBit(pixels, pixel)) {
                continue;
            }
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
        out.write(coarse);
        for (final byte[] mask : fine) {
            if (mask != null) {
                out.write(mask);
            }
        }
    }

    private static byte[] readSparseMask(final DataInputStream in) throws IOException {
        final byte[] coarse = new byte[COARSE_MASK_BYTES];
        in.readFully(coarse);
        final byte[] pixels = new byte[MASK_BYTES];
        for (int coarseIndex = 0; coarseIndex < 256; coarseIndex++) {
            if (!hasBit(coarse, coarseIndex)) {
                continue;
            }
            final byte[] fine = new byte[FINE_MASK_BYTES];
            in.readFully(fine);
            for (int fineIndex = 0; fineIndex < 256; fineIndex++) {
                if (!hasBit(fine, fineIndex)) {
                    continue;
                }
                final int x = ((coarseIndex & 15) << 4) | (fineIndex & 15);
                final int z = ((coarseIndex >>> 4) << 4) | (fineIndex >>> 4);
                setBit(pixels, (z << 8) | x);
            }
        }
        return pixels;
    }

    private static int[] readUnsignedBytePlane(final DataInputStream in, final int count) throws IOException {
        final int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            values[i] = in.readUnsignedByte();
        }
        return values;
    }

    private static int[] readShortPlane(final DataInputStream in, final int count) throws IOException {
        final int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            values[i] = in.readShort();
        }
        return values;
    }

    private static void setBit(final byte[] bits, final int index) {
        bits[index >>> 3] |= (byte) (1 << (index & 7));
    }

    private static boolean hasBit(final byte[] bits, final int index) {
        return (bits[index >>> 3] & (1 << (index & 7))) != 0;
    }

    private static void checkPixel(final int pixelIndex) {
        if (pixelIndex < 0 || pixelIndex >= PIXELS) {
            throw new IndexOutOfBoundsException("pixel index outside tile: " + pixelIndex);
        }
    }
}
