package cn.net.rms.confluxmap.core.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import org.junit.jupiter.api.Test;

/** Round-trip and corruption-handling coverage for {@link RegionFileCodec}. */
class RegionFileCodecTest {

    private static RegionFileCodec.RegionData sampleData(final int rx, final int rz) {
        final byte[] chunkSourceOrdinal = new byte[RegionFileCodec.CHUNK_TABLE_ENTRIES];
        final int[] chunkUpdateEpochSeconds = new int[RegionFileCodec.CHUNK_TABLE_ENTRIES];
        for (int i = 0; i < RegionFileCodec.CHUNK_TABLE_ENTRIES; i++) {
            chunkSourceOrdinal[i] = (byte) (i % 4);
            chunkUpdateEpochSeconds[i] = 1_700_000_000 + i;
        }

        final short[] surfaceY = new short[RegionFileCodec.COLUMN_COUNT];
        final byte[] fluidDepth = new byte[RegionFileCodec.COLUMN_COUNT];
        final byte[] kind = new byte[RegionFileCodec.COLUMN_COUNT];
        final int[] baseArgb = new int[RegionFileCodec.COLUMN_COUNT];
        final int[] biomeTint = new int[RegionFileCodec.COLUMN_COUNT];
        final int[] overlayArgb = new int[RegionFileCodec.COLUMN_COUNT];
        final byte[] light = new byte[RegionFileCodec.COLUMN_COUNT];
        for (int i = 0; i < RegionFileCodec.COLUMN_COUNT; i++) {
            surfaceY[i] = (short) (i % 400 - 64);
            fluidDepth[i] = (byte) (i % 17);
            kind[i] = (byte) (i % 9);
            baseArgb[i] = 0xFF000000 | (int) ((i * 2654435761L) & 0x00FFFFFF);
            biomeTint[i] = 0xFF445566 + i;
            overlayArgb[i] = i % 5 == 0 ? 0 : 0x80112233 + i;
            light[i] = (byte) (i % 16);
        }

        return new RegionFileCodec.RegionData(
            rx, rz, 1_700_000_123_456L,
            chunkSourceOrdinal, chunkUpdateEpochSeconds,
            surfaceY, fluidDepth, kind, baseArgb, biomeTint, overlayArgb, light
        );
    }

    @Test
    void encodeThenDecodeReproducesEveryArray() throws IOException, RegionFileCodec.RegionFileException {
        final int rx = 7;
        final int rz = -3;
        final int layerOrdinal = 0;
        final RegionFileCodec.RegionData original = sampleData(rx, rz);

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        RegionFileCodec.encode(out, layerOrdinal, original);

        final RegionFileCodec.RegionData decoded = RegionFileCodec.decode(
            new ByteArrayInputStream(out.toByteArray()), rx, rz, layerOrdinal
        );

        assertEquals(original.rx(), decoded.rx());
        assertEquals(original.rz(), decoded.rz());
        assertEquals(original.lastWriteEpochMs(), decoded.lastWriteEpochMs());
        assertArrayEquals(original.chunkSourceOrdinal(), decoded.chunkSourceOrdinal());
        assertArrayEquals(original.chunkUpdateEpochSeconds(), decoded.chunkUpdateEpochSeconds());
        assertArrayEquals(original.surfaceY(), decoded.surfaceY());
        assertArrayEquals(original.fluidDepth(), decoded.fluidDepth());
        assertArrayEquals(original.kind(), decoded.kind());
        assertArrayEquals(original.baseArgb(), decoded.baseArgb());
        assertArrayEquals(original.biomeTint(), decoded.biomeTint());
        assertArrayEquals(original.overlayArgb(), decoded.overlayArgb());
        assertArrayEquals(original.light(), decoded.light());
    }

    /**
     * A schema-1 file (written before {@code light} existed) must still load, with every
     * column's light backfilled to {@link RegionFileCodec#DEFAULT_LIGHT_FULL_BRIGHT} so
     * previously-cached terrain doesn't render pitch-black under the new night-darkening.
     */
    @Test
    void decodeSchemaOneFillsDefaultLight() throws IOException, RegionFileCodec.RegionFileException {
        final int rx = 4;
        final int rz = -8;
        final int layerOrdinal = 0;
        final RegionFileCodec.RegionData original = sampleData(rx, rz);

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        encodeLegacySchemaOne(out, layerOrdinal, original);

        final RegionFileCodec.RegionData decoded = RegionFileCodec.decode(
            new ByteArrayInputStream(out.toByteArray()), rx, rz, layerOrdinal
        );

        assertArrayEquals(original.surfaceY(), decoded.surfaceY());
        assertArrayEquals(original.baseArgb(), decoded.baseArgb());
        final byte[] expectedLight = new byte[RegionFileCodec.COLUMN_COUNT];
        java.util.Arrays.fill(expectedLight, RegionFileCodec.DEFAULT_LIGHT_FULL_BRIGHT);
        assertArrayEquals(expectedLight, decoded.light());
    }

    /**
     * Hand-rolls the pre-{@code light} on-disk layout (schema 1: 16-byte column records,
     * no trailing light byte) directly against the format constants, since {@link
     * RegionFileCodec#encode} only ever writes the current schema.
     */
    private static void encodeLegacySchemaOne(
        final ByteArrayOutputStream rawOut, final int layerOrdinal, final RegionFileCodec.RegionData data
    ) throws IOException {
        final DataOutputStream header = new DataOutputStream(rawOut);
        header.write(RegionFileCodec.MAGIC);
        header.writeByte(RegionFileCodec.FORMAT_VERSION);
        header.writeByte(RegionFileCodec.SCHEMA_VERSION_NO_LIGHT);
        header.writeByte(RegionFileCodec.SOURCE_CLASS);
        header.writeByte(layerOrdinal);
        header.writeInt(data.rx());
        header.writeInt(data.rz());
        header.writeLong(data.lastWriteEpochMs());
        header.write(new byte[RegionFileCodec.HEADER_SIZE - (RegionFileCodec.MAGIC.length + 4 + 4 + 4 + 8)]);

        for (int i = 0; i < RegionFileCodec.CHUNK_TABLE_ENTRIES; i++) {
            header.writeByte(data.chunkSourceOrdinal()[i]);
            header.writeInt(data.chunkUpdateEpochSeconds()[i]);
        }
        header.flush();

        final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try {
            final DeflaterOutputStream compressed = new DeflaterOutputStream(rawOut, deflater, 8192);
            final DataOutputStream columns = new DataOutputStream(compressed);
            for (int i = 0; i < RegionFileCodec.COLUMN_COUNT; i++) {
                columns.writeShort(data.surfaceY()[i]);
                columns.writeByte(data.fluidDepth()[i]);
                columns.writeByte(data.kind()[i]);
                columns.writeInt(data.baseArgb()[i]);
                columns.writeInt(data.biomeTint()[i]);
                columns.writeInt(data.overlayArgb()[i]);
            }
            columns.flush();
            compressed.finish();
        } finally {
            deflater.end();
        }
    }

    @Test
    void decodeRejectsBadMagic() throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        RegionFileCodec.encode(out, 0, sampleData(1, 1));
        final byte[] bytes = out.toByteArray();
        bytes[0] = 'X'; // corrupt the first magic byte

        assertThrows(RegionFileCodec.RegionFileException.class, () ->
            RegionFileCodec.decode(new ByteArrayInputStream(bytes), 1, 1, 0)
        );
    }

    @Test
    void decodeRejectsUnsupportedFormatVersion() throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        RegionFileCodec.encode(out, 0, sampleData(2, 2));
        final byte[] bytes = out.toByteArray();
        bytes[4] = 99; // formatVersion byte, right after the 4-byte magic

        assertThrows(RegionFileCodec.RegionFileException.class, () ->
            RegionFileCodec.decode(new ByteArrayInputStream(bytes), 2, 2, 0)
        );
    }

    @Test
    void decodeRejectsRegionCoordinateMismatch() throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        RegionFileCodec.encode(out, 0, sampleData(5, 9));

        assertThrows(RegionFileCodec.RegionFileException.class, () ->
            RegionFileCodec.decode(new ByteArrayInputStream(out.toByteArray()), 5, 10, 0)
        );
    }

    @Test
    void regionDataRejectsWrongArrayLengths() {
        assertThrows(IllegalArgumentException.class, () -> new RegionFileCodec.RegionData(
            0, 0, 0L,
            new byte[1], new int[RegionFileCodec.CHUNK_TABLE_ENTRIES],
            new short[RegionFileCodec.COLUMN_COUNT], new byte[RegionFileCodec.COLUMN_COUNT],
            new byte[RegionFileCodec.COLUMN_COUNT], new int[RegionFileCodec.COLUMN_COUNT],
            new int[RegionFileCodec.COLUMN_COUNT], new int[RegionFileCodec.COLUMN_COUNT],
            new byte[RegionFileCodec.COLUMN_COUNT]
        ));
    }
}
