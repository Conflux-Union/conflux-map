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
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;

/**
 * Compact, hostile-input-safe layout for one authoritative correction snapshot.
 *
 * <p>The compressed body contains two hierarchical sparse bit planes followed by homogeneous value
 * planes. Heights are zigzag-varint deltas in pixel order; the remaining categorical fields stay
 * grouped so Deflate can exploit long terrain runs. {@code evaluated[pixel]} distinguishes a
 * column the server proved equivalent to the deterministic baseline from a column it could not
 * read; {@code difference[pixel]} is implicit in the sample mask.
 */
public final class PatchCodec {
    public static final int FORMAT_VERSION = 4;
    public static final int LEGACY_FORMAT_VERSION = 3;
    public static final int PIXELS = 256 * 256;
    public static final int MASK_BYTES = PIXELS / 8;
    public static final int COARSE_MASK_BYTES = 32;
    public static final int FINE_MASK_BYTES = 32;
    public static final int MAX_SPARSE_MASK_BYTES = COARSE_MASK_BYTES + 256 * FINE_MASK_BYTES;
    public static final int RECORD_BYTES = 7;
    private static final int MAX_DELTA_HEIGHT_BYTES = 3;
    public static final int MAX_RAW_BYTES =
        1 + MAX_SPARSE_MASK_BYTES * 2 + PIXELS * (RECORD_BYTES - 2 + MAX_DELTA_HEIGHT_BYTES + 11);
    public static final int MAX_COMPRESSED_BYTES = 576 * 1024;

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
    public record Patch(
        byte[] evaluated,
        List<Sample> samples,
        long[] sourceRevisions,
        byte[] blockLight
    ) {
        public Patch {
            if (evaluated == null || evaluated.length != MASK_BYTES) {
                throw new IllegalArgumentException("evaluated mask must contain " + MASK_BYTES + " bytes");
            }
            if (samples == null) {
                throw new IllegalArgumentException("patch samples are null");
            }
            evaluated = evaluated.clone();
            samples = List.copyOf(samples);
            if (sourceRevisions == null || sourceRevisions.length != PIXELS) {
                throw new IllegalArgumentException("source revisions must contain " + PIXELS + " entries");
            }
            if (blockLight == null || blockLight.length != PIXELS) {
                throw new IllegalArgumentException("block light must contain " + PIXELS + " entries");
            }
            sourceRevisions = sourceRevisions.clone();
            blockLight = blockLight.clone();
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
            for (int pixel = 0; pixel < PIXELS; pixel++) {
                final int light = blockLight[pixel] & 0xFF;
                if (light > 15) {
                    throw new IllegalArgumentException("block light outside 0..15 at pixel " + pixel);
                }
                if (!hasBit(evaluated, pixel)
                    && (sourceRevisions[pixel] != Long.MIN_VALUE || light != 0)) {
                    throw new IllegalArgumentException("metadata exists for unevaluated pixel " + pixel);
                }
            }
        }

        public Patch(final byte[] evaluated, final List<Sample> samples) {
            this(evaluated, samples, unknownRevisions(), new byte[PIXELS]);
        }

        /** Compatibility constructor: handcrafted samples are evaluated exactly where they differ. */
        public Patch(final List<Sample> samples) {
            this(evaluatedFrom(samples), samples, unknownRevisions(), new byte[PIXELS]);
        }

        @Override
        public byte[] evaluated() {
            return evaluated.clone();
        }

        @Override
        public long[] sourceRevisions() {
            return sourceRevisions.clone();
        }

        @Override
        public byte[] blockLight() {
            return blockLight.clone();
        }

        public long sourceRevisionAt(final int pixelIndex) {
            checkPixel(pixelIndex);
            return sourceRevisions[pixelIndex];
        }

        public int blockLightAt(final int pixelIndex) {
            checkPixel(pixelIndex);
            return blockLight[pixelIndex] & 0xFF;
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
        return encode(patch, FORMAT_VERSION);
    }

    public static byte[] encodeLegacy(final Patch patch) {
        return encode(patch, LEGACY_FORMAT_VERSION);
    }

    private static byte[] encode(final Patch patch, final int formatVersion) {
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
            final ByteArrayOutputStream rawBytes = new ByteArrayOutputStream(
                1 + COARSE_MASK_BYTES * 2 + ordered.size() * RECORD_BYTES
            );
            final DataOutputStream out = new DataOutputStream(rawBytes);
            out.writeByte(formatVersion);
            writeSparseMask(out, patch.evaluated());
            writeSparseMask(out, difference);
            for (final Sample sample : ordered) {
                out.writeByte(sample.biomeId());
            }
            int previousY = 0;
            for (final Sample sample : ordered) {
                writeZigzagVarint(out, sample.surfaceY() - previousY);
                previousY = sample.surfaceY();
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
            if (formatVersion >= FORMAT_VERSION) {
                long previousRevision = 0L;
                for (int pixel = 0; pixel < PIXELS; pixel++) {
                    if (!patch.evaluatedAt(pixel)) {
                        continue;
                    }
                    final long revision = patch.sourceRevisions[pixel];
                    writeZigzagVarLong(out, revision - previousRevision);
                    previousRevision = revision;
                }
                for (int pixel = 0; pixel < PIXELS; pixel++) {
                    if (patch.evaluatedAt(pixel)) {
                        out.writeByte(patch.blockLight[pixel]);
                    }
                }
            }
            out.flush();
            final byte[] raw = rawBytes.toByteArray();
            if (raw.length > MAX_RAW_BYTES) {
                throw new IllegalArgumentException("patch body exceeds raw cap");
            }
            return deflate(raw);
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
            final int version = in.readUnsignedByte();
            if (version != FORMAT_VERSION && version != LEGACY_FORMAT_VERSION) {
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
            final int[] pixels = new int[count];
            int next = 0;
            for (int pixel = 0; pixel < PIXELS; pixel++) {
                if (hasBit(difference, pixel)) {
                    pixels[next++] = pixel;
                }
            }
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
            final long[] sourceRevisions = unknownRevisions();
            final byte[] blockLight = new byte[PIXELS];
            if (version >= FORMAT_VERSION) {
                long previousRevision = 0L;
                for (int pixel = 0; pixel < PIXELS; pixel++) {
                    if (!hasBit(evaluated, pixel)) {
                        continue;
                    }
                    previousRevision += readZigzagVarLong(in);
                    sourceRevisions[pixel] = previousRevision;
                }
                for (int pixel = 0; pixel < PIXELS; pixel++) {
                    if (!hasBit(evaluated, pixel)) {
                        continue;
                    }
                    final int light = in.readUnsignedByte();
                    if (light > 15) {
                        throw new ProtoException("block light outside 0..15: " + light);
                    }
                    blockLight[pixel] = (byte) light;
                }
            }
            if (in.available() != 0) {
                throw new ProtoException("trailing bytes in patch body: " + in.available());
            }
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
            return new Patch(evaluated, samples, sourceRevisions, blockLight);
        } catch (final EOFException | IllegalArgumentException e) {
            throw new ProtoException("malformed patch body: " + e.getMessage(), e);
        } catch (final IOException e) {
            throw new ProtoException("malformed patch body", e);
        }
    }

    private static byte[] deflate(final byte[] raw) throws IOException {
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
        if (result.length > MAX_COMPRESSED_BYTES) {
            throw new IllegalArgumentException("patch body exceeds compressed cap: " + result.length);
        }
        return result;
    }

    private static byte[] inflate(final byte[] body) throws ProtoException {
        final Inflater inflater = new Inflater();
        final ByteArrayOutputStream raw = new ByteArrayOutputStream(Math.min(body.length * 4, MAX_RAW_BYTES));
        final byte[] buffer = new byte[8192];
        try {
            inflater.setInput(body);
            while (!inflater.finished()) {
                final int read = inflater.inflate(buffer);
                if (read > 0) {
                    if (raw.size() > MAX_RAW_BYTES - read) {
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

    private static long[] unknownRevisions() {
        final long[] revisions = new long[PIXELS];
        java.util.Arrays.fill(revisions, Long.MIN_VALUE);
        return revisions;
    }

    private static void writeZigzagVarLong(final DataOutputStream out, final long value) throws IOException {
        long encoded = (value << 1) ^ (value >> 63);
        while ((encoded & ~0x7FL) != 0L) {
            out.writeByte((int) (encoded & 0x7F) | 0x80);
            encoded >>>= 7;
        }
        out.writeByte((int) encoded);
    }

    private static long readZigzagVarLong(final DataInputStream in) throws IOException, ProtoException {
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
        for (int shift = 0; shift < MAX_DELTA_HEIGHT_BYTES * 7; shift += 7) {
            final int next = in.readUnsignedByte();
            encoded |= (next & 0x7F) << shift;
            if ((next & 0x80) == 0) {
                return (encoded >>> 1) ^ -(encoded & 1);
            }
        }
        throw new ProtoException("overlong surface height delta");
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
