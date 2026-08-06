package cn.net.rms.confluxmap.core.net;

import cn.net.rms.confluxmap.core.model.MapPixel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/** Disk codec for server-side region summaries ({@code .cfs}). */
public final class SummaryCodec {
    public static final byte[] MAGIC = {'C', 'F', 'S', 'M'};
    /**
     * Version 5 carries real registry map colours instead of the natural/artificial heuristic ids.
     * Version 6 promotes collision-less snow cover to the surface (snowy columns no longer
     * summarize as the green land beneath) and reports ice fluid depth from the ground under the
     * cover, with land-borne ice carrying no fluid column. Version 7 keeps the submerged floor's
     * map colour separate from the water/ice surface. Version 8 invalidates live-chunk summaries
     * created with inclusive top-block Y values. Version 9 adds the surface block-light plane.
     */
    public static final int FORMAT_VERSION = 10;
    public static final int CHUNKS = 256;
    public static final int COLUMNS = 256;
    public static final int RECORD_BYTES = 8;
    public static final int MAX_RAW_BYTES = CHUNKS * COLUMNS * RECORD_BYTES;

    private SummaryCodec() {
    }

    public record Column(
        int biomeId,
        int surfaceY,
        int kind,
        int mapColorId,
        int fluidDepth,
        int floorMapColorId,
        int blockLight
    ) {
        public Column {
            new MapPixel(biomeId, surfaceY, kind, mapColorId, fluidDepth, floorMapColorId);
            if (blockLight < 0 || blockLight > 15) {
                throw new IllegalArgumentException("block light outside 0..15: " + blockLight);
            }
        }

        public Column(
            final int biomeId,
            final int surfaceY,
            final int kind,
            final int mapColorId,
            final int fluidDepth,
            final int floorMapColorId
        ) {
            this(biomeId, surfaceY, kind, mapColorId, fluidDepth, floorMapColorId, 0);
        }

        public Column(
            final int biomeId,
            final int surfaceY,
            final int kind,
            final int mapColorId,
            final int fluidDepth
        ) {
            this(biomeId, surfaceY, kind, mapColorId, fluidDepth, MapPixel.MAP_COLOR_NONE, 0);
        }

        public MapPixel pixel() {
            return new MapPixel(biomeId, surfaceY, kind, mapColorId, fluidDepth, floorMapColorId);
        }
    }

    public record Chunk(boolean generated, long revision, Column[] columns) {
        public Chunk {
            if (columns == null || columns.length != COLUMNS) {
                throw new IllegalArgumentException("summary chunk must contain 256 columns");
            }
            columns = columns.clone();
        }

        public static Chunk empty() {
            return new Chunk(false, 0L, new Column[COLUMNS]);
        }
    }

    public record Region(int rx, int rz, long sourceMcaMtimeMs, Chunk[] chunks) {
        public Region {
            if (chunks == null || chunks.length != CHUNKS) {
                throw new IllegalArgumentException("summary region must contain 256 chunks");
            }
            chunks = chunks.clone();
        }
    }

    /**
     * One region's chunk-generation flags, read without inflating the column body.
     *
     * @param maxRevision highest revision among generated chunks, 0 when the region has none
     */
    public record Generated(int rx, int rz, long sourceMcaMtimeMs, boolean[] flags, long maxRevision) {
        public Generated {
            if (flags == null || flags.length != CHUNKS) {
                throw new IllegalArgumentException("summary region must contain 256 chunk flags");
            }
            flags = flags.clone();
        }
    }

    /**
     * The centered source columns that one output LOD can actually publish from a chunk.
     * A stride of 16 carries one column; a stride of 8 carries four.
     */
    public record SampledChunk(boolean generated, long revision, int sampleStride, Column[] columns) {
        public SampledChunk {
            final int samplesPerSide = SummaryCodec.samplesPerSide(sampleStride);
            if (columns == null || columns.length != samplesPerSide * samplesPerSide) {
                throw new IllegalArgumentException("sampled chunk has the wrong column count");
            }
            if (generated) {
                for (final Column column : columns) {
                    if (column == null) {
                        throw new IllegalArgumentException("generated sampled chunk contains a null column");
                    }
                }
            }
            columns = columns.clone();
        }

        public int samplesPerSide() {
            return SummaryCodec.samplesPerSide(sampleStride);
        }

        public Column column(final int sampleX, final int sampleZ) {
            final int side = samplesPerSide();
            if (sampleX < 0 || sampleX >= side || sampleZ < 0 || sampleZ >= side) {
                throw new IndexOutOfBoundsException("sample coordinate outside chunk");
            }
            return columns[sampleZ * side + sampleX];
        }

        public static SampledChunk empty(final int sampleStride) {
            return empty(sampleStride, 0L);
        }

        public static SampledChunk empty(final int sampleStride, final long revision) {
            final int side = SummaryCodec.samplesPerSide(sampleStride);
            return new SampledChunk(false, revision, sampleStride, new Column[side * side]);
        }
    }

    /** One region decoded at the centered column density required by an output LOD. */
    public record SampledRegion(
        int rx,
        int rz,
        long sourceMcaMtimeMs,
        int sampleStride,
        SampledChunk[] chunks
    ) {
        public SampledRegion {
            samplesPerSide(sampleStride);
            if (chunks == null || chunks.length != CHUNKS) {
                throw new IllegalArgumentException("sampled summary region must contain 256 chunks");
            }
            for (final SampledChunk chunk : chunks) {
                if (chunk == null || chunk.sampleStride() != sampleStride) {
                    throw new IllegalArgumentException("sampled summary chunk stride mismatch");
                }
            }
            chunks = chunks.clone();
        }
    }

    /** Fixed-size prefix every {@code .cfs} file starts with, ahead of the deflated columns. */
    private record Header(int rx, int rz, long mtime, boolean[] generated, long[] revisions) {
    }

    public static byte[] encode(final Region region) {
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            encode(out, region);
            return out.toByteArray();
        } catch (final IOException e) {
            throw new IllegalStateException("in-memory summary encoding failed", e);
        }
    }

    public static void encode(final OutputStream destination, final Region region) throws IOException {
        final DataOutputStream out = new DataOutputStream(destination);
        out.write(MAGIC);
        out.writeByte(FORMAT_VERSION);
        out.writeInt(region.rx());
        out.writeInt(region.rz());
        out.writeLong(region.sourceMcaMtimeMs());
        for (final Chunk chunk : region.chunks()) {
            out.writeByte(chunk.generated() ? 1 : 0);
            out.writeLong(chunk.revision());
        }
        final ByteArrayOutputStream raw = new ByteArrayOutputStream(MAX_RAW_BYTES);
        final DataOutputStream columns = new DataOutputStream(raw);
        for (final Chunk chunk : region.chunks()) {
            if (!chunk.generated()) {
                continue;
            }
            for (final Column column : chunk.columns()) {
                if (column == null) {
                    throw new IllegalArgumentException("generated chunk contains null column");
                }
                columns.writeByte(column.biomeId());
                columns.writeShort(column.surfaceY());
                columns.writeByte(column.kind());
                columns.writeByte(column.mapColorId());
                columns.writeByte(column.fluidDepth());
                columns.writeByte(column.floorMapColorId());
                columns.writeByte(column.blockLight());
            }
        }
        columns.flush();
        final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try {
            final DeflaterOutputStream compressed = new DeflaterOutputStream(out, deflater, 8192);
            compressed.write(raw.toByteArray());
            compressed.finish();
        } finally {
            deflater.end();
        }
        out.flush();
    }

    public static Region decode(final byte[] bytes) throws ProtoException {
        try {
            return decode(new ByteArrayInputStream(bytes));
        } catch (final IOException e) {
            throw new ProtoException("invalid summary stream", e);
        }
    }

    public static Region decode(final InputStream source) throws IOException, ProtoException {
        final Header header = readHeader(new DataInputStream(source));
        final int rx = header.rx();
        final int rz = header.rz();
        final long mtime = header.mtime();
        final boolean[] generated = header.generated();
        final long[] revisions = header.revisions();
        int generatedCount = 0;
        for (final boolean flag : generated) {
            generatedCount += flag ? 1 : 0;
        }
        final byte[] raw = inflate(source, generatedCount * COLUMNS * RECORD_BYTES);
        final DataInputStream columns = new DataInputStream(new ByteArrayInputStream(raw));
        final Chunk[] chunks = new Chunk[CHUNKS];
        for (int chunkIndex = 0; chunkIndex < CHUNKS; chunkIndex++) {
            final Column[] values = new Column[COLUMNS];
            if (generated[chunkIndex]) {
                for (int column = 0; column < COLUMNS; column++) {
                    values[column] = new Column(columns.readUnsignedByte(), columns.readShort(), columns.readUnsignedByte(),
                        columns.readUnsignedByte(), columns.readUnsignedByte(), columns.readUnsignedByte(),
                        columns.readUnsignedByte());
                }
            }
            chunks[chunkIndex] = new Chunk(generated[chunkIndex], revisions[chunkIndex], values);
        }
        if (columns.available() != 0) {
            throw new ProtoException("trailing summary body bytes: " + columns.available());
        }
        return new Region(rx, rz, mtime, chunks);
    }

    /**
     * Decodes only the centered columns consumed by an output LOD. The compressed body is still
     * validated in full, but unused records never become {@link Column} objects.
     */
    public static SampledRegion decodeSampled(
        final InputStream source,
        final int sampleStride
    ) throws IOException, ProtoException {
        final int side = samplesPerSide(sampleStride);
        final Header header = readHeader(new DataInputStream(source));
        int generatedCount = 0;
        for (final boolean flag : header.generated()) {
            generatedCount += flag ? 1 : 0;
        }
        final byte[] raw = inflate(source, generatedCount * COLUMNS * RECORD_BYTES);
        final SampledChunk[] chunks = new SampledChunk[CHUNKS];
        int generatedIndex = 0;
        for (int chunkIndex = 0; chunkIndex < CHUNKS; chunkIndex++) {
            if (!header.generated()[chunkIndex]) {
                chunks[chunkIndex] = SampledChunk.empty(
                    sampleStride, header.revisions()[chunkIndex]
                );
                continue;
            }
            final Column[] sampled = new Column[side * side];
            final int chunkOffset = generatedIndex * COLUMNS * RECORD_BYTES;
            int sampleIndex = 0;
            for (int sampleZ = 0; sampleZ < side; sampleZ++) {
                final int columnZ = sampleZ * sampleStride + (sampleStride >>> 1);
                for (int sampleX = 0; sampleX < side; sampleX++) {
                    final int columnX = sampleX * sampleStride + (sampleStride >>> 1);
                    final int columnIndex = columnZ * 16 + columnX;
                    sampled[sampleIndex++] = decodeColumn(raw, chunkOffset + columnIndex * RECORD_BYTES);
                }
            }
            chunks[chunkIndex] = new SampledChunk(
                true, header.revisions()[chunkIndex], sampleStride, sampled
            );
            generatedIndex++;
        }
        return new SampledRegion(
            header.rx(), header.rz(), header.mtime(), sampleStride, chunks
        );
    }

    /** Samples an in-memory full summary with the same centered coordinates as {@link #decodeSampled}. */
    public static SampledChunk sample(final Chunk chunk, final int sampleStride) {
        final int side = samplesPerSide(sampleStride);
        if (chunk == null) {
            return SampledChunk.empty(sampleStride);
        }
        if (!chunk.generated()) {
            return SampledChunk.empty(sampleStride, chunk.revision());
        }
        final Column[] sampled = new Column[side * side];
        int sampleIndex = 0;
        for (int sampleZ = 0; sampleZ < side; sampleZ++) {
            final int columnZ = sampleZ * sampleStride + (sampleStride >>> 1);
            for (int sampleX = 0; sampleX < side; sampleX++) {
                final int columnX = sampleX * sampleStride + (sampleStride >>> 1);
                sampled[sampleIndex++] = chunk.columns()[columnZ * 16 + columnX];
            }
        }
        return new SampledChunk(true, chunk.revision(), sampleStride, sampled);
    }

    /**
     * Reads only the chunk-generation flags, leaving the deflated column body untouched.
     *
     * <p>A coarse presence bitmap needs nothing else, and one LOD-4 prediction tile spans 256
     * regions: decoding their columns would allocate ~16 million records to produce 32 bytes.
     */
    public static Generated decodeGenerated(final InputStream source) throws IOException, ProtoException {
        final Header header = readHeader(new DataInputStream(source));
        long maxRevision = 0L;
        for (int i = 0; i < CHUNKS; i++) {
            if (header.generated()[i]) {
                maxRevision = Math.max(maxRevision, header.revisions()[i]);
            }
        }
        return new Generated(header.rx(), header.rz(), header.mtime(), header.generated(), maxRevision);
    }

    private static Column decodeColumn(final byte[] raw, final int offset) {
        final int surfaceY = (short) (((raw[offset + 1] & 255) << 8) | (raw[offset + 2] & 255));
        return new Column(
            raw[offset] & 255,
            surfaceY,
            raw[offset + 3] & 255,
            raw[offset + 4] & 255,
            raw[offset + 5] & 255,
            raw[offset + 6] & 255,
            raw[offset + 7] & 255
        );
    }

    private static int samplesPerSide(final int sampleStride) {
        if (sampleStride <= 0 || sampleStride > 16 || 16 % sampleStride != 0
            || (sampleStride & (sampleStride - 1)) != 0) {
            throw new IllegalArgumentException("sample stride must be a power of two from 1 to 16");
        }
        return 16 / sampleStride;
    }

    private static Header readHeader(final DataInputStream in) throws IOException, ProtoException {
        final byte[] magic = new byte[MAGIC.length];
        in.readFully(magic);
        if (!Arrays.equals(MAGIC, magic)) {
            throw new ProtoException("bad summary magic");
        }
        final int version = in.readUnsignedByte();
        if (version != FORMAT_VERSION) {
            throw new ProtoException("unsupported summary version " + version);
        }
        final int rx = in.readInt();
        final int rz = in.readInt();
        final long mtime = in.readLong();
        final boolean[] generated = new boolean[CHUNKS];
        final long[] revisions = new long[CHUNKS];
        for (int i = 0; i < CHUNKS; i++) {
            final int flags = in.readUnsignedByte();
            if ((flags & ~1) != 0) {
                throw new ProtoException("unknown summary flags " + flags);
            }
            generated[i] = (flags & 1) != 0;
            revisions[i] = in.readLong();
        }
        return new Header(rx, rz, mtime, generated, revisions);
    }

    private static byte[] inflate(final InputStream source, final int expectedMax) throws IOException, ProtoException {
        if (expectedMax > MAX_RAW_BYTES) {
            throw new ProtoException("summary body exceeds cap");
        }
        final Inflater inflater = new Inflater();
        try {
            final InflaterInputStream compressed = new InflaterInputStream(source, inflater, 8192);
            final ByteArrayOutputStream raw = new ByteArrayOutputStream(expectedMax);
            final byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = compressed.read(buffer)) != -1) {
                total += read;
                if (total > expectedMax) {
                    throw new ProtoException("summary body has more bytes than generated chunks");
                }
                raw.write(buffer, 0, read);
            }
            if (total != expectedMax) {
                throw new ProtoException("summary body truncated: expected " + expectedMax + ", got " + total);
            }
            return raw.toByteArray();
        } finally {
            inflater.end();
        }
    }
}
