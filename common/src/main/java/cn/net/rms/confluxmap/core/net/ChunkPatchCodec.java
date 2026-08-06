package cn.net.rms.confluxmap.core.net;

import cn.net.rms.confluxmap.core.util.ChunkRegionSlice;
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

/** Variable-size authoritative correction codec for one cropped 16x16-chunk region page. */
public final class ChunkPatchCodec {
    public static final int FORMAT_VERSION = 2;
    public static final int LEGACY_FORMAT_VERSION = 1;
    public static final int MAX_CHUNKS_PER_SIDE = 16;
    public static final int MAX_PIXELS = 256 * 256;
    public static final int MAX_RAW_BYTES = 640 * 1024;
    public static final int MAX_COMPRESSED_BYTES = 576 * 1024;
    private static final int MASK_DENSE = 0;
    private static final int MASK_RUNS = 1;
    private static final int MAX_DELTA_HEIGHT_BYTES = 3;

    private ChunkPatchCodec() {
    }

    public record Patch(
        int chunkWidth,
        int chunkHeight,
        int samplesPerChunk,
        byte[] generated,
        byte[] evaluated,
        List<PatchCodec.Sample> samples,
        long[] sourceRevisions,
        byte[] blockLight
    ) {
        public Patch {
            if (chunkWidth < 1 || chunkWidth > MAX_CHUNKS_PER_SIDE
                || chunkHeight < 1 || chunkHeight > MAX_CHUNKS_PER_SIDE
                || samplesPerChunk < 1 || samplesPerChunk > 16
                || (samplesPerChunk & (samplesPerChunk - 1)) != 0) {
                throw new IllegalArgumentException("invalid chunk patch dimensions");
            }
            final int chunkCount = Math.multiplyExact(chunkWidth, chunkHeight);
            final int pixelCount = Math.multiplyExact(
                Math.multiplyExact(chunkWidth, samplesPerChunk),
                Math.multiplyExact(chunkHeight, samplesPerChunk)
            );
            if (generated == null || generated.length != maskBytes(chunkCount)) {
                throw new IllegalArgumentException("generated mask has the wrong length");
            }
            if (evaluated == null || evaluated.length != maskBytes(pixelCount)) {
                throw new IllegalArgumentException("evaluated mask has the wrong length");
            }
            requireUnusedBitsClear(generated, chunkCount);
            requireUnusedBitsClear(evaluated, pixelCount);
            if (samples == null) {
                throw new IllegalArgumentException("chunk patch samples are null");
            }
            if (sourceRevisions == null || sourceRevisions.length != chunkCount) {
                throw new IllegalArgumentException("chunk source revisions have the wrong length");
            }
            if (blockLight == null || blockLight.length != pixelCount) {
                throw new IllegalArgumentException("chunk patch block light has the wrong length");
            }
            generated = generated.clone();
            evaluated = evaluated.clone();
            samples = List.copyOf(samples);
            sourceRevisions = sourceRevisions.clone();
            blockLight = blockLight.clone();
            final boolean[] seen = new boolean[pixelCount];
            for (final PatchCodec.Sample sample : samples) {
                if (sample == null || sample.pixelIndex() >= pixelCount || seen[sample.pixelIndex()]) {
                    throw new IllegalArgumentException(
                        sample == null ? "chunk patch contains a null sample" : "invalid sample index " + sample.pixelIndex()
                    );
                }
                if (!hasBit(evaluated, sample.pixelIndex())) {
                    throw new IllegalArgumentException("difference sample was not evaluated");
                }
                seen[sample.pixelIndex()] = true;
            }
            for (int pixel = 0; pixel < pixelCount; pixel++) {
                final int light = blockLight[pixel] & 0xFF;
                if (light > 15) {
                    throw new IllegalArgumentException("block light outside 0..15 at pixel " + pixel);
                }
                if (!hasBit(evaluated, pixel) && light != 0) {
                    throw new IllegalArgumentException("block light exists for unevaluated pixel " + pixel);
                }
            }
        }

        public Patch(
            final int chunkWidth,
            final int chunkHeight,
            final int samplesPerChunk,
            final byte[] generated,
            final byte[] evaluated,
            final List<PatchCodec.Sample> samples
        ) {
            this(
                chunkWidth, chunkHeight, samplesPerChunk, generated, evaluated, samples,
                unknownRevisions(chunkWidth * chunkHeight),
                new byte[chunkWidth * samplesPerChunk * chunkHeight * samplesPerChunk]
            );
        }

        @Override
        public byte[] generated() {
            return generated.clone();
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

        public int sampleWidth() {
            return chunkWidth * samplesPerChunk;
        }

        public int sampleHeight() {
            return chunkHeight * samplesPerChunk;
        }

        public int pixelCount() {
            return sampleWidth() * sampleHeight();
        }

        public boolean generatedAt(final int chunkIndex) {
            checkIndex(chunkIndex, chunkWidth * chunkHeight, "chunk");
            return hasBit(generated, chunkIndex);
        }

        public boolean evaluatedAt(final int pixelIndex) {
            checkIndex(pixelIndex, pixelCount(), "pixel");
            return hasBit(evaluated, pixelIndex);
        }

        public long sourceRevisionAt(final int chunkIndex) {
            checkIndex(chunkIndex, chunkWidth * chunkHeight, "chunk");
            return sourceRevisions[chunkIndex];
        }

        public int blockLightAt(final int pixelIndex) {
            checkIndex(pixelIndex, pixelCount(), "pixel");
            return blockLight[pixelIndex] & 0xFF;
        }

        public PatchCodec.Sample sampleAt(final int pixelIndex) {
            checkIndex(pixelIndex, pixelCount(), "pixel");
            for (final PatchCodec.Sample sample : samples) {
                if (sample.pixelIndex() == pixelIndex) {
                    return sample;
                }
            }
            return null;
        }
    }

    public static int maskBytes(final int bits) {
        if (bits < 0) {
            throw new IllegalArgumentException("negative mask size");
        }
        return (bits + 7) >>> 3;
    }

    public static void setBit(final byte[] bits, final int index) {
        if (bits == null || index < 0 || index >= bits.length * 8) {
            throw new IndexOutOfBoundsException("bit index outside mask");
        }
        bits[index >>> 3] |= (byte) (1 << (index & 7));
    }

    public static byte[] encode(final Patch patch) {
        return encode(patch, FORMAT_VERSION);
    }

    public static byte[] encodeLegacy(final Patch patch) {
        return encode(patch, LEGACY_FORMAT_VERSION);
    }

    private static byte[] encode(final Patch patch, final int formatVersion) {
        if (patch == null) {
            throw new IllegalArgumentException("chunk patch is null");
        }
        final PatchCodec.Sample[] byPixel = new PatchCodec.Sample[patch.pixelCount()];
        final byte[] difference = new byte[maskBytes(patch.pixelCount())];
        for (final PatchCodec.Sample sample : patch.samples()) {
            byPixel[sample.pixelIndex()] = sample;
            setBit(difference, sample.pixelIndex());
        }
        final List<PatchCodec.Sample> ordered = new ArrayList<>(patch.samples().size());
        for (final PatchCodec.Sample sample : byPixel) {
            if (sample != null) {
                ordered.add(sample);
            }
        }
        try {
            final ByteArrayOutputStream rawBytes = new ByteArrayOutputStream();
            final DataOutputStream out = new DataOutputStream(rawBytes);
            out.writeByte(formatVersion);
            out.writeByte(patch.chunkWidth());
            out.writeByte(patch.chunkHeight());
            out.writeByte(patch.samplesPerChunk());
            writeMask(out, patch.generated(), patch.chunkWidth() * patch.chunkHeight());
            writeMask(out, patch.evaluated(), patch.pixelCount());
            writeMask(out, difference, patch.pixelCount());
            for (final PatchCodec.Sample sample : ordered) {
                out.writeByte(sample.biomeId());
            }
            int previousY = 0;
            for (final PatchCodec.Sample sample : ordered) {
                writeZigzagVarint(out, sample.surfaceY() - previousY);
                previousY = sample.surfaceY();
            }
            for (final PatchCodec.Sample sample : ordered) {
                out.writeByte(sample.kind());
            }
            for (final PatchCodec.Sample sample : ordered) {
                out.writeByte(sample.mapColorId());
            }
            for (final PatchCodec.Sample sample : ordered) {
                out.writeByte(sample.fluidDepth());
            }
            for (final PatchCodec.Sample sample : ordered) {
                out.writeByte(sample.floorMapColorId());
            }
            if (formatVersion >= FORMAT_VERSION) {
                long previousRevision = 0L;
                for (final long revision : patch.sourceRevisions) {
                    writeZigzagVarLong(out, revision - previousRevision);
                    previousRevision = revision;
                }
                for (int pixel = 0; pixel < patch.pixelCount(); pixel++) {
                    if (patch.evaluatedAt(pixel)) {
                        out.writeByte(patch.blockLight[pixel]);
                    }
                }
            }
            out.flush();
            final byte[] raw = rawBytes.toByteArray();
            if (raw.length > MAX_RAW_BYTES) {
                throw new IllegalArgumentException("chunk patch exceeds raw cap");
            }
            return deflate(raw);
        } catch (final IOException e) {
            throw new IllegalStateException("in-memory chunk patch encoding failed", e);
        }
    }

    /** Stable content fingerprints for each chunk in row-major patch order. */
    public static long[] chunkRevisions(final Patch patch) {
        return chunkRevisions(patch, CorrectionProfile.SOURCE_LIGHT_V2);
    }

    /** Profile-specific fingerprints so released peers retain their original cache identity. */
    public static long[] chunkRevisions(
        final Patch patch, final CorrectionProfile profile
    ) {
        if (patch == null) {
            throw new IllegalArgumentException("chunk patch is null");
        }
        final PatchCodec.Sample[] byPixel = new PatchCodec.Sample[patch.pixelCount()];
        for (final PatchCodec.Sample sample : patch.samples()) {
            byPixel[sample.pixelIndex()] = sample;
        }
        final long[] revisions = new long[patch.chunkWidth() * patch.chunkHeight()];
        final int sampleWidth = patch.sampleWidth();
        final int samplesPerChunk = patch.samplesPerChunk();
        for (int chunkZ = 0; chunkZ < patch.chunkHeight(); chunkZ++) {
            for (int chunkX = 0; chunkX < patch.chunkWidth(); chunkX++) {
                final int chunkIndex = chunkZ * patch.chunkWidth() + chunkX;
                long hash = 0xcbf29ce484222325L;
                hash = fnv1a(hash, patch.generatedAt(chunkIndex) ? 1 : 0);
                if (profile.carriesSourceMetadata()) {
                    hash = fnv1aLong(hash, patch.sourceRevisionAt(chunkIndex));
                }
                for (int sampleZ = 0; sampleZ < samplesPerChunk; sampleZ++) {
                    for (int sampleX = 0; sampleX < samplesPerChunk; sampleX++) {
                        final int pixel = (chunkZ * samplesPerChunk + sampleZ) * sampleWidth
                            + chunkX * samplesPerChunk + sampleX;
                        hash = fnv1a(hash, patch.evaluatedAt(pixel) ? 1 : 0);
                        if (profile.carriesSourceMetadata()) {
                            hash = fnv1a(hash, patch.blockLightAt(pixel));
                        }
                        final PatchCodec.Sample sample = byPixel[pixel];
                        hash = fnv1a(hash, sample == null ? 0 : 1);
                        if (sample != null) {
                            hash = hashSample(hash, sample);
                        }
                    }
                }
                revisions[chunkIndex] = normalizeRevision(hash);
            }
        }
        return revisions;
    }

    /** Stable revision for this exact LOD and cropped region rectangle. */
    public static long regionRevision(
        final int lod, final ChunkRegionSlice slice, final Patch patch
    ) {
        return regionRevision(lod, slice, patch, CorrectionProfile.SOURCE_LIGHT_V2);
    }

    public static long regionRevision(
        final int lod,
        final ChunkRegionSlice slice,
        final Patch patch,
        final CorrectionProfile profile
    ) {
        if (slice == null || patch == null
            || slice.width() != patch.chunkWidth() || slice.height() != patch.chunkHeight()) {
            throw new IllegalArgumentException("region slice and patch dimensions disagree");
        }
        return regionRevision(lod, slice, chunkRevisions(patch, profile));
    }

    public static long regionRevision(
        final int lod, final ChunkRegionSlice slice, final long[] chunkRevisions
    ) {
        if (slice == null || chunkRevisions == null
            || chunkRevisions.length != slice.width() * slice.height()) {
            throw new IllegalArgumentException("region revision coverage is incomplete");
        }
        long hash = 0xcbf29ce484222325L;
        hash = fnv1aInt(hash, lod);
        hash = fnv1aInt(hash, slice.regionX());
        hash = fnv1aInt(hash, slice.regionZ());
        hash = fnv1a(hash, slice.minLocalChunkX());
        hash = fnv1a(hash, slice.minLocalChunkZ());
        hash = fnv1a(hash, slice.maxLocalChunkX());
        hash = fnv1a(hash, slice.maxLocalChunkZ());
        for (final long revision : chunkRevisions) {
            hash = fnv1aLong(hash, revision);
        }
        return normalizeRevision(hash);
    }

    public static Patch decode(final byte[] body) throws ProtoException {
        if (body == null || body.length == 0 || body.length > MAX_COMPRESSED_BYTES) {
            throw new ProtoException("invalid chunk patch body length: " + (body == null ? -1 : body.length));
        }
        final byte[] raw = inflate(body);
        try {
            final DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
            final int version = in.readUnsignedByte();
            if (version != FORMAT_VERSION && version != LEGACY_FORMAT_VERSION) {
                throw new ProtoException("unsupported chunk patch version " + version);
            }
            final int chunkWidth = in.readUnsignedByte();
            final int chunkHeight = in.readUnsignedByte();
            final int samplesPerChunk = in.readUnsignedByte();
            if (chunkWidth < 1 || chunkWidth > MAX_CHUNKS_PER_SIDE
                || chunkHeight < 1 || chunkHeight > MAX_CHUNKS_PER_SIDE
                || samplesPerChunk < 1 || samplesPerChunk > 16
                || (samplesPerChunk & (samplesPerChunk - 1)) != 0) {
                throw new ProtoException("invalid chunk patch dimensions");
            }
            final int chunks = chunkWidth * chunkHeight;
            final int pixels = chunkWidth * samplesPerChunk * chunkHeight * samplesPerChunk;
            final byte[] generated = readMask(in, chunks);
            final byte[] evaluated = readMask(in, pixels);
            final byte[] difference = readMask(in, pixels);
            int count = 0;
            for (int i = 0; i < difference.length; i++) {
                if ((difference[i] & ~evaluated[i]) != 0) {
                    throw new ProtoException("difference mask contains unevaluated pixels");
                }
                count += Integer.bitCount(difference[i] & 255);
            }
            final int[] pixelIndexes = new int[count];
            int next = 0;
            for (int pixel = 0; pixel < pixels; pixel++) {
                if (hasBit(difference, pixel)) {
                    pixelIndexes[next++] = pixel;
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
            final long[] sourceRevisions = unknownRevisions(chunks);
            final byte[] blockLight = new byte[pixels];
            if (version >= FORMAT_VERSION) {
                long previousRevision = 0L;
                for (int chunk = 0; chunk < chunks; chunk++) {
                    previousRevision += readZigzagVarLong(in);
                    sourceRevisions[chunk] = previousRevision;
                }
                for (int pixel = 0; pixel < pixels; pixel++) {
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
                throw new ProtoException("trailing chunk patch bytes: " + in.available());
            }
            final List<PatchCodec.Sample> samples = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                samples.add(new PatchCodec.Sample(
                    pixelIndexes[i], biomes[i], surfaceYs[i], kinds[i], mapColors[i], fluidDepths[i], floorMapColors[i]
                ));
            }
            return new Patch(
                chunkWidth, chunkHeight, samplesPerChunk, generated, evaluated, samples,
                sourceRevisions, blockLight
            );
        } catch (final ProtoException e) {
            throw e;
        } catch (final EOFException | IllegalArgumentException e) {
            throw new ProtoException("malformed chunk patch: " + e.getMessage(), e);
        } catch (final IOException e) {
            throw new ProtoException("malformed chunk patch", e);
        }
    }

    private static void writeMask(final DataOutputStream out, final byte[] mask, final int bitCount) throws IOException {
        int runCount = 0;
        int runsBytes = 0;
        int previousEnd = 0;
        for (int bit = 0; bit < bitCount;) {
            if (!hasBit(mask, bit)) {
                bit++;
                continue;
            }
            final int start = bit;
            while (bit < bitCount && hasBit(mask, bit)) {
                bit++;
            }
            final int length = bit - start;
            runsBytes += unsignedVarintBytes(start - previousEnd) + unsignedVarintBytes(length);
            previousEnd = bit;
            runCount++;
        }
        runsBytes += unsignedVarintBytes(runCount);
        if (1 + runsBytes < 1 + mask.length) {
            out.writeByte(MASK_RUNS);
            writeUnsignedVarint(out, runCount);
            previousEnd = 0;
            for (int bit = 0; bit < bitCount;) {
                if (!hasBit(mask, bit)) {
                    bit++;
                    continue;
                }
                final int start = bit;
                while (bit < bitCount && hasBit(mask, bit)) {
                    bit++;
                }
                writeUnsignedVarint(out, start - previousEnd);
                writeUnsignedVarint(out, bit - start);
                previousEnd = bit;
            }
            return;
        }
        out.writeByte(MASK_DENSE);
        out.write(mask);
    }

    private static byte[] readMask(final DataInputStream in, final int bitCount) throws IOException, ProtoException {
        final int mode = in.readUnsignedByte();
        final byte[] result = new byte[maskBytes(bitCount)];
        if (mode == MASK_DENSE) {
            in.readFully(result);
            if (!unusedBitsClear(result, bitCount)) {
                throw new ProtoException("mask contains bits outside its declared size");
            }
            return result;
        }
        if (mode != MASK_RUNS) {
            throw new ProtoException("unknown chunk mask mode " + mode);
        }
        final int runCount = readUnsignedVarint(in, 3);
        if (runCount > bitCount) {
            throw new ProtoException("too many chunk mask runs");
        }
        int end = 0;
        for (int run = 0; run < runCount; run++) {
            final int delta = readUnsignedVarint(in, 3);
            final int length = readUnsignedVarint(in, 3);
            if (length <= 0 || delta > bitCount - end) {
                throw new ProtoException("invalid chunk mask run");
            }
            final int start = end + delta;
            if (length > bitCount - start) {
                throw new ProtoException("chunk mask run exceeds declared size");
            }
            for (int bit = start; bit < start + length; bit++) {
                setBit(result, bit);
            }
            end = start + length;
        }
        return result;
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
            throw new IllegalArgumentException("chunk patch exceeds compressed cap");
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
                        throw new ProtoException("inflated chunk patch exceeds cap");
                    }
                    raw.write(buffer, 0, read);
                    continue;
                }
                if (inflater.needsDictionary()) {
                    throw new ProtoException("chunk patch requires a compression dictionary");
                }
                if (inflater.needsInput()) {
                    throw new ProtoException("truncated compressed chunk patch");
                }
                throw new ProtoException("compressed chunk patch made no progress");
            }
            if (inflater.getRemaining() != 0) {
                throw new ProtoException("trailing compressed chunk patch bytes");
            }
            return raw.toByteArray();
        } catch (final DataFormatException e) {
            throw new ProtoException("malformed compressed chunk patch", e);
        } finally {
            inflater.end();
        }
    }

    private static int[] readUnsignedBytePlane(final DataInputStream in, final int count) throws IOException {
        final int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            values[i] = in.readUnsignedByte();
        }
        return values;
    }

    private static long[] unknownRevisions(final int count) {
        final long[] revisions = new long[count];
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
        writeUnsignedVarint(out, (value << 1) ^ (value >> 31));
    }

    private static int readZigzagVarint(final DataInputStream in) throws IOException, ProtoException {
        final int encoded = readUnsignedVarint(in, MAX_DELTA_HEIGHT_BYTES);
        return (encoded >>> 1) ^ -(encoded & 1);
    }

    private static void writeUnsignedVarint(final DataOutputStream out, int value) throws IOException {
        do {
            final int next = value & 0x7F;
            value >>>= 7;
            out.writeByte(value == 0 ? next : next | 0x80);
        } while (value != 0);
    }

    private static int readUnsignedVarint(
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

    private static int unsignedVarintBytes(int value) {
        int bytes = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            bytes++;
        }
        return bytes;
    }

    private static boolean hasBit(final byte[] bits, final int index) {
        return (bits[index >>> 3] & (1 << (index & 7))) != 0;
    }

    private static void requireUnusedBitsClear(final byte[] bits, final int bitCount) {
        if (!unusedBitsClear(bits, bitCount)) {
            throw new IllegalArgumentException("mask contains bits outside its declared size");
        }
    }

    private static boolean unusedBitsClear(final byte[] bits, final int bitCount) {
        if (bits.length == 0 || (bitCount & 7) == 0) {
            return true;
        }
        final int allowed = (1 << (bitCount & 7)) - 1;
        return (bits[bits.length - 1] & ~allowed) == 0;
    }

    private static void checkIndex(final int index, final int size, final String label) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException(label + " index outside patch");
        }
    }

    private static long hashSample(long hash, final PatchCodec.Sample sample) {
        hash = fnv1a(hash, sample.biomeId());
        hash = fnv1aInt(hash, sample.surfaceY());
        hash = fnv1a(hash, sample.kind());
        hash = fnv1a(hash, sample.mapColorId());
        hash = fnv1a(hash, sample.fluidDepth());
        return fnv1a(hash, sample.floorMapColorId());
    }

    private static long fnv1aInt(long hash, final int value) {
        for (int shift = 0; shift < Integer.SIZE; shift += Byte.SIZE) {
            hash = fnv1a(hash, value >>> shift);
        }
        return hash;
    }

    private static long fnv1aLong(long hash, final long value) {
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            hash = fnv1a(hash, (int) (value >>> shift));
        }
        return hash;
    }

    private static long fnv1a(final long hash, final int value) {
        return (hash ^ (value & 0xFFL)) * 0x100000001b3L;
    }

    private static long normalizeRevision(final long revision) {
        return revision == Long.MIN_VALUE ? Long.MAX_VALUE : revision;
    }
}
