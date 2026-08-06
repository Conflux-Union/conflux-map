package cn.net.rms.confluxmap.core.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/** Round-trip and corruption-handling coverage for {@link RegionFileCodec}. */
class RegionFileCodecTest {

    private static RegionFileCodec.RegionData sampleData(final int rx, final int rz) {
        final byte[] chunkSourceOrdinal = new byte[RegionFileCodec.CHUNK_TABLE_ENTRIES];
        final int[] chunkUpdateEpochSeconds = new int[RegionFileCodec.CHUNK_TABLE_ENTRIES];
        final long[] chunkSourceRevision = new long[RegionFileCodec.CHUNK_TABLE_ENTRIES];
        for (int i = 0; i < RegionFileCodec.CHUNK_TABLE_ENTRIES; i++) {
            chunkSourceOrdinal[i] = (byte) (i % 4);
            chunkUpdateEpochSeconds[i] = 1_700_000_000 + i;
            chunkSourceRevision[i] = 90_000L + i;
        }

        final short[] surfaceY = new short[RegionFileCodec.COLUMN_COUNT];
        final byte[] fluidDepth = new byte[RegionFileCodec.COLUMN_COUNT];
        final byte[] kind = new byte[RegionFileCodec.COLUMN_COUNT];
        final String[] biomeId = new String[RegionFileCodec.COLUMN_COUNT];
        final int[] baseArgb = new int[RegionFileCodec.COLUMN_COUNT];
        final int[] biomeTint = new int[RegionFileCodec.COLUMN_COUNT];
        final int[] overlayArgb = new int[RegionFileCodec.COLUMN_COUNT];
        final byte[] light = new byte[RegionFileCodec.COLUMN_COUNT];
        for (int i = 0; i < RegionFileCodec.COLUMN_COUNT; i++) {
            surfaceY[i] = (short) (i % 400 - 64);
            fluidDepth[i] = (byte) (i % 17);
            kind[i] = (byte) (i % 9);
            biomeId[i] = switch (i % 4) {
                case 0 -> "minecraft:plains";
                case 1 -> "minecraft:forest";
                case 2 -> "example:crystal_fields";
                default -> null;
            };
            baseArgb[i] = 0xFF000000 | (int) ((i * 2654435761L) & 0x00FFFFFF);
            biomeTint[i] = 0xFF445566 + i;
            overlayArgb[i] = i % 5 == 0 ? 0 : 0x80112233 + i;
            light[i] = (byte) (i % 16);
        }

        return new RegionFileCodec.RegionData(
            rx, rz, 1_700_000_123_456L,
            chunkSourceOrdinal, chunkUpdateEpochSeconds, chunkSourceRevision,
            surfaceY, fluidDepth, kind, biomeId, baseArgb, biomeTint, overlayArgb, light
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
        assertArrayEquals(original.chunkSourceRevision(), decoded.chunkSourceRevision());
        assertArrayEquals(original.surfaceY(), decoded.surfaceY());
        assertArrayEquals(original.fluidDepth(), decoded.fluidDepth());
        assertArrayEquals(original.kind(), decoded.kind());
        assertArrayEquals(original.biomeId(), decoded.biomeId());
        assertArrayEquals(original.baseArgb(), decoded.baseArgb());
        assertArrayEquals(original.biomeTint(), decoded.biomeTint());
        assertArrayEquals(original.overlayArgb(), decoded.overlayArgb());
        assertArrayEquals(original.light(), decoded.light());
    }

    @Test
    void decodeRejectsSchemaWithoutBiomeIdentityPlane() throws IOException {
        final int rx = 4;
        final int rz = -8;
        final int layerOrdinal = 0;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        RegionFileCodec.encode(out, layerOrdinal, sampleData(rx, rz));
        final byte[] bytes = out.toByteArray();
        bytes[5] = 2;

        assertThrows(RegionFileCodec.RegionFileException.class, () ->
            RegionFileCodec.decode(new ByteArrayInputStream(bytes), rx, rz, layerOrdinal)
        );
    }

    @Test
    void schemaThreeCacheLoadsWithUnknownSourceRevisions() throws Exception {
        final RegionFileCodec.RegionData original = sampleData(4, -8);
        final ByteArrayOutputStream currentOut = new ByteArrayOutputStream();
        RegionFileCodec.encode(currentOut, 0, original);
        final byte[] current = currentOut.toByteArray();
        final ByteArrayOutputStream legacyOut = new ByteArrayOutputStream();
        legacyOut.write(current, 0, RegionFileCodec.HEADER_SIZE);
        final byte[] legacy = legacyOut.toByteArray();
        legacy[5] = 3;
        legacyOut.reset();
        legacyOut.write(legacy);
        final int currentEntryBytes = 13;
        final int legacyEntryBytes = 5;
        for (int chunk = 0; chunk < RegionFileCodec.CHUNK_TABLE_ENTRIES; chunk++) {
            legacyOut.write(
                current,
                RegionFileCodec.HEADER_SIZE + chunk * currentEntryBytes,
                legacyEntryBytes
            );
        }
        legacyOut.write(
            current,
            RegionFileCodec.HEADER_SIZE + RegionFileCodec.CHUNK_TABLE_SIZE,
            current.length - RegionFileCodec.HEADER_SIZE - RegionFileCodec.CHUNK_TABLE_SIZE
        );

        final RegionFileCodec.RegionData decoded = RegionFileCodec.decode(
            new ByteArrayInputStream(legacyOut.toByteArray()), 4, -8, 0
        );

        final long[] unknown = new long[RegionFileCodec.CHUNK_TABLE_ENTRIES];
        java.util.Arrays.fill(unknown, Long.MIN_VALUE);
        assertArrayEquals(unknown, decoded.chunkSourceRevision());
        assertArrayEquals(original.surfaceY(), decoded.surfaceY());
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
            new byte[RegionFileCodec.COLUMN_COUNT], new String[RegionFileCodec.COLUMN_COUNT],
            new int[RegionFileCodec.COLUMN_COUNT],
            new int[RegionFileCodec.COLUMN_COUNT], new int[RegionFileCodec.COLUMN_COUNT],
            new byte[RegionFileCodec.COLUMN_COUNT]
        ));
    }
}
