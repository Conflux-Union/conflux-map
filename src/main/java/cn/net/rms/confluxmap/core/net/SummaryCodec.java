package cn.net.rms.confluxmap.core.net;

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
import java.util.zip.InflaterInputStream;

/** Disk codec for server-side region summaries ({@code .cfs}). */
public final class SummaryCodec {
    public static final byte[] MAGIC = {'C', 'F', 'S', 'M'};
    /**
     * Version 5 carries real registry map colours instead of the natural/artificial heuristic ids.
     * Version 6 promotes collision-less snow cover to the surface (snowy columns no longer
     * summarize as the green land beneath) and reports ice fluid depth from the ground under the
     * cover, with land-borne ice carrying no fluid column.
     */
    public static final int FORMAT_VERSION = 6;
    public static final int CHUNKS = 256;
    public static final int COLUMNS = 256;
    public static final int RECORD_BYTES = 6;
    public static final int MAX_RAW_BYTES = CHUNKS * COLUMNS * RECORD_BYTES;

    private SummaryCodec() {
    }

    public record Column(int biomeId, int surfaceY, int kind, int mapColorId, int fluidDepth) {
        public Column {
            if (biomeId < 0 || biomeId > 255 || kind < 0 || kind > 255 || mapColorId < 0 || mapColorId > 255
                || fluidDepth < 0 || fluidDepth > 255 || surfaceY < Short.MIN_VALUE || surfaceY > Short.MAX_VALUE) {
                throw new IllegalArgumentException("summary column field outside wire range");
            }
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
                        columns.readUnsignedByte(), columns.readUnsignedByte());
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
        final InflaterInputStream compressed = new InflaterInputStream(source);
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
    }
}
