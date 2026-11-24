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
import java.util.Map;

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
    public static final int FORMAT_VERSION = 6;
    public static final int MATERIAL_FORMAT_VERSION = 5;
    public static final int SOURCE_LIGHT_FORMAT_VERSION = 4;
    public static final int LEGACY_FORMAT_VERSION = 3;
    public static final int PIXELS = 256 * 256;
    public static final int MASK_BYTES = PIXELS / 8;
    public static final int COARSE_MASK_BYTES = 32;
    public static final int FINE_MASK_BYTES = 32;
    public static final int MAX_SPARSE_MASK_BYTES = COARSE_MASK_BYTES + 256 * FINE_MASK_BYTES;
    public static final int RECORD_BYTES = 7;
    public static final int MAX_MATERIALS = 4_096;
    public static final int MAX_MATERIAL_BYTES = 256;
    private static final int MAX_DELTA_HEIGHT_BYTES = 3;
    public static final int MAX_RAW_BYTES =
        1 + MAX_SPARSE_MASK_BYTES * 2
            + PIXELS * (RECORD_BYTES - 2 + MAX_DELTA_HEIGHT_BYTES + 15)
            + MAX_MATERIALS * (2 + MAX_MATERIAL_BYTES) + 2;
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

        public Sample(
            final int pixelIndex,
            final int biomeId,
            final int surfaceY,
            final int kind,
            final int mapColorId,
            final int fluidDepth,
            final int floorMapColorId,
            final String materialId,
            final String floorMaterialId
        ) {
            this(pixelIndex, new MapPixel(
                biomeId, surfaceY, kind, mapColorId, fluidDepth, floorMapColorId,
                materialId, floorMaterialId
            ));
        }

        public Sample(
            final int pixelIndex,
            final int biomeId,
            final int surfaceY,
            final int kind,
            final int mapColorId,
            final int fluidDepth,
            final int floorMapColorId,
            final String materialId,
            final String floorMaterialId,
            final String overlayMaterialId
        ) {
            this(pixelIndex, new MapPixel(
                biomeId, surfaceY, kind, mapColorId, fluidDepth, floorMapColorId,
                materialId, floorMaterialId, overlayMaterialId
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

        public String materialId() {
            return pixel.materialId();
        }

        public String floorMaterialId() {
            return pixel.floorMaterialId();
        }

        public String overlayMaterialId() {
            return pixel.overlayMaterialId();
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
                if (!PatchWire.hasBit(evaluated, sample.pixelIndex())) {
                    throw new IllegalArgumentException("difference pixel was not evaluated: " + sample.pixelIndex());
                }
                seen[sample.pixelIndex()] = true;
            }
            for (int pixel = 0; pixel < PIXELS; pixel++) {
                final int light = blockLight[pixel] & 0xFF;
                if (light > 15) {
                    throw new IllegalArgumentException("block light outside 0..15 at pixel " + pixel);
                }
                if (!PatchWire.hasBit(evaluated, pixel)
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
            return PatchWire.hasBit(evaluated, pixelIndex);
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

    public static byte[] encodeMaterial(final Patch patch) {
        return encode(patch, MATERIAL_FORMAT_VERSION);
    }

    public static byte[] encodeLegacy(final Patch patch) {
        return encode(patch, LEGACY_FORMAT_VERSION);
    }

    public static byte[] encodeSourceLight(final Patch patch) {
        return encode(patch, SOURCE_LIGHT_FORMAT_VERSION);
    }

    private static byte[] encode(final Patch patch, final int formatVersion) {
        if (patch == null) {
            throw new IllegalArgumentException("patch is null");
        }
        final Sample[] byPixel = new Sample[PIXELS];
        final byte[] difference = new byte[MASK_BYTES];
        for (final Sample sample : patch.samples()) {
            byPixel[sample.pixelIndex()] = sample;
            PatchWire.setBit(difference, sample.pixelIndex());
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
                PatchWire.writeZigzagVarint(out, sample.surfaceY() - previousY);
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
            final Map<String, Integer> materials = PatchWire.materialTable(ordered);
            if (formatVersion >= MATERIAL_FORMAT_VERSION) {
                out.writeShort(materials.size());
                for (final String material : materials.keySet()) {
                    PatchWire.writeMaterial(out, material);
                }
                for (final Sample sample : ordered) {
                    out.writeShort(materials.get(sample.materialId()));
                }
                for (final Sample sample : ordered) {
                    out.writeShort(materials.get(sample.floorMaterialId()));
                }
            }
            if (formatVersion >= FORMAT_VERSION) {
                for (final Sample sample : ordered) {
                    out.writeShort(materials.get(sample.overlayMaterialId()));
                }
            }
            if (formatVersion >= SOURCE_LIGHT_FORMAT_VERSION) {
                long previousRevision = 0L;
                for (int pixel = 0; pixel < PIXELS; pixel++) {
                    if (!patch.evaluatedAt(pixel)) {
                        continue;
                    }
                    final long revision = patch.sourceRevisions[pixel];
                    PatchWire.writeZigzagVarLong(out, revision - previousRevision);
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
            return PatchWire.deflate(raw, MAX_COMPRESSED_BYTES);
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
        final byte[] raw = PatchWire.inflate(body, MAX_RAW_BYTES);
        try {
            final DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
            final int version = in.readUnsignedByte();
            if (version != FORMAT_VERSION && version != MATERIAL_FORMAT_VERSION
                && version != SOURCE_LIGHT_FORMAT_VERSION && version != LEGACY_FORMAT_VERSION) {
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
                if (PatchWire.hasBit(difference, pixel)) {
                    pixels[next++] = pixel;
                }
            }
            final PatchWire.ValuePlanes planes = PatchWire.readValuePlanes(
                in, count, version >= MATERIAL_FORMAT_VERSION, version >= FORMAT_VERSION
            );
            final long[] sourceRevisions = unknownRevisions();
            final byte[] blockLight = new byte[PIXELS];
            if (version >= SOURCE_LIGHT_FORMAT_VERSION) {
                long previousRevision = 0L;
                for (int pixel = 0; pixel < PIXELS; pixel++) {
                    if (!PatchWire.hasBit(evaluated, pixel)) {
                        continue;
                    }
                    previousRevision += PatchWire.readZigzagVarLong(in);
                    sourceRevisions[pixel] = previousRevision;
                }
                for (int pixel = 0; pixel < PIXELS; pixel++) {
                    if (!PatchWire.hasBit(evaluated, pixel)) {
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
                    planes.biomes()[i],
                    planes.surfaceYs()[i],
                    planes.kinds()[i],
                    planes.mapColors()[i],
                    planes.fluidDepths()[i],
                    planes.floorMapColors()[i],
                    planes.materialIds()[i],
                    planes.floorMaterialIds()[i],
                    planes.overlayMaterialIds()[i]
                ));
            }
            return new Patch(evaluated, samples, sourceRevisions, blockLight);
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
        PatchWire.setBit(evaluated, pixelIndex);
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
            PatchWire.setBit(result, sample.pixelIndex());
        }
        return result;
    }

    private static void writeSparseMask(final DataOutputStream out, final byte[] pixels) throws IOException {
        final byte[] coarse = new byte[COARSE_MASK_BYTES];
        final byte[][] fine = new byte[256][];
        for (int pixel = 0; pixel < PIXELS; pixel++) {
            if (!PatchWire.hasBit(pixels, pixel)) {
                continue;
            }
            final int x = pixel & 255;
            final int z = pixel >>> 8;
            final int coarseIndex = (z >>> 4) * 16 + (x >>> 4);
            final int fineIndex = (z & 15) * 16 + (x & 15);
            PatchWire.setBit(coarse, coarseIndex);
            if (fine[coarseIndex] == null) {
                fine[coarseIndex] = new byte[FINE_MASK_BYTES];
            }
            PatchWire.setBit(fine[coarseIndex], fineIndex);
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
            if (!PatchWire.hasBit(coarse, coarseIndex)) {
                continue;
            }
            final byte[] fine = new byte[FINE_MASK_BYTES];
            in.readFully(fine);
            for (int fineIndex = 0; fineIndex < 256; fineIndex++) {
                if (!PatchWire.hasBit(fine, fineIndex)) {
                    continue;
                }
                final int x = ((coarseIndex & 15) << 4) | (fineIndex & 15);
                final int z = ((coarseIndex >>> 4) << 4) | (fineIndex >>> 4);
                PatchWire.setBit(pixels, (z << 8) | x);
            }
        }
        return pixels;
    }

    private static long[] unknownRevisions() {
        final long[] revisions = new long[PIXELS];
        java.util.Arrays.fill(revisions, Long.MIN_VALUE);
        return revisions;
    }

    private static void checkPixel(final int pixelIndex) {
        if (pixelIndex < 0 || pixelIndex >= PIXELS) {
            throw new IndexOutOfBoundsException("pixel index outside tile: " + pixelIndex);
        }
    }
}
