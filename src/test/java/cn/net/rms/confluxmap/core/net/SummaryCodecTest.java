package cn.net.rms.confluxmap.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SummaryCodecTest {
    @Test
    void roundTripPreservesGeneratedChunks() throws Exception {
        final SummaryCodec.Chunk[] chunks = new SummaryCodec.Chunk[SummaryCodec.CHUNKS];
        Arrays.fill(chunks, SummaryCodec.Chunk.empty());
        final SummaryCodec.Column[] columns = new SummaryCodec.Column[SummaryCodec.COLUMNS];
        Arrays.fill(columns, new SummaryCodec.Column(1, 64, 1, 1, 0));
        chunks[17] = new SummaryCodec.Chunk(true, 99L, columns);
        final SummaryCodec.Region input = new SummaryCodec.Region(2, -3, 1234L, chunks);
        final SummaryCodec.Region output = SummaryCodec.decode(SummaryCodec.encode(input));
        assertEquals(2, output.rx());
        assertEquals(-3, output.rz());
        assertEquals(1234L, output.sourceMcaMtimeMs());
        assertEquals(99L, output.chunks()[17].revision());
        assertEquals(new SummaryCodec.Column(1, 64, 1, 1, 0), output.chunks()[17].columns()[0]);
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
