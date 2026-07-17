package cn.net.rms.confluxmap.core.cache;

import cn.net.rms.confluxmap.core.store.RegionColumns;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Binary format for {@code r.<rx>.<rz>.cfr} region cache files (see the plan's
 * 磁盘缓存 decision and S6). A file is three back-to-back sections written to the
 * same stream, uncompressed header/table followed by a compressed body:
 *
 * <pre>
 * [0, HEADER_SIZE)                                    32B fixed header, raw
 * [HEADER_SIZE, HEADER_SIZE + CHUNK_TABLE_SIZE)        256-entry chunk table, raw
 * [HEADER_SIZE + CHUNK_TABLE_SIZE, EOF)                Deflate stream of 65536 column records
 * </pre>
 *
 * <p>Column record layout is {@code i16 surfaceY, u8 fluidDepth, u8 kind, i32 baseArgb,
 * i32 biomeTint, i32 overlayArgb, u8 light} - 17 bytes/column, matching {@link RegionColumns}'
 * seven per-column arrays one-for-one ({@code biomeTint} here is {@code RegionColumns.tintArgb} /
 * {@code ChunkSnapshot.tintArgb} under the format's own name). Note: the implementation plan
 * originally described this record as "17B/col" back when it only listed 6 fields (summing
 * those alone gives 16); {@code light} is the field that makes the count actually match.
 *
 * <p>{@code light} was added in {@link #SCHEMA_VERSION} 2 (day/night dynamic lighting for the
 * SURFACE layer). {@link #decode} still accepts {@link #SCHEMA_VERSION_NO_LIGHT} files written
 * before that - their column records are one byte shorter (no trailing {@code light} byte) and
 * every column is filled with {@link #DEFAULT_LIGHT_FULL_BRIGHT} instead, so previously-cached
 * terrain doesn't render pitch-black at night until it's recaptured.
 *
 * <p>All multi-byte integers are big-endian (plain {@link DataOutputStream}/
 * {@link DataInputStream} semantics). This class only encodes/decodes streams; file
 * placement, atomic writes and corruption quarantine are {@link RegionDiskCache}'s job.
 */
public final class RegionFileCodec {
    public static final byte[] MAGIC = {'C', 'F', 'R', 'M'};
    public static final int FORMAT_VERSION = 1;
    public static final int SCHEMA_VERSION = 2;
    /** The pre-{@code light}-plane schema; still readable, see this class's javadoc. */
    public static final int SCHEMA_VERSION_NO_LIGHT = 1;
    /** Backfilled {@code light} value for {@link #SCHEMA_VERSION_NO_LIGHT} files: full bright, never darkens at night. */
    public static final byte DEFAULT_LIGHT_FULL_BRIGHT = 15;
    /** Discriminates the on-disk record family; 0 = the fixed-width column layout described above. */
    public static final int SOURCE_CLASS = 0;

    public static final int HEADER_SIZE = 32;
    private static final int HEADER_PAYLOAD_SIZE = MAGIC.length + 4 + 4 + 4 + 8; // magic + 4x u8 + rx + rz + epochMs
    private static final int HEADER_RESERVED_SIZE = HEADER_SIZE - HEADER_PAYLOAD_SIZE;

    public static final int CHUNK_TABLE_ENTRIES = RegionColumns.CHUNKS * RegionColumns.CHUNKS;
    private static final int CHUNK_TABLE_ENTRY_SIZE = 1 + 4; // u8 sourceOrdinal + u32 lastUpdateEpochSeconds
    public static final int CHUNK_TABLE_SIZE = CHUNK_TABLE_ENTRIES * CHUNK_TABLE_ENTRY_SIZE;

    public static final int COLUMN_COUNT = RegionColumns.SIZE * RegionColumns.SIZE;
    public static final int COLUMN_RECORD_SIZE = 2 + 1 + 1 + 4 + 4 + 4 + 1;

    private RegionFileCodec() {
    }

    /** Corrupt, foreign, or version/coordinate-mismatched region file. Callers should quarantine and treat as empty. */
    public static final class RegionFileException extends Exception {
        public RegionFileException(final String message) {
            super(message);
        }
    }

    /**
     * Plain data carrier for one region's worth of decoded/to-be-encoded columns. Deliberately
     * not {@link RegionColumns} itself, so the codec has no locking/threading concerns and is
     * trivial to unit test with hand-built arrays.
     */
    public record RegionData(
        int rx,
        int rz,
        long lastWriteEpochMs,
        byte[] chunkSourceOrdinal,
        int[] chunkUpdateEpochSeconds,
        short[] surfaceY,
        byte[] fluidDepth,
        byte[] kind,
        int[] baseArgb,
        int[] biomeTint,
        int[] overlayArgb,
        byte[] light
    ) {
        public RegionData {
            requireLength("chunkSourceOrdinal", chunkSourceOrdinal.length, CHUNK_TABLE_ENTRIES);
            requireLength("chunkUpdateEpochSeconds", chunkUpdateEpochSeconds.length, CHUNK_TABLE_ENTRIES);
            requireLength("surfaceY", surfaceY.length, COLUMN_COUNT);
            requireLength("fluidDepth", fluidDepth.length, COLUMN_COUNT);
            requireLength("kind", kind.length, COLUMN_COUNT);
            requireLength("baseArgb", baseArgb.length, COLUMN_COUNT);
            requireLength("biomeTint", biomeTint.length, COLUMN_COUNT);
            requireLength("overlayArgb", overlayArgb.length, COLUMN_COUNT);
            requireLength("light", light.length, COLUMN_COUNT);
        }

        private static void requireLength(final String name, final int actual, final int expected) {
            if (actual != expected) {
                throw new IllegalArgumentException(name + " must have " + expected + " entries, got " + actual);
            }
        }
    }

    /**
     * Writes header + chunk table (raw) then the Deflate-compressed column body to {@code rawOut}.
     * Does not close {@code rawOut} - the caller (which owns the tmp-file/force/atomic-move dance)
     * keeps control of the stream's lifecycle.
     */
    public static void encode(final OutputStream rawOut, final int layerOrdinal, final RegionData data) throws IOException {
        final DataOutputStream header = new DataOutputStream(rawOut);
        header.write(MAGIC);
        header.writeByte(FORMAT_VERSION);
        header.writeByte(SCHEMA_VERSION);
        header.writeByte(SOURCE_CLASS);
        header.writeByte(layerOrdinal);
        header.writeInt(data.rx());
        header.writeInt(data.rz());
        header.writeLong(data.lastWriteEpochMs());
        header.write(new byte[HEADER_RESERVED_SIZE]);

        for (int i = 0; i < CHUNK_TABLE_ENTRIES; i++) {
            header.writeByte(data.chunkSourceOrdinal()[i]);
            header.writeInt(data.chunkUpdateEpochSeconds()[i]);
        }
        header.flush();

        final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try {
            // finish() flushes the compressed tail without closing rawOut, so the caller can
            // still fsync/close it afterward as part of the atomic-write sequence.
            final DeflaterOutputStream compressed = new DeflaterOutputStream(rawOut, deflater, 8192);
            final DataOutputStream columns = new DataOutputStream(compressed);
            for (int i = 0; i < COLUMN_COUNT; i++) {
                columns.writeShort(data.surfaceY()[i]);
                columns.writeByte(data.fluidDepth()[i]);
                columns.writeByte(data.kind()[i]);
                columns.writeInt(data.baseArgb()[i]);
                columns.writeInt(data.biomeTint()[i]);
                columns.writeInt(data.overlayArgb()[i]);
                columns.writeByte(data.light()[i]);
            }
            columns.flush();
            compressed.finish();
        } finally {
            deflater.end();
        }
    }

    /**
     * Reads and validates a region file from {@code rawIn}, throwing {@link RegionFileException}
     * if the magic, format/schema version, source class, or region coordinates don't match what
     * the caller expected. Truncated/otherwise malformed streams surface as a plain {@link IOException}
     * (typically {@link java.io.EOFException}); callers already treat both the same way (quarantine
     * and continue empty), so this only wraps validation-logic mismatches in the typed exception.
     */
    public static RegionData decode(
        final InputStream rawIn,
        final int expectedRx,
        final int expectedRz,
        final int expectedLayerOrdinal
    ) throws IOException, RegionFileException {
        final DataInputStream header = new DataInputStream(rawIn);
        final byte[] magic = new byte[MAGIC.length];
        header.readFully(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new RegionFileException("bad magic: " + Arrays.toString(magic));
        }
        final int formatVersion = header.readUnsignedByte();
        if (formatVersion != FORMAT_VERSION) {
            throw new RegionFileException("unsupported format version " + formatVersion);
        }
        final int schemaVersion = header.readUnsignedByte();
        if (schemaVersion != SCHEMA_VERSION && schemaVersion != SCHEMA_VERSION_NO_LIGHT) {
            throw new RegionFileException("unsupported schema version " + schemaVersion);
        }
        final int sourceClass = header.readUnsignedByte();
        if (sourceClass != SOURCE_CLASS) {
            throw new RegionFileException("unsupported source class " + sourceClass);
        }
        final int layerOrdinal = header.readUnsignedByte();
        if (layerOrdinal != expectedLayerOrdinal) {
            throw new RegionFileException("layer mismatch: file has " + layerOrdinal + ", expected " + expectedLayerOrdinal);
        }
        final int rx = header.readInt();
        final int rz = header.readInt();
        if (rx != expectedRx || rz != expectedRz) {
            throw new RegionFileException(
                "region coordinate mismatch: file has r." + rx + "." + rz + ", expected r." + expectedRx + "." + expectedRz
            );
        }
        final long lastWriteEpochMs = header.readLong();
        header.readFully(new byte[HEADER_RESERVED_SIZE]);

        final byte[] chunkSourceOrdinal = new byte[CHUNK_TABLE_ENTRIES];
        final int[] chunkUpdateEpochSeconds = new int[CHUNK_TABLE_ENTRIES];
        for (int i = 0; i < CHUNK_TABLE_ENTRIES; i++) {
            chunkSourceOrdinal[i] = header.readByte();
            chunkUpdateEpochSeconds[i] = header.readInt();
        }

        final short[] surfaceY = new short[COLUMN_COUNT];
        final byte[] fluidDepth = new byte[COLUMN_COUNT];
        final byte[] kind = new byte[COLUMN_COUNT];
        final int[] baseArgb = new int[COLUMN_COUNT];
        final int[] biomeTint = new int[COLUMN_COUNT];
        final int[] overlayArgb = new int[COLUMN_COUNT];
        final byte[] light = new byte[COLUMN_COUNT];
        final boolean hasLight = schemaVersion == SCHEMA_VERSION;
        final DataInputStream columns = new DataInputStream(new InflaterInputStream(rawIn, new Inflater(), 8192));
        for (int i = 0; i < COLUMN_COUNT; i++) {
            surfaceY[i] = columns.readShort();
            fluidDepth[i] = columns.readByte();
            kind[i] = columns.readByte();
            baseArgb[i] = columns.readInt();
            biomeTint[i] = columns.readInt();
            overlayArgb[i] = columns.readInt();
            light[i] = hasLight ? columns.readByte() : DEFAULT_LIGHT_FULL_BRIGHT;
        }

        return new RegionData(
            rx, rz, lastWriteEpochMs, chunkSourceOrdinal, chunkUpdateEpochSeconds,
            surfaceY, fluidDepth, kind, baseArgb, biomeTint, overlayArgb, light
        );
    }
}
