package cn.net.rms.confluxmap.core.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SummaryCodecTest {
    /** magic(4) + version(1) + rx(4) + rz(4) + mtime(8) + per-chunk flag(1) and revision(8). */
    private static final int HEADER_BYTES = 4 + 1 + 4 + 4 + 8 + SummaryCodec.CHUNKS * 9;

    @Test
    void roundTripPreservesGeneratedChunks() throws Exception {
        final SummaryCodec.Chunk[] chunks = new SummaryCodec.Chunk[SummaryCodec.CHUNKS];
        Arrays.fill(chunks, SummaryCodec.Chunk.empty());
        final SummaryCodec.Column[] columns = new SummaryCodec.Column[SummaryCodec.COLUMNS];
        Arrays.fill(columns, new SummaryCodec.Column(1, 64, 1, 1, 0, 255, 12));
        chunks[17] = new SummaryCodec.Chunk(true, 99L, columns);
        final SummaryCodec.Region input = new SummaryCodec.Region(2, -3, 1234L, chunks);
        final SummaryCodec.Region output = SummaryCodec.decode(SummaryCodec.encode(input));
        assertEquals(2, output.rx());
        assertEquals(-3, output.rz());
        assertEquals(1234L, output.sourceMcaMtimeMs());
        assertEquals(99L, output.chunks()[17].revision());
        assertEquals(
            new SummaryCodec.Column(1, 64, 1, 1, 0, 255, 12),
            output.chunks()[17].columns()[0]
        );
    }

    @Test
    void roundTripPreservesSurfaceAndFloorMaterials() throws Exception {
        final SummaryCodec.Chunk[] chunks = new SummaryCodec.Chunk[SummaryCodec.CHUNKS];
        Arrays.fill(chunks, SummaryCodec.Chunk.empty());
        final SummaryCodec.Column[] columns = new SummaryCodec.Column[SummaryCodec.COLUMNS];
        Arrays.fill(columns, new SummaryCodec.Column(
            1, 64, 2, 12, 4, 11, 14,
            "minecraft:water", "minecraft:glowstone"
        ));
        chunks[0] = new SummaryCodec.Chunk(true, 1L, columns);

        final SummaryCodec.Region decoded = SummaryCodec.decode(SummaryCodec.encode(
            new SummaryCodec.Region(0, 0, 1L, chunks)
        ));

        assertEquals("minecraft:water", decoded.chunks()[0].columns()[0].materialId());
        assertEquals("minecraft:glowstone", decoded.chunks()[0].columns()[0].floorMaterialId());
    }

    /**
     * Coarse presence answers read the flags of hundreds of regions per tile, so they must not pay
     * for the column body. Feeding a header-only prefix proves the body is never touched.
     */
    @Test
    void sampledDecodeKeepsOnlyTheColumnsVisibleAtTheRequestedLod() throws Exception {
        final SummaryCodec.Chunk[] chunks = new SummaryCodec.Chunk[SummaryCodec.CHUNKS];
        Arrays.fill(chunks, SummaryCodec.Chunk.empty());
        final SummaryCodec.Column[] columns = new SummaryCodec.Column[SummaryCodec.COLUMNS];
        for (int index = 0; index < columns.length; index++) {
            columns[index] = new SummaryCodec.Column(
                index,
                index - 128,
                index & 7,
                index & 63,
                index % 255
            );
        }
        chunks[16] = new SummaryCodec.Chunk(
            false, 77L, new SummaryCodec.Column[SummaryCodec.COLUMNS]
        );
        chunks[17] = new SummaryCodec.Chunk(true, 99L, columns);
        final SummaryCodec.Column[] laterColumns = new SummaryCodec.Column[SummaryCodec.COLUMNS];
        for (int index = 0; index < laterColumns.length; index++) {
            laterColumns[index] = new SummaryCodec.Column(
                255 - index,
                512 - index,
                (index + 1) & 7,
                (index + 1) & 63,
                (index + 1) % 255
            );
        }
        chunks[200] = new SummaryCodec.Chunk(true, 101L, laterColumns);
        final byte[] encoded = SummaryCodec.encode(new SummaryCodec.Region(2, -3, 1234L, chunks));

        final SummaryCodec.SampledRegion lodFour = SummaryCodec.decodeSampled(
            new ByteArrayInputStream(encoded), 16
        );
        final SummaryCodec.SampledRegion lodThree = SummaryCodec.decodeSampled(
            new ByteArrayInputStream(encoded), 8
        );

        assertEquals(1, lodFour.chunks()[17].columns().length);
        assertEquals(columns[8 * 16 + 8], lodFour.chunks()[17].column(0, 0));
        assertArrayEquals(
            new SummaryCodec.Column[] {
                columns[4 * 16 + 4],
                columns[4 * 16 + 12],
                columns[12 * 16 + 4],
                columns[12 * 16 + 12]
            },
            lodThree.chunks()[17].columns()
        );
        assertEquals(99L, lodFour.chunks()[17].revision());
        assertFalse(lodFour.chunks()[16].generated());
        assertEquals(77L, lodFour.chunks()[16].revision());
        assertEquals(laterColumns[8 * 16 + 8], lodFour.chunks()[200].column(0, 0));
    }

    @Test
    void generatedFlagsAreReadableWithoutTheColumnBody() throws Exception {
        final byte[] encoded = SummaryCodec.encode(regionWithGeneratedChunk(2, -3, 17, 99L));
        final byte[] headerOnly = Arrays.copyOf(encoded, HEADER_BYTES);

        final SummaryCodec.Generated generated = SummaryCodec.decodeGenerated(new ByteArrayInputStream(headerOnly));

        assertEquals(2, generated.rx());
        assertEquals(-3, generated.rz());
        assertEquals(1234L, generated.sourceMcaMtimeMs());
        assertTrue(generated.flags()[17]);
        assertFalse(generated.flags()[16]);
        assertEquals(99L, generated.maxRevision());
        assertThrows(
            Exception.class,
            () -> SummaryCodec.decode(headerOnly),
            "a full decode must still require the column body the light path skipped"
        );
    }

    @Test
    void generatedFlagsAgreeWithAFullDecode() throws Exception {
        final byte[] encoded = SummaryCodec.encode(regionWithGeneratedChunk(0, 0, 42, 7L));

        final SummaryCodec.Region full = SummaryCodec.decode(encoded);
        final SummaryCodec.Generated light = SummaryCodec.decodeGenerated(new ByteArrayInputStream(encoded));

        for (int i = 0; i < SummaryCodec.CHUNKS; i++) {
            assertEquals(full.chunks()[i].generated(), light.flags()[i], "chunk " + i + " disagreed");
        }
    }

    @Test
    void maxRevisionIgnoresUngeneratedChunks() throws Exception {
        final SummaryCodec.Chunk[] chunks = new SummaryCodec.Chunk[SummaryCodec.CHUNKS];
        Arrays.fill(chunks, SummaryCodec.Chunk.empty());
        // An ungenerated chunk carrying a high revision must not raise the reported watermark.
        chunks[3] = new SummaryCodec.Chunk(false, 5_000L, new SummaryCodec.Column[SummaryCodec.COLUMNS]);
        final SummaryCodec.Column[] columns = new SummaryCodec.Column[SummaryCodec.COLUMNS];
        Arrays.fill(columns, new SummaryCodec.Column(1, 64, 1, 1, 0));
        chunks[4] = new SummaryCodec.Chunk(true, 12L, columns);
        final byte[] encoded = SummaryCodec.encode(new SummaryCodec.Region(0, 0, 1L, chunks));

        assertEquals(12L, SummaryCodec.decodeGenerated(new ByteArrayInputStream(encoded)).maxRevision());
    }

    private static SummaryCodec.Region regionWithGeneratedChunk(
        final int rx, final int rz, final int chunkIndex, final long revision
    ) {
        final SummaryCodec.Chunk[] chunks = new SummaryCodec.Chunk[SummaryCodec.CHUNKS];
        Arrays.fill(chunks, SummaryCodec.Chunk.empty());
        final SummaryCodec.Column[] columns = new SummaryCodec.Column[SummaryCodec.COLUMNS];
        Arrays.fill(columns, new SummaryCodec.Column(1, 64, 1, 1, 0));
        chunks[chunkIndex] = new SummaryCodec.Chunk(true, revision, columns);
        return new SummaryCodec.Region(rx, rz, 1234L, chunks);
    }

    @Test
    void versionOneSummaryIsRejectedSoIncompleteChunkDataCannotSurviveTheFix() {
        final SummaryCodec.Chunk[] chunks = new SummaryCodec.Chunk[SummaryCodec.CHUNKS];
        Arrays.fill(chunks, SummaryCodec.Chunk.empty());
        final byte[] encoded = SummaryCodec.encode(new SummaryCodec.Region(0, 0, 0L, chunks));
        encoded[4] = 1;

        assertThrows(ProtoException.class, () -> SummaryCodec.decode(encoded));
    }
}
